#!/usr/bin/env python3
"""
Generador de datasets sinteticos para las 2 redes neuronales de Orquitas (Keiko).

Genera, sin dependencias externas (solo stdlib), dos CSV balanceados y fisicamente
consistentes con el modelo acustico/optico del proyecto (rig mega-3us-2laser):

1. dataset_ultrasonico.csv  -> dist_us, class_label
   Clases: BLOQUEO / ESPACIO_LIBRE / SALIDA
2. dataset_canicas.csv      -> d_us_izq, d_us_cen, d_us_der,
                               d_laser_izq, d_laser_der, angulo_servo, class_label
   Clases: NINGUNA / LOBITO_IZQ / LOBITO_CENTRO / LOBITO_DER

Reglas fisicas aplicadas:
- Ultrasonido HC-SR04: cuantizacion de 0.25 cm, ruido gaussiano
  sigma = 0.35 + 0.01 * distancia (ver controllers/common/ultrasonic_physics.py).
  Las canicas (1.6 cm) quedan por debajo del haz a 5.5 cm de altura: no bloquean el US.
- Laser ToF VL53L0X: reposo sobre piso limpio 37-42 cm, canica 3.5-21.0 cm, ruido ~0.4 cm
  (ver controllers/common/laser_physics.py). Barrido espejo [-45.0, +60.0] grados.
- Canica: laser bajo (3.5-21.0 cm) en angulo frontal/interior [-45, +18] deg + US despejado
  detras de la canica (>= 40 cm y sin gradiente de muro cercano).
- Obstaculo/muro: lectura baja con US < 36 cm, gradiente oblicuo rasante,
  lecturas intermedias (>= 22.5 cm), o angulos exteriores [+19.5, +60] deg (muro lateral).

Uso:
    python3 keiko-sim/scripts/generate_datasets.py
"""

import csv
import math
import os
import random

DATASETS_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "datasets")
)
SEED = 42
SAMPLES_PER_CLASS = 2000
US_QUANTUM_CM = 0.25

# Bandas oficiales de clasificacion (cm).
US_CLASSES = (
    ("BLOQUEO", (2.0, 17.5)),
    ("ESPACIO_LIBRE", (19.5, 122.0)),
    ("SALIDA", (132.0, 250.0)),
)
LASER_FLOOR = (35.0, 43.0)       # reposo sobre piso limpio (media 38.5 cm, con ruido 35-43 cm)
SERVO_MIN_DEG = -45.0
SERVO_MAX_DEG = 60.0


def noisy_us(rng, true_cm, band):
    """Lectura HC-SR04 de una distancia real, restringida a la banda de la clase."""
    target = max(band[0], min(band[1], true_cm))
    for _ in range(50):
        sigma = 0.35 + 0.010 * target
        value = max(2.0, min(250.0, target + rng.gauss(0.0, sigma)))
        value = round(value / US_QUANTUM_CM) * US_QUANTUM_CM
        if band[0] <= value <= band[1]:
            return round(value, 2)
    return round(round(target / US_QUANTUM_CM) * US_QUANTUM_CM, 2)


def noisy_laser(rng, true_cm, band):
    """Lectura VL53L0X (0.1 cm, sigma 0.4 cm) restringida a la banda esperada."""
    target = max(band[0], min(band[1], true_cm))
    for _ in range(50):
        value = round(target + rng.gauss(0.0, 0.4), 1)
        if band[0] <= value <= band[1]:
            return value
    return round(target, 1)


def sample_bay_us(rng, min_cm, max_cm=250.0):
    """Muestrea distancias US realistas de la bahia (alta densidad entre 40-115 cm)."""
    lo = max(2.0, min_cm)
    hi = max(lo, max_cm)
    if lo < 115.0 and rng.random() < 0.65:
        return noisy_us(rng, rng.uniform(lo, min(hi, 115.0)), (lo, hi))
    return noisy_us(rng, rng.uniform(lo, hi), (lo, hi))


def build_ultrasonic_rows(rng):
    rows = []
    for label, band in US_CLASSES:
        if label == "ESPACIO_LIBRE":
            n_low = SAMPLES_PER_CLASS // 3
            n_high = SAMPLES_PER_CLASS // 4
            n_mid = SAMPLES_PER_CLASS - n_low - n_high
            for _ in range(n_low):
                rows.append((noisy_us(rng, rng.uniform(19.5, 35.0), band), label))
            for _ in range(n_mid):
                rows.append((noisy_us(rng, rng.uniform(35.0, 105.0), band), label))
            for _ in range(n_high):
                rows.append((noisy_us(rng, rng.uniform(105.0, 122.0), band), label))
        elif label == "BLOQUEO":
            n_edge = SAMPLES_PER_CLASS // 3
            for _ in range(n_edge):
                rows.append((noisy_us(rng, rng.uniform(12.5, 17.5), band), label))
            for _ in range(SAMPLES_PER_CLASS - n_edge):
                rows.append((noisy_us(rng, rng.uniform(2.0, 17.5), band), label))
        else:
            n_edge = SAMPLES_PER_CLASS // 4
            for _ in range(n_edge):
                rows.append((noisy_us(rng, rng.uniform(132.0, 150.0), band), label))
            for _ in range(SAMPLES_PER_CLASS - n_edge):
                rows.append((noisy_us(rng, rng.uniform(132.0, 250.0), band), label))
    rows[0] = (2.00, "BLOQUEO")
    for i, (_, lbl) in enumerate(rows):
        if lbl == "SALIDA":
            rows[i] = (250.00, "SALIDA")
            break
    rng.shuffle(rows)
    return rows


def build_marble_rows(rng):
    rows = []
    # 1. NINGUNA (Piso limpio, bandas intermedias >= 22.5 cm, muros cercanos < 36 cm, angulos >= 20 deg)
    for idx in range(SAMPLES_PER_CLASS):
        if idx == 0:
            rows.append((2.0, 2.0, 2.0, 2.0, 2.0, -45.0, "NINGUNA"))
            continue
        if idx == 1:
            rows.append((250.0, 250.0, 250.0, 43.0, 43.0, 60.0, "NINGUNA"))
            continue

        scenario = rng.choices(
            ("limpio", "muro_frontal", "muro_oblicuo_izq", "muro_oblicuo_der",
             "muro_oblicuo_inward_izq", "muro_oblicuo_inward_der",
             "muro_rasante_grad_izq", "muro_rasante_grad_der",
             "muro_lateral_izq", "muro_lateral_der",
             "muro_exterior_izq", "muro_exterior_der", "muro_exterior_ambos",
             "obstaculo_izq", "obstaculo_der", "obstaculo_frontal",
             "esquina_curva_izq", "esquina_curva_der",
             "muro_especular_izq", "muro_especular_der",
             "laser_medio_izq", "laser_medio_der", "laser_medio_ambos",
             "contacto_inmediato"),
            weights=(5, 5, 5, 5, 10, 10, 8, 8, 6, 6, 14, 14, 5, 4, 4, 4, 5, 5, 3, 3, 12, 12, 7, 5),
        )[0]
        las_izq = noisy_laser(rng, rng.uniform(*LASER_FLOOR), LASER_FLOOR)
        las_der = noisy_laser(rng, rng.uniform(*LASER_FLOOR), LASER_FLOOR)
        us_izq = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
        us_cen = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
        us_der = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
        servo = round(rng.uniform(SERVO_MIN_DEG, SERVO_MAX_DEG), 1)

        if scenario == "limpio":
            pass
        elif scenario == "muro_frontal":
            d_m = rng.uniform(3.0, 32.0)
            las_izq = noisy_laser(rng, d_m, (2.0, 34.0))
            las_der = noisy_laser(rng, d_m, (2.0, 34.0))
            us_cen = noisy_us(rng, d_m, (2.0, 35.5))
            us_izq = noisy_us(rng, d_m + rng.uniform(-1.0, 3.0), (2.0, 35.5))
            us_der = noisy_us(rng, d_m + rng.uniform(-1.0, 3.0), (2.0, 35.5))
        elif scenario == "muro_oblicuo_izq":
            d_w = rng.uniform(4.0, 28.0)
            las_izq = noisy_laser(rng, d_w, (2.0, 30.0))
            las_der = noisy_laser(rng, rng.uniform(d_w + 2.0, min(38.0, d_w + 14.0)), (d_w + 1.5, 40.0))
            us_izq = noisy_us(rng, d_w + rng.uniform(-1.0, 5.0), (2.5, 35.5))
            us_cen = noisy_us(rng, d_w + rng.uniform(6.0, 18.0), (3.0, 52.0))
            us_der = noisy_us(rng, d_w + rng.uniform(16.0, 36.0), (5.0, 80.0))
        elif scenario == "muro_oblicuo_der":
            d_w = rng.uniform(4.0, 28.0)
            las_der = noisy_laser(rng, d_w, (2.0, 30.0))
            las_izq = noisy_laser(rng, rng.uniform(d_w + 2.0, min(38.0, d_w + 14.0)), (d_w + 1.5, 40.0))
            us_der = noisy_us(rng, d_w + rng.uniform(-1.0, 5.0), (2.5, 35.5))
            us_cen = noisy_us(rng, d_w + rng.uniform(6.0, 18.0), (3.0, 52.0))
            us_izq = noisy_us(rng, d_w + rng.uniform(16.0, 36.0), (5.0, 80.0))
        elif scenario == "muro_oblicuo_inward_izq":
            servo = round(rng.uniform(-45.0, 18.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(2.5, 21.0), (2.0, 22.0))
            las_der = noisy_laser(rng, rng.uniform(24.0, 43.0), (22.5, 43.0))
            us_izq = noisy_us(rng, rng.uniform(10.0, 35.5), (10.0, 36.0))
            us_cen = noisy_us(rng, rng.uniform(us_izq + 1.0, 48.0), (12.0, 52.0))
            us_der = noisy_us(rng, rng.uniform(us_cen + 1.0, 75.0), (16.0, 90.0))
        elif scenario == "muro_oblicuo_inward_der":
            servo = round(rng.uniform(-45.0, 18.0), 1)
            las_der = noisy_laser(rng, rng.uniform(2.5, 21.0), (2.0, 22.0))
            las_izq = noisy_laser(rng, rng.uniform(24.0, 43.0), (22.5, 43.0))
            us_der = noisy_us(rng, rng.uniform(10.0, 35.5), (10.0, 36.0))
            us_cen = noisy_us(rng, rng.uniform(us_der + 1.0, 48.0), (12.0, 52.0))
            us_izq = noisy_us(rng, rng.uniform(us_cen + 1.0, 75.0), (16.0, 90.0))
        elif scenario == "muro_rasante_grad_izq":
            servo = round(rng.uniform(-45.0, 20.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(2.0, 12.0), (2.0, 13.0))
            las_der = noisy_laser(rng, rng.uniform(26.0, 43.0), (24.0, 43.0))
            us_izq = noisy_us(rng, rng.uniform(16.0, 42.0), (15.0, 44.0))
            us_cen = noisy_us(rng, us_izq + rng.uniform(18.0, 45.0), (34.0, 95.0))
            us_der = noisy_us(rng, us_cen + rng.uniform(16.0, 45.0), (52.0, 140.0))
        elif scenario == "muro_rasante_grad_der":
            servo = round(rng.uniform(-45.0, 20.0), 1)
            las_der = noisy_laser(rng, rng.uniform(2.0, 12.0), (2.0, 13.0))
            las_izq = noisy_laser(rng, rng.uniform(26.0, 43.0), (24.0, 43.0))
            us_der = noisy_us(rng, rng.uniform(16.0, 42.0), (15.0, 44.0))
            us_cen = noisy_us(rng, us_der + rng.uniform(18.0, 45.0), (34.0, 95.0))
            us_izq = noisy_us(rng, us_cen + rng.uniform(16.0, 45.0), (52.0, 140.0))
        elif scenario == "muro_lateral_izq":
            servo = round(rng.uniform(-45.0, 18.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(2.0, 21.0), (2.0, 22.0))
            las_der = noisy_laser(rng, rng.uniform(24.0, 43.0), (22.5, 43.0))
            us_izq = noisy_us(rng, rng.uniform(2.5, 35.0), (2.5, 36.0))
            us_cen = noisy_us(rng, rng.uniform(us_izq, 52.0), (2.5, 56.0))
            us_der = noisy_us(rng, rng.uniform(us_cen, 90.0), (2.5, 115.0))
        elif scenario == "muro_lateral_der":
            servo = round(rng.uniform(-45.0, 18.0), 1)
            las_der = noisy_laser(rng, rng.uniform(2.0, 21.0), (2.0, 22.0))
            las_izq = noisy_laser(rng, rng.uniform(24.0, 43.0), (22.5, 43.0))
            us_der = noisy_us(rng, rng.uniform(2.5, 35.0), (2.5, 36.0))
            us_cen = noisy_us(rng, rng.uniform(us_der, 52.0), (2.5, 56.0))
            us_izq = noisy_us(rng, rng.uniform(us_cen, 90.0), (2.5, 115.0))
        elif scenario == "muro_exterior_izq":
            # Barrido exterior [+20..+60]: pared lateral aunque US frontal este despejado
            servo = round(rng.uniform(20.0, 60.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(2.0, 22.0), (2.0, 23.0))
            las_der = noisy_laser(rng, rng.uniform(24.0, 43.0), (22.5, 43.0))
            us_izq = noisy_us(rng, rng.uniform(15.0, 250.0), (12.0, 250.0))
            us_cen = noisy_us(rng, rng.uniform(18.0, 250.0), (15.0, 250.0))
            us_der = noisy_us(rng, rng.uniform(18.0, 250.0), (15.0, 250.0))
        elif scenario == "muro_exterior_der":
            servo = round(rng.uniform(20.0, 60.0), 1)
            las_der = noisy_laser(rng, rng.uniform(2.0, 22.0), (2.0, 23.0))
            las_izq = noisy_laser(rng, rng.uniform(24.0, 43.0), (22.5, 43.0))
            us_der = noisy_us(rng, rng.uniform(15.0, 250.0), (12.0, 250.0))
            us_cen = noisy_us(rng, rng.uniform(18.0, 250.0), (15.0, 250.0))
            us_izq = noisy_us(rng, rng.uniform(18.0, 250.0), (15.0, 250.0))
        elif scenario == "muro_exterior_ambos":
            servo = round(rng.uniform(20.0, 60.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(2.5, 22.0), (2.0, 23.0))
            las_der = noisy_laser(rng, rng.uniform(2.5, 22.0), (2.0, 23.0))
            us_izq = noisy_us(rng, rng.uniform(18.0, 120.0), (15.0, 120.0))
            us_cen = noisy_us(rng, rng.uniform(20.0, 120.0), (18.0, 120.0))
            us_der = noisy_us(rng, rng.uniform(18.0, 120.0), (15.0, 120.0))
        elif scenario == "obstaculo_izq":
            d_obs = rng.uniform(3.0, 22.0)
            las_izq = noisy_laser(rng, d_obs, (2.0, 24.0))
            us_izq = noisy_us(rng, d_obs + rng.uniform(-1.0, 4.0), (2.0, 30.0))
            us_cen = noisy_us(rng, rng.uniform(d_obs, 35.0), (2.0, 38.0))
        elif scenario == "obstaculo_der":
            d_obs = rng.uniform(3.0, 22.0)
            las_der = noisy_laser(rng, d_obs, (2.0, 24.0))
            us_der = noisy_us(rng, d_obs + rng.uniform(-1.0, 4.0), (2.0, 30.0))
            us_cen = noisy_us(rng, rng.uniform(d_obs, 35.0), (2.0, 38.0))
        elif scenario == "obstaculo_frontal":
            d_obs = rng.uniform(3.0, 22.0)
            las_izq = noisy_laser(rng, d_obs, (2.0, 24.0))
            las_der = noisy_laser(rng, d_obs, (2.0, 24.0))
            us_cen = noisy_us(rng, d_obs + rng.uniform(-1.0, 3.0), (2.0, 30.0))
        elif scenario == "esquina_curva_izq":
            d_c = rng.uniform(3.0, 20.0)
            las_izq = noisy_laser(rng, d_c, (2.0, 21.5))
            las_der = noisy_laser(rng, rng.uniform(d_c, min(32.0, d_c + 10.0)), (2.0, 33.0))
            us_izq = noisy_us(rng, rng.uniform(d_c, min(33.0, d_c + 14.0)), (3.0, 34.0))
            us_cen = noisy_us(rng, rng.uniform(us_izq, us_izq + 16.0), (3.0, 50.0))
            us_der = noisy_us(rng, rng.uniform(us_cen, us_cen + 20.0), (3.0, 68.0))
        elif scenario == "esquina_curva_der":
            d_c = rng.uniform(3.0, 20.0)
            las_der = noisy_laser(rng, d_c, (2.0, 21.5))
            las_izq = noisy_laser(rng, rng.uniform(d_c, min(32.0, d_c + 10.0)), (2.0, 33.0))
            us_der = noisy_us(rng, rng.uniform(d_c, min(33.0, d_c + 14.0)), (3.0, 34.0))
            us_cen = noisy_us(rng, rng.uniform(us_der, us_der + 16.0), (3.0, 50.0))
            us_izq = noisy_us(rng, rng.uniform(us_cen, us_cen + 20.0), (3.0, 68.0))
        elif scenario == "muro_especular_izq":
            d_m = rng.uniform(8.0, 22.0)
            las_izq = noisy_laser(rng, d_m, (7.0, 24.0))
            las_der = noisy_laser(rng, rng.uniform(25.0, 42.0), (23.0, 43.0))
            us_izq = noisy_us(rng, rng.uniform(185.0, 250.0), (180.0, 250.0))
            us_cen = noisy_us(rng, d_m + rng.uniform(0.0, 6.0), (7.0, 30.0))
            us_der = noisy_us(rng, rng.uniform(25.0, 60.0), (20.0, 70.0))
        elif scenario == "muro_especular_der":
            d_m = rng.uniform(8.0, 22.0)
            las_der = noisy_laser(rng, d_m, (7.0, 24.0))
            las_izq = noisy_laser(rng, rng.uniform(25.0, 42.0), (23.0, 43.0))
            us_der = noisy_us(rng, rng.uniform(185.0, 250.0), (180.0, 250.0))
            us_cen = noisy_us(rng, d_m + rng.uniform(0.0, 6.0), (7.0, 30.0))
            us_izq = noisy_us(rng, rng.uniform(25.0, 60.0), (20.0, 70.0))
        elif scenario == "laser_medio_izq":
            # Banda intermedia >= 23.0 cm con US abierto (NO es canica)
            las_izq = noisy_laser(rng, rng.uniform(23.5, 34.5), (23.0, 35.5))
            las_der = noisy_laser(rng, rng.uniform(24.0, 42.5), (23.2, 43.0))
            us_izq = sample_bay_us(rng, 25.0, 250.0)
            us_cen = sample_bay_us(rng, 25.0, 250.0)
            us_der = sample_bay_us(rng, 25.0, 250.0)
        elif scenario == "laser_medio_der":
            las_der = noisy_laser(rng, rng.uniform(23.5, 34.5), (23.0, 35.5))
            las_izq = noisy_laser(rng, rng.uniform(24.0, 42.5), (23.2, 43.0))
            us_izq = sample_bay_us(rng, 25.0, 250.0)
            us_cen = sample_bay_us(rng, 25.0, 250.0)
            us_der = sample_bay_us(rng, 25.0, 250.0)
        elif scenario == "laser_medio_ambos":
            d_mid = rng.uniform(23.5, 34.5)
            las_izq = noisy_laser(rng, d_mid, (23.0, 35.5))
            las_der = noisy_laser(rng, d_mid + rng.uniform(-3.0, 3.0), (23.0, 36.0))
            us_izq = sample_bay_us(rng, 25.0, 250.0)
            us_cen = sample_bay_us(rng, 25.0, 250.0)
            us_der = sample_bay_us(rng, 25.0, 250.0)
        elif scenario == "contacto_inmediato":
            # Roce directo en pared (laser 2.0-3.2 cm) con US < 45 cm
            d_c = rng.uniform(2.0, 3.0)
            side = rng.choice(("izq", "der"))
            if side == "izq":
                las_izq = noisy_laser(rng, d_c, (2.0, 3.2))
                us_izq = noisy_us(rng, rng.uniform(2.0, 42.0), (2.0, 44.0))
            else:
                las_der = noisy_laser(rng, d_c, (2.0, 3.2))
                us_der = noisy_us(rng, rng.uniform(2.0, 42.0), (2.0, 44.0))

        rows.append((us_izq, us_cen, us_der, las_izq, las_der, servo, "NINGUNA"))

    # 2. CANICAS (LOBITO_CENTRO, LOBITO_IZQ, LOBITO_DER)
    # Proyeccion optica cruzada real del brazo de 2.0 cm en [-45.0, +18.0] deg:
    # Un laser izquierdo en angulo negativo apunta hacia el centro/derecha (y = +8.3 + d*sin(theta)).
    for target_label in ("LOBITO_IZQ", "LOBITO_CENTRO", "LOBITO_DER"):
        generated = 0
        while generated < SAMPLES_PER_CLASS:
            d_canica = rng.uniform(3.8, 20.2)
            servo = round(rng.uniform(-45.0, 18.0), 1)
            sin_s = math.sin(math.radians(servo))

            if target_label == "LOBITO_CENTRO" and rng.random() < 0.65:
                # Deteccion binocular centrada (ambos laseres en rango de canica)
                if servo > 12.0:
                    continue
                las_izq = noisy_laser(rng, d_canica, (3.6, 20.6))
                target_der = max(3.6, min(20.6, d_canica + rng.uniform(-4.0, 4.0)))
                las_der = noisy_laser(rng, target_der, (3.6, 20.6))
                us_cen = sample_bay_us(rng, max(40.0, max(las_izq, las_der) + 18.0), 250.0)
                us_izq = noisy_us(rng, max(38.0, us_cen + rng.uniform(-8.0, 8.0)), (38.0, 250.0))
                us_der = noisy_us(rng, max(38.0, us_cen + rng.uniform(-8.0, 8.0)), (38.0, 250.0))
                rows.append((us_izq, us_cen, us_der, las_izq, las_der, servo, "LOBITO_CENTRO"))
                generated += 1
                continue

            # Deteccion monocular con geometria cruzada real de y_hit
            use_left = rng.random() < 0.5
            if use_left:
                y_hit = 8.3 + d_canica * sin_s
                if y_hit > 2.8:
                    true_lbl = "LOBITO_IZQ"
                elif y_hit < -2.8:
                    true_lbl = "LOBITO_DER"
                else:
                    true_lbl = "LOBITO_CENTRO"
                if true_lbl != target_label:
                    continue
                las_izq = noisy_laser(rng, d_canica, (3.6, 20.6))
                las_der = noisy_laser(rng, rng.uniform(27.0, 43.0), (25.5, 43.0))
                us_ref = sample_bay_us(rng, max(40.0, d_canica + 18.0), 250.0)
                us_izq = us_ref
                us_cen = noisy_us(rng, max(38.0, us_ref + rng.uniform(-7.0, 9.0)), (38.0, 250.0))
                us_der = noisy_us(rng, max(38.0, us_cen + rng.uniform(-7.0, 9.0)), (38.0, 250.0))
            else:
                y_hit = -8.3 - d_canica * sin_s
                if y_hit < -2.8:
                    true_lbl = "LOBITO_DER"
                elif y_hit > 2.8:
                    true_lbl = "LOBITO_IZQ"
                else:
                    true_lbl = "LOBITO_CENTRO"
                if true_lbl != target_label:
                    continue
                las_der = noisy_laser(rng, d_canica, (3.6, 20.6))
                las_izq = noisy_laser(rng, rng.uniform(27.0, 43.0), (25.5, 43.0))
                us_ref = sample_bay_us(rng, max(40.0, d_canica + 18.0), 250.0)
                us_der = us_ref
                us_cen = noisy_us(rng, max(38.0, us_ref + rng.uniform(-7.0, 9.0)), (38.0, 250.0))
                us_izq = noisy_us(rng, max(38.0, us_cen + rng.uniform(-7.0, 9.0)), (38.0, 250.0))

            rows.append((us_izq, us_cen, us_der, las_izq, las_der, servo, target_label))
            generated += 1

    rng.shuffle(rows)
    return rows


def self_check(us_rows, marble_rows):
    """Verifica conteos, bandas y la regla laser-vs-ultrasonido de cada etiqueta."""
    assert len(us_rows) == 3 * SAMPLES_PER_CLASS
    assert len(marble_rows) == 4 * SAMPLES_PER_CLASS

    for label, band in US_CLASSES:
        values = [d for d, lab in us_rows if lab == label]
        assert len(values) == SAMPLES_PER_CLASS
        assert all(band[0] <= d <= band[1] for d in values)
        assert all(abs(d * 4 - round(d * 4)) < 1e-9 for d in values), "US no cuantizado"

    counts = {}
    for us_izq, us_cen, us_der, las_izq, las_der, servo, label in marble_rows:
        counts[label] = counts.get(label, 0) + 1
        assert SERVO_MIN_DEG <= servo <= SERVO_MAX_DEG
        if label in ("LOBITO_IZQ", "LOBITO_DER", "LOBITO_CENTRO"):
            d_min = min(las_izq, las_der)
            assert 3.4 <= d_min <= 21.5
            assert -45.0 <= servo <= 18.0
            assert min(us_izq, us_cen, us_der) >= 36.0
            assert max(us_izq, us_cen, us_der) >= d_min + 16.0
        if label == "LOBITO_CENTRO" and las_izq <= 21.5 and las_der <= 21.5:
            assert abs(las_izq - las_der) <= 5.5

    assert set(counts) == {"NINGUNA", "LOBITO_IZQ", "LOBITO_CENTRO", "LOBITO_DER"}
    assert all(n == SAMPLES_PER_CLASS for n in counts.values())


def write_csv(path, header, rows, fmt):
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(fmt(row) for row in rows)


def main():
    rng = random.Random(SEED)
    us_rows = build_ultrasonic_rows(rng)
    marble_rows = build_marble_rows(rng)
    self_check(us_rows, marble_rows)

    os.makedirs(DATASETS_DIR, exist_ok=True)
    us_path = os.path.join(DATASETS_DIR, "dataset_ultrasonico.csv")
    marble_path = os.path.join(DATASETS_DIR, "dataset_canicas.csv")

    write_csv(
        us_path,
        ("dist_us", "class_label"),
        us_rows,
        lambda row: (f"{row[0]:.2f}", row[1]),
    )
    write_csv(
        marble_path,
        ("d_us_izq", "d_us_cen", "d_us_der",
         "d_laser_izq", "d_laser_der", "angulo_servo", "class_label"),
        marble_rows,
        lambda row: (f"{row[0]:.2f}", f"{row[1]:.2f}", f"{row[2]:.2f}",
                     f"{row[3]:.1f}", f"{row[4]:.1f}", f"{row[5]:.1f}", row[6]),
    )

    print(f"OK: {us_path} ({len(us_rows)} filas)")
    print(f"OK: {marble_path} ({len(marble_rows)} filas)")


if __name__ == "__main__":
    main()
