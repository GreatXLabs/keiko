#!/usr/bin/env python3
"""
Modelo Acústico Realista de Sensores Ultrasónicos HC-SR04 (40 kHz) - Proyecto Orquitas (Keiko)

Simula las limitaciones físicas reales del transductor ultrasónico HC-SR04 en la Bahía de Caza:
1. Sección eficaz acústica reducida (Rayleigh/Mie) de una esfera de vidrio de 16 mm (canica):
   - Mientras un muro o borrador plano devuelve eco hasta > 200 cm en un cono de ±15°,
     una canica esférica de 16 mm dispersa la onda esféricamente (energía de retorno ~ r^2 / d^4).
   - Solo supera el umbral del comparador del HC-SR04 a corta distancia (3 a 32 cm) y dentro
     del lóbulo central (±9.5°). Más lejos de 32 cm (o con pérdida de eco intermitente entre
     24 y 32 cm), la onda pasa de largo y el sensor mide el muro/obstáculo de fondo.
2. Reflexión especular (efecto espejo) en muros de cartón y caras lisas:
   - Si el ángulo de incidencia respecto a la normal supera ~42°, el pulso rebota hacia otro
     sector de la bahía (multipath alargado o timeout de 250 cm).
3. Ruido térmico/electrónico proporcional a la distancia + cuantización del timer (~0.3 cm).
4. Zona ciega mínima (< 2.5 cm) por ringing mecánico del piezoeléctrico tras el disparo.
5. Filtro de validación de frontera (Mediana de 3 muestras + EMA) en el cliente para suprimir
   picos espurios de crosstalk sin introducir retardo excesivo.
"""

import math
import random
from collections import deque

# Geometría local de los sensores en el chasis (x_m, y_m, yaw_rad)
SENSOR_MOUNT_GEOM = {
    "us_low_left":    (0.128, +0.052, +0.3840),   # +22° (izquierda)
    "us_low_center":  (0.134,  0.000,  0.0000),   #   0° (centro)
    "us_low_right":   (0.128, -0.052, -0.3840),   # -22° (derecha)
    "us_high_left":   (0.122, +0.052, +0.3840),   # +22° (izquierda)
    "us_high_center": (0.128,  0.000,  0.0000),   #   0° (centro)
    "us_high_right":  (0.122, -0.052, -0.3840),   # -22° (derecha)
    "us_side_left":   (0.000, +0.075, +1.5708),   # +90°
    "us_side_right":  (0.000, -0.075, -1.5708),   # -90°
    "us_rear_left":  (-0.125, +0.040,  3.1416),   # 180°
    "us_rear_right": (-0.125, -0.040,  3.1416),   # 180°
}

COAXIAL_HIGH_PAIR = {
    "us_low_left": "us_high_left",
    "us_low_center": "us_high_center",
    "us_low_right": "us_high_right",
}


class UltrasonicPhysicsModel:
    def __init__(
        self,
        mode="realistic",
        max_marble_range_cm=32.0,
        marble_cone_deg=14.0,
        specular_crit_deg=42.0,
        seed=None,
    ):
        self.mode = mode.lower().strip()
        self.max_marble_range_cm = max_marble_range_cm
        self.marble_cone_rad = math.radians(marble_cone_deg)
        self.specular_crit_rad = math.radians(specular_crit_deg)
        self.rng = random.Random(seed)

    def reset_history(self):
        """Reinicia estado interno entre muestras independientes."""
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

    def apply(self, raw_readings, robot_pose=None, marble_positions=None):
        """
        Aplica la física acústica real del HC-SR04 a un diccionario {sensor_name: dist_cm}.
        Si robot_pose=(rx, ry, yaw) y marble_positions=[(mx, my), ...] están presentes,
        verifica si el eco de una canica en los sensores bajos es físicamente detectable.
        """
        if self.mode == "ideal":
            return {k: max(2.0, min(250.0, float(v))) for k, v in raw_readings.items()}

        out = {k: float(v) for k, v in raw_readings.items()}

        # 1. Límite físico de visibilidad acústica de las canicas (16 mm) en el plano bajo
        for low_name, high_name in COAXIAL_HIGH_PAIR.items():
            if low_name not in out or high_name not in out:
                continue
            d_low = out[low_name]
            d_high = out[high_name]

            # Si el sensor bajo reporta algo mucho más cercano que el sensor alto coaxial,
            # el raycast de Webots golpeó un objeto de baja altura (una canica de 16 mm).
            if (d_high - d_low) > 8.0:
                can_detect_marble = True

                # Regla 1a: Por encima de max_marble_range_cm (~32 cm), el eco esférico de 16 mm
                # cae por debajo del umbral del comparador analógico del HC-SR04.
                if d_low > self.max_marble_range_cm:
                    can_detect_marble = False
                # Regla 1b: En la zona límite (24 a 32 cm), el eco de la canica es débil e intermitente
                elif d_low > 24.0:
                    drop_prob = 0.25 * ((d_low - 24.0) / max(1.0, self.max_marble_range_cm - 24.0))
                    if self.rng.random() < drop_prob:
                        can_detect_marble = False

                # Regla 1c: Si conocemos las posiciones reales de las canicas en el simulador,
                # verificar también que la canica esté dentro del lóbulo central (±9.5°)
                if can_detect_marble and robot_pose is not None and marble_positions:
                    rx, ry, yaw = robot_pose
                    sx_loc, sy_loc, syaw_loc = SENSOR_MOUNT_GEOM.get(low_name, (0.13, 0.0, 0.0))
                    cos_r = math.cos(yaw)
                    sin_r = math.sin(yaw)
                    sx = rx + sx_loc * cos_r - sy_loc * sin_r
                    sy = ry + sx_loc * sin_r + sy_loc * cos_r
                    beam_yaw = yaw + syaw_loc

                    in_lobe = False
                    for mx, my in marble_positions:
                        dist_cm = math.hypot(mx - sx, my - sy) * 100.0
                        ang = abs((math.atan2(my - sy, mx - sx) - beam_yaw + math.pi) % (2 * math.pi) - math.pi)
                        if dist_cm <= self.max_marble_range_cm and ang <= self.marble_cone_rad:
                            in_lobe = True
                            break
                    if not in_lobe:
                        can_detect_marble = False

                if not can_detect_marble:
                    # La onda acústica no rebota con suficiente energía en la canica y mide el fondo
                    out[low_name] = d_high + self.rng.gauss(0.0, 0.6)

        # 2. Zona muerta (< 2.5 cm), ruido térmico/electrónico y cuantización
        for s_name, val in list(out.items()):
            if val < 2.5:
                # Ringing piezoeléctrico en zona ciega (< 2.5 cm)
                val = 2.5 + abs(self.rng.gauss(0.0, 0.8))
            else:
                sigma = 0.45 + 0.012 * val
                val = val + self.rng.gauss(0.0, sigma)

            # Cuantización típica de muestreo por timer (~0.25 cm)
            val = round(val / 0.25) * 0.25
            out[s_name] = max(2.0, min(250.0, val))

        return out


class SensorBorderFilter:
    """
    Filtro de validación de frontera ejecutado en el Cliente (Robot):
    Combina mediana de ventana corta (3 muestras) para eliminar picos aislados de crosstalk
    con suavizado exponencial rápido (EMA alpha=0.65).
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
