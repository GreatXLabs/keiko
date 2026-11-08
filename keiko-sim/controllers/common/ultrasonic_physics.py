#!/usr/bin/env python3
"""
Modelo Acústico Realista de Sensores Ultrasónicos HC-SR04 (40 kHz) - Proyecto Orquitas (Keiko)
Configuración: 3 Sensores Ultrasónicos Frontales Fijos a 5.5 cm de altura (yaw = 0°, pitch = 0°).

Física del sensor a 5.5 cm de altura (validada con resultados_altura_us.txt):
1. Detección de obstáculos 3D (6x6x6 cm) y muros (18 cm):
   - A 5.5 cm de altura el haz acústico (cono ±15°) detecta obstáculos de 6 cm de alto con >90% de confiabilidad.
   - Los muros de cartón devuelven eco fuerte hasta > 200 cm.
   - Reflexión especular (efecto espejo): si el ángulo de incidencia respecto a la normal supera ~42°,
     se produce pérdida de eco o dispersión multipath.
2. Salida (30 cm):
   - A través del hueco de 30 cm, los haces frontales viajan hacia el exterior (> 185-250 cm).
3. Canicas (16 mm):
   - A 5.5 cm de altura, el pulso ultrasónico pasa por encima de las canicas de 16 mm (que reposan a Z=0.008 m).
   - Por tanto, las canicas son prácticamente invisibles para los ultrasonidos (no generan falsas alarmas < 25 cm).
4. Ruido térmico y cuantización:
   - Ruido proporcional a la distancia y cuantización del timer del microcontrolador (~0.25 cm).
   - Zona ciega mínima (< 2.5 cm) por ringing mecánico del piezoeléctrico.
"""

import math
import random
from collections import deque

SENSOR_MOUNT_GEOM_3US = {
    "us_left":   (0.1325, +0.054, 0.0),   # 5.4 cm izquierda, mirando al frente (yaw = 0)
    "us_center": (0.1325,  0.000, 0.0),   # centro, mirando al frente (yaw = 0)
    "us_right":  (0.1325, -0.054, 0.0),   # 5.4 cm derecha, mirando al frente (yaw = 0)
}


class UltrasonicPhysicsModel:
    def __init__(
        self,
        mode="realistic",
        specular_crit_deg=42.0,
        seed=None,
    ):
        self.mode = mode.lower().strip()
        self.specular_crit_rad = math.radians(specular_crit_deg)
        self.rng = random.Random(seed)

    def reset_history(self):
        return None

    def apply_wall_incidence(self, dist_cm, incidence_rad):
        """Degrada la lectura cuando el haz incide de forma muy oblicua contra una pared lisa."""
        if self.mode == "ideal" or dist_cm >= 245.0:
            return dist_cm
        inc = abs(incidence_rad)
        if inc <= self.specular_crit_rad:
            return dist_cm
        # Entre 42° y 65°: rebote multipath (alargamiento aparente) o pérdida de eco (250 cm)
        excess = (inc - self.specular_crit_rad) / max(1e-6, (math.radians(70.0) - self.specular_crit_rad))
        if excess > 0.65 or self.rng.random() < 0.45:
            return 250.0
        multipath_factor = 1.28 + 0.35 * min(1.0, excess)
        return min(250.0, dist_cm * multipath_factor)

    def apply(self, raw_readings, robot_pose=None, active_obstacles_xy=None):
        """
        Aplica la física acústica real del HC-SR04 a un diccionario {sensor_name: dist_cm}.
        A 5.5 cm de altura, los 3 ultrasonidos poseen un cono acústico de ~30° (±15°)
        que detecta obstáculos 3D (6x6x6 cm) hasta ~30 cm y paredes perimetrales,
        mientras que las canicas de 1.6 cm no obstruyen el haz principal a dicha altura.
        """
        if self.mode == "ideal":
            return {k: max(2.0, min(250.0, float(v))) for k, v in raw_readings.items()}

        out = {}
        for s_name, raw_val in raw_readings.items():
            val = float(raw_val)

            # 1. Intersección acústica con obstáculos 3D en el cono acústico (55° total, ±26.0° según acustica.py)
            if robot_pose is not None and active_obstacles_xy and s_name in SENSOR_MOUNT_GEOM_3US:
                rx, ry, yaw = robot_pose
                lx, ly, _ = SENSOR_MOUNT_GEOM_3US[s_name]
                sx = rx + lx * math.cos(yaw) - ly * math.sin(yaw)
                sy = ry + lx * math.sin(yaw) + ly * math.cos(yaw)
                syaw = yaw

                for ox, oy in active_obstacles_xy:
                    dx = ox - sx
                    dy = oy - sy
                    dist_m = math.hypot(dx, dy)
                    if dist_m > 0.45:
                        continue
                    ang = abs((math.atan2(dy, dx) - syaw + math.pi) % (2.0 * math.pi) - math.pi)
                    box_angular_radius = math.asin(min(0.85, 0.042 / max(0.042, dist_m)))
                    if (ang - box_angular_radius) <= math.radians(24.0):
                        # Obstáculo dentro del cono acústico: eco en la cara más cercana
                        d_surf_cm = max(2.0, (dist_m - 0.035) * 100.0)
                        if d_surf_cm < val:
                            val = d_surf_cm

            # 2. Zona ciega (< 2.5 cm), ruido térmico y cuantización
            if val < 2.5:
                val = 2.5 + abs(self.rng.gauss(0.0, 0.8))
            elif val < 240.0:
                sigma = 0.35 + 0.010 * val
                val = val + self.rng.gauss(0.0, sigma)

            # 3. Cuantización típica de microcontrolador (~0.25 cm)
            val = round(val / 0.25) * 0.25
            out[s_name] = max(2.0, min(250.0, val))

        return out


class SensorBorderFilter:
    """
    Filtro de validación de frontera ejecutado en el Cliente (Robot):
    Combina mediana de ventana corta (3 muestras) con suavizado exponencial rápido (EMA alpha=0.65).
    """

    def __init__(self, sensor_names, alpha=0.65, default_cm=250.0):
        self.alpha = alpha
        self.default_cm = default_cm
        self.windows = {}
        self.ema = {}

    def update(self, raw_dict):
        filtered = {}
        for name, raw_val in raw_dict.items():
            clamped = max(2.0, min(250.0, float(raw_val)))
            if name not in self.windows:
                self.windows[name] = deque([clamped] * 3, maxlen=3)
                self.ema[name] = clamped
            else:
                w = self.windows[name]
                w.append(clamped)
                med = sorted(w)[1]
                self.ema[name] = self.alpha * med + (1.0 - self.alpha) * self.ema[name]
            filtered[name] = round(self.ema[name], 2)
        return filtered
