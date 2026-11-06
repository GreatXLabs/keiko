#!/usr/bin/env python3
"""
Modelo Óptico Realista de Sensores Láser ToF VL53L0X sobre Servos SG90 - Proyecto Orquitas (Keiko)
Fila inferior: 2 sensores VL53L0X a 1.8 cm de altura, montados sobre brazos de 2.0 cm en 2 servos SG90.

Características físicas (validadas con pruebas/simulacion/sim_laser.py y fit_laser.py):
1. Geometría cinemática:
   - Servo izquierdo: eje vertical en (+0.1405, +0.083, 0.018) m.
   - Servo derecho: eje vertical en (+0.1405, -0.083, 0.018) m.
   - Radio del aspa/brazo: 2.0 cm (0.020 m) desde el eje al chip.
   - Ángulo en espejo: 0° recto al frente, negativos (hasta -45°) hacia el centro/picker,
     positivos (hasta +60°) hacia afuera.
   - Traslación cinemática del chip:
     Izquierdo: X = 0.1405 + 0.020*cos(theta), Y = +0.083 + 0.020*sin(theta), yaw = theta
     Derecho:   X = 0.1405 + 0.020*cos(theta), Y = -0.083 - 0.020*sin(theta), yaw = -theta
2. Física óptica del VL53L0X:
   - Cono FOV de ~25° (±12.5°), inclinación leve hacia el piso.
   - Lectura de reposo en piso limpio: ~37–42 cm (promedio calibrado ~38.5 cm).
   - Canicas blancas opacas (Ø 16 mm, rho >= 1.0):
     Caída nítida de distancia hasta ~15–18 cm en el cono.
   - Obstáculos (6x6x6 cm) y muros: lectura directa de la superficie dentro del cono.
   - Rango útil 200 cm (retorna 999 cm o 200 cm en espacio libre/timeout).
"""

import math
import random

ARM_RADIUS_M = 0.020
CHIP_HEIGHT_M = 0.018

SERVO_AXIS_LEFT = (0.1405, +0.083)
SERVO_AXIS_RIGHT = (0.1405, -0.083)

CONE_HALF_FOV_RAD = math.radians(12.5)  # cono total 25°
RESTING_FLOOR_CM = 38.5                 # reposo en piso limpio ~37-42 cm
MAX_MARBLE_DETECT_CM = 22.0             # alcance máximo de detección de canica


class LaserPhysicsModel:
    def __init__(self, mode="realistic", seed=None):
        self.mode = mode.lower().strip()
        self.rng = random.Random(seed)

    def get_chip_pose_local(self, side, servo_angle_rad):
        """
        Retorna (x_local, y_local, yaw_local) del chip en el sistema del robot (+X frente, +Y izq).
        side: 'left' o 'right'
        servo_angle_rad: ángulo en espejo (negativo = hacia adentro/picker, positivo = afuera).
        """
        if side == "left":
            ax, ay = SERVO_AXIS_LEFT
            yaw = servo_angle_rad
            cx = ax + ARM_RADIUS_M * math.cos(yaw)
            cy = ay + ARM_RADIUS_M * math.sin(yaw)
        else:
            ax, ay = SERVO_AXIS_RIGHT
            yaw = -servo_angle_rad
            cx = ax + ARM_RADIUS_M * math.cos(yaw)
            cy = ay + ARM_RADIUS_M * math.sin(yaw)
        return cx, cy, yaw

    def compute_reading(self, raw_sensor_val_cm, chip_world_pose, marble_positions_world):
        """
        Combina la lectura de fondo (piso/pared/obstáculo de Webots) con la presencia de canicas
        según el modelo ajustado en sim_laser.py.
        """
        if self.mode == "ideal":
            return max(2.0, min(200.0, float(raw_sensor_val_cm)))

        cx, cy, chip_yaw = chip_world_pose

        # 1. Base de fondo: lectura reportada por el raycast de Webots o reposo en piso
        base_val = float(raw_sensor_val_cm)
        if base_val <= 0.0 or base_val > 195.0:
            # Vacío o piso rasante
            base_val = RESTING_FLOOR_CM + self.rng.gauss(0.0, 1.2)
        else:
            # Webots detectó muro, obstáculo o piso
            base_val = min(base_val, RESTING_FLOOR_CM + self.rng.gauss(0.0, 1.0))

        # 2. Verificar si hay canicas blancas dentro del cono del sensor
        best_marble_reading = None
        for mx, my in marble_positions_world:
            dx = mx - cx
            dy = my - cy
            dist_cm = math.hypot(dx, dy) * 100.0
            if dist_cm > MAX_MARBLE_DETECT_CM:
                continue

            # Ángulo relativo respecto al eje óptico del sensor
            ang = abs((math.atan2(dy, dx) - chip_yaw + math.pi) % (2.0 * math.pi) - math.pi)
            if ang <= CONE_HALF_FOV_RAD:
                # Canica blanca en el cono: caída nítida de distancia (calibrada con fit_laser.py)
                ratio = dist_cm / 22.0
                m_reading = 0.72 * dist_cm + 0.28 * RESTING_FLOOR_CM * (ratio ** 2)
                if best_marble_reading is None or m_reading < best_marble_reading:
                    best_marble_reading = m_reading

        if best_marble_reading is not None and best_marble_reading < base_val:
            final_reading = best_marble_reading
        else:
            final_reading = base_val

        # 3. Ruido gaussiano leve del sensor ToF (~0.4 cm)
        final_reading += self.rng.gauss(0.0, 0.4)
        return round(max(2.0, min(200.0, final_reading)), 1)
