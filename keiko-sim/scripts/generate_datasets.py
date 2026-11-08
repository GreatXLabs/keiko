#!/usr/bin/env python3
"""
Generador de datasets sinteticos para las 2 redes neuronales de Orquitas (Keiko).

Genera, sin dependencias externas (solo stdlib), dos CSV balanceados y fisicamente
consistentes con el modelo acustico/optico del proyecto:

1. dataset_ultrasonico.csv  -> dist_us, class_label
   Clases: BLOQUEO / ESPACIO_LIBRE / SALIDA
2. dataset_canicas.csv      -> d_us_izq, d_us_cen, d_us_der,
                               d_laser_izq, d_laser_der, angulo_servo, class_label
   Clases: NINGUNA / LOBITO_IZQ / LOBITO_CENTRO / LOBITO_DER

Reglas fisicas aplicadas:
- Ultrasonido HC-SR04: cuantizacion de 0.25 cm, ruido gaussiano
  sigma = 0.35 + 0.01 * distancia (ver controllers/common/ultrasonic_physics.py).
  Las canicas (1.6 cm) quedan por debajo del haz a 5.5 cm de altura: no bloquean el US.
- Laser ToF VL53L0X: reposo sobre piso limpio 37-42 cm, canica 6.8-22.0 cm, ruido ~0.4 cm
  (ver controllers/common/laser_physics.py). Solo hay 2 lasers (izq/der) y un angulo
  de servo espejado: -45 deg hacia adentro, +60 deg hacia afuera.
- Canica: laser bajo (2.5-20.0 cm) + US correspondiente despejado (>= laser + 2.4 cm,
  mide el fondo detras de la canica). Con servo frontal la canica se ve de frente.
- Obstaculo/muro: laser en banda de canica + servo frontal sólo es NINGUNA si el US
  correspondiente confirma la misma distancia (us <= laser + 2.5 cm).

Uso:
    python3 keiko-sim/scripts/generate_datasets.py
"""

import csv
import os
import random

DATASETS_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "datasets")
)
SEED = 42
SAMPLES_PER_CLASS = 1500
US_QUANTUM_CM = 0.25

# Bandas oficiales de clasificacion (cm).
US_CLASSES = (
    ("BLOQUEO", (2.0, 16.0)),
    ("ESPACIO_LIBRE", (17.0, 165.0)),
    ("SALIDA", (180.0, 250.0)),
)
US_FREE = (20.0, 250.0)          # espacio despejado (>18 cm)
US_MARBLE_FREE = (20.0, 250.0)   # espacio despejado detrás de canica (el US a 5.5cm pasa por encima de la canica de 1.6cm y mide fondo de pista >= d_canica + 8cm)
LASER_FLOOR = (35.0, 43.0)       # reposo sobre piso limpio (media 38.5 cm, con ruido 35-43 cm)
MARBLE_RANGE = (2.5, 20.0)       # distancia fisica de deteccion util de canica para embocado
LASER_MARBLE = (2.0, 23.0)       # canica detectable en cono optico ToF con ruido
LASER_OBSTACLE = (2.0, 30.0)     # obstaculo o muro
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


def confirm_wall_reading(rng, laser, us):
    """En NINGUNA, un laser en banda de canica exige que el US mida la misma distancia (muro/obstaculo)."""
    if not (LASER_MARBLE[0] <= laser <= LASER_MARBLE[1]):
        return us
    # Banda capada a laser + 2.2: la cuantizacion de 0.25 cm puede sumar hasta 0.25 cm y el
    # self-check exige us <= laser + 2.5 cm para confirmar contacto.
    return noisy_us(
        rng,
        rng.uniform(max(2.0, laser - 1.5), min(28.0, laser + 2.0)),
        (2.0, min(28.0, laser + 2.2)),
    )


def build_ultrasonic_rows(rng):
    rows = []
    for label, band in US_CLASSES:
        if label == "ESPACIO_LIBRE":
            # 750 muestras densas en la zona de transición frontera (17-35 cm) y 750 en fondo (35-165 cm)
            for _ in range(SAMPLES_PER_CLASS // 2):
                rows.append((noisy_us(rng, rng.uniform(17.0, 35.0), band), label))
            for _ in range(SAMPLES_PER_CLASS - (SAMPLES_PER_CLASS // 2)):
                rows.append((noisy_us(rng, rng.uniform(35.0, 165.0), band), label))
        else:
            for _ in range(SAMPLES_PER_CLASS):
                rows.append((noisy_us(rng, rng.uniform(*band), band), label))
    rng.shuffle(rows)
    return rows


def build_marble_rows(rng):
    rows = []
    # 1. NINGUNA (Piso limpio, muros en todos los ángulos, obstáculos 3D, curvas, esquinas)
    for _ in range(SAMPLES_PER_CLASS):
        scenario = rng.choices(
            ("limpio", "muro_frontal", "muro_oblicuo_izq", "muro_oblicuo_der",
             "muro_oblicuo_inward_izq", "muro_oblicuo_inward_der",
             "muro_lateral_izq", "muro_lateral_der",
             "muro_exterior_izq", "muro_exterior_der",
             "obstaculo_izq", "obstaculo_der", "obstaculo_frontal",
             "esquina_curva_izq", "esquina_curva_der",
             "pared_rasante_izq", "pared_rasante_der",
             "muro_especular_izq", "muro_especular_der",
             "laser_medio_ninguna", "contacto_inmediato"),
            weights=(5, 8, 7, 7, 10, 10, 8, 8, 4, 4, 5, 5, 5, 5, 5, 4, 4, 3, 3, 4, 4),
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
            d_m = rng.uniform(3.0, 28.0)
            las_izq = noisy_laser(rng, d_m, (2.0, 30.0))
            las_der = noisy_laser(rng, d_m, (2.0, 30.0))
            us_cen = noisy_us(rng, d_m, (2.0, 32.0))
            us_izq = noisy_us(rng, d_m + rng.uniform(-1.0, 2.5), (2.0, 32.0))
            us_der = noisy_us(rng, d_m + rng.uniform(-1.0, 2.5), (2.0, 32.0))
        elif scenario == "muro_oblicuo_izq":
            d_w = rng.uniform(4.0, 25.0)
            las_izq = noisy_laser(rng, d_w, (2.5, 28.0))
            las_der = noisy_laser(rng, rng.uniform(d_w + 2.0, min(35.0, d_w + 12.0)), (d_w + 1.5, 36.0))
            # Muro oblicuo real: pendiente acústica pronunciada hacia la derecha (us_der >> us_izq)
            us_izq = noisy_us(rng, d_w + rng.uniform(-1.0, 3.5), (2.5, 32.0))
            us_cen = noisy_us(rng, d_w + rng.uniform(6.0, 16.0), (3.0, 48.0))
            us_der = noisy_us(rng, d_w + rng.uniform(16.0, 32.0), (5.0, 75.0))
        elif scenario == "muro_oblicuo_der":
            d_w = rng.uniform(4.0, 25.0)
            las_der = noisy_laser(rng, d_w, (2.5, 28.0))
            las_izq = noisy_laser(rng, rng.uniform(d_w + 2.0, min(35.0, d_w + 12.0)), (d_w + 1.5, 36.0))
            # Muro oblicuo real: pendiente acústica pronunciada hacia la izquierda (us_izq >> us_der)
            us_der = noisy_us(rng, d_w + rng.uniform(-1.0, 3.5), (2.5, 32.0))
            us_cen = noisy_us(rng, d_w + rng.uniform(6.0, 16.0), (3.0, 48.0))
            us_izq = noisy_us(rng, d_w + rng.uniform(16.0, 32.0), (5.0, 75.0))
        elif scenario == "muro_oblicuo_inward_izq":
            servo = round(rng.uniform(-40.0, 14.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(8.0, 22.0), (7.0, 24.0))
            las_der = noisy_laser(rng, rng.uniform(26.0, 42.0), (24.0, 43.0))
            us_izq = noisy_us(rng, rng.uniform(las_izq, 39.0), (12.0, 39.5))
            us_cen = noisy_us(rng, rng.uniform(us_izq, 45.0), (16.0, 50.0))
            us_der = noisy_us(rng, rng.uniform(us_cen, 75.0), (20.0, 90.0))
        elif scenario == "muro_oblicuo_inward_der":
            servo = round(rng.uniform(-40.0, 14.0), 1)
            las_der = noisy_laser(rng, rng.uniform(8.0, 22.0), (7.0, 24.0))
            las_izq = noisy_laser(rng, rng.uniform(26.0, 42.0), (24.0, 43.0))
            us_der = noisy_us(rng, rng.uniform(las_der, 39.0), (12.0, 39.5))
            us_cen = noisy_us(rng, rng.uniform(us_der, 45.0), (16.0, 50.0))
            us_izq = noisy_us(rng, rng.uniform(us_cen, 75.0), (20.0, 90.0))
        elif scenario == "muro_lateral_izq":
            servo = round(rng.uniform(-45.0, 14.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(3.5, 22.0), (3.0, 24.0))
            las_der = noisy_laser(rng, rng.uniform(25.0, 42.0), (22.0, 43.0))
            us_izq = noisy_us(rng, rng.uniform(las_izq - 1.0, 38.0), (2.5, 39.5))
            us_cen = noisy_us(rng, rng.uniform(us_izq - 1.0, 50.0), (2.5, 55.0))
            us_der = noisy_us(rng, rng.uniform(us_cen - 1.0, 90.0), (2.5, 120.0))
        elif scenario == "muro_lateral_der":
            servo = round(rng.uniform(-45.0, 14.0), 1)
            las_der = noisy_laser(rng, rng.uniform(3.5, 22.0), (3.0, 24.0))
            las_izq = noisy_laser(rng, rng.uniform(25.0, 42.0), (22.0, 43.0))
            us_der = noisy_us(rng, rng.uniform(las_der - 1.0, 38.0), (2.5, 39.5))
            us_cen = noisy_us(rng, rng.uniform(us_der - 1.0, 50.0), (2.5, 55.0))
            us_izq = noisy_us(rng, rng.uniform(us_cen - 1.0, 90.0), (2.5, 120.0))
        elif scenario == "muro_exterior_izq":
            servo = round(rng.uniform(15.0, 55.0), 1)
            las_izq = noisy_laser(rng, rng.uniform(4.0, 22.0), (3.5, 24.0))
            las_der = noisy_laser(rng, rng.uniform(30.0, 42.0), (28.0, 43.0))
            us_izq = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
            us_cen = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
            us_der = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
        elif scenario == "muro_exterior_der":
            servo = round(rng.uniform(15.0, 55.0), 1)
            las_der = noisy_laser(rng, rng.uniform(4.0, 22.0), (3.5, 24.0))
            las_izq = noisy_laser(rng, rng.uniform(30.0, 42.0), (28.0, 43.0))
            us_der = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
            us_cen = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
            us_izq = noisy_us(rng, rng.uniform(30.0, 250.0), (25.0, 250.0))
        elif scenario == "obstaculo_izq":
            d_obs = rng.uniform(3.0, 24.0)
            las_izq = noisy_laser(rng, d_obs, (2.0, 26.0))
            us_izq = noisy_us(rng, d_obs + rng.uniform(-1.0, 3.5), (2.0, 28.0))
            us_cen = noisy_us(rng, rng.uniform(d_obs, 35.0), (2.0, 38.0))
        elif scenario == "obstaculo_der":
            d_obs = rng.uniform(3.0, 24.0)
            las_der = noisy_laser(rng, d_obs, (2.0, 26.0))
            us_der = noisy_us(rng, d_obs + rng.uniform(-1.0, 3.5), (2.0, 28.0))
            us_cen = noisy_us(rng, rng.uniform(d_obs, 35.0), (2.0, 38.0))
        elif scenario == "obstaculo_frontal":
            d_obs = rng.uniform(3.0, 24.0)
            las_izq = noisy_laser(rng, d_obs, (2.0, 26.0))
            las_der = noisy_laser(rng, d_obs, (2.0, 26.0))
            us_cen = noisy_us(rng, d_obs + rng.uniform(-1.0, 3.0), (2.0, 28.0))
        elif scenario == "esquina_curva_izq":
            d_c = rng.uniform(4.0, 20.0)
            las_izq = noisy_laser(rng, d_c, (3.5, 22.0))
            las_der = noisy_laser(rng, rng.uniform(d_c, min(30.0, d_c + 8.0)), (3.5, 30.0))
            us_izq = noisy_us(rng, rng.uniform(d_c, d_c + 14.0), (3.5, 36.0))
            us_cen = noisy_us(rng, rng.uniform(us_izq, us_izq + 16.0), (3.5, 48.0))
            us_der = noisy_us(rng, rng.uniform(us_cen, us_cen + 20.0), (3.5, 65.0))
        elif scenario == "esquina_curva_der":
            d_c = rng.uniform(4.0, 20.0)
            las_der = noisy_laser(rng, d_c, (3.5, 22.0))
            las_izq = noisy_laser(rng, rng.uniform(d_c, min(30.0, d_c + 8.0)), (3.5, 30.0))
            us_der = noisy_us(rng, rng.uniform(d_c, d_c + 14.0), (3.5, 36.0))
            us_cen = noisy_us(rng, rng.uniform(us_der, us_der + 16.0), (3.5, 48.0))
            us_izq = noisy_us(rng, rng.uniform(us_cen, us_cen + 20.0), (3.5, 65.0))
        elif scenario == "pared_rasante_izq":
            d_r = rng.uniform(4.0, 18.0)
            las_izq = noisy_laser(rng, d_r, (3.5, 19.5))
            las_der = noisy_laser(rng, rng.uniform(20.0, 42.0), (18.0, 43.0))
            us_izq = noisy_us(rng, d_r + rng.uniform(-1.0, 4.0), (3.5, 24.0))
            us_cen = noisy_us(rng, rng.uniform(d_r, 35.0), (3.5, 38.0))
        elif scenario == "pared_rasante_der":
            d_r = rng.uniform(4.0, 18.0)
            las_der = noisy_laser(rng, d_r, (3.5, 19.5))
            las_izq = noisy_laser(rng, rng.uniform(20.0, 42.0), (18.0, 43.0))
            us_der = noisy_us(rng, d_r + rng.uniform(-1.0, 4.0), (3.5, 24.0))
            us_cen = noisy_us(rng, rng.uniform(d_r, 35.0), (3.5, 38.0))
        elif scenario == "muro_especular_izq":
            d_m = rng.uniform(8.0, 24.0)
            las_izq = noisy_laser(rng, d_m, (7.0, 26.0))
            las_der = noisy_laser(rng, rng.uniform(25.0, 42.0), (22.0, 43.0))
            us_izq = noisy_us(rng, rng.uniform(185.0, 250.0), (180.0, 250.0))
            us_cen = noisy_us(rng, d_m + rng.uniform(0.0, 6.0), (7.0, 32.0))
            us_der = noisy_us(rng, rng.uniform(25.0, 60.0), (20.0, 70.0))
        elif scenario == "muro_especular_der":
            d_m = rng.uniform(8.0, 24.0)
            las_der = noisy_laser(rng, d_m, (7.0, 26.0))
            las_izq = noisy_laser(rng, rng.uniform(25.0, 42.0), (22.0, 43.0))
            us_der = noisy_us(rng, rng.uniform(185.0, 250.0), (180.0, 250.0))
            us_cen = noisy_us(rng, d_m + rng.uniform(0.0, 6.0), (7.0, 32.0))
            us_izq = noisy_us(rng, rng.uniform(25.0, 60.0), (20.0, 70.0))
        elif scenario == "laser_medio_ninguna":
            d_mid = rng.uniform(24.0, 35.0)
            las_izq = noisy_laser(rng, d_mid, (23.0, 36.0))
            las_der = noisy_laser(rng, rng.uniform(24.0, 42.0), (23.0, 43.0))
        elif scenario == "contacto_inmediato":
            d_c = rng.uniform(2.0, 5.0)
            side = rng.choice(("izq", "der"))
            if side == "izq":
                las_izq = noisy_laser(rng, d_c, (2.0, 5.5))
                us_izq = noisy_us(rng, d_c + rng.uniform(-0.5, 2.0), (2.0, 7.5))
            else:
                las_der = noisy_laser(rng, d_c, (2.0, 5.5))
                us_der = noisy_us(rng, d_c + rng.uniform(-0.5, 2.0), (2.0, 7.5))

        rows.append((us_izq, us_cen, us_der, las_izq, las_der, servo, "NINGUNA"))

    # 2. CANICAS (LOBITO_CENTRO, LOBITO_IZQ, LOBITO_DER)
    for label in ("LOBITO_IZQ", "LOBITO_CENTRO", "LOBITO_DER"):
        for _ in range(SAMPLES_PER_CLASS):
            d_canica = rng.uniform(3.5, 22.0)
            d_bg_min = d_canica + 15.0
            d_bg = rng.uniform(d_bg_min, 250.0)
            if label == "LOBITO_IZQ":
                las_izq = noisy_laser(rng, d_canica, (3.5, 22.5))
                las_der = noisy_laser(rng, rng.uniform(30.0, 42.0), (28.0, 43.0))
                servo = round(rng.uniform(-35.0, 35.0), 1)
                us_izq = noisy_us(rng, d_bg, (d_bg_min, 250.0))
                us_cen = noisy_us(rng, rng.uniform(max(20.0, d_bg - 15.0), 250.0), (20.0, 250.0))
                us_der = noisy_us(rng, rng.uniform(max(20.0, d_bg - 15.0), 250.0), (20.0, 250.0))
            elif label == "LOBITO_DER":
                las_der = noisy_laser(rng, d_canica, (3.5, 22.5))
                las_izq = noisy_laser(rng, rng.uniform(30.0, 42.0), (28.0, 43.0))
                servo = round(rng.uniform(-35.0, 35.0), 1)
                us_der = noisy_us(rng, d_bg, (d_bg_min, 250.0))
                us_cen = noisy_us(rng, rng.uniform(max(20.0, d_bg - 15.0), 250.0), (20.0, 250.0))
                us_izq = noisy_us(rng, rng.uniform(max(20.0, d_bg - 15.0), 250.0), (20.0, 250.0))
            else: # LOBITO_CENTRO
                las_izq = noisy_laser(rng, d_canica, (3.5, 22.5))
                target_der = max(3.5, min(22.5, d_canica + rng.uniform(-1.5, 1.5)))
                las_der = noisy_laser(rng, target_der, (3.5, 22.5))
                servo = round(rng.uniform(-40.0, 10.0), 1)
                us_cen = noisy_us(rng, d_bg, (d_bg_min, 250.0))
                us_izq = noisy_us(rng, rng.uniform(max(20.0, d_bg - 15.0), 250.0), (20.0, 250.0))
                us_der = noisy_us(rng, rng.uniform(max(20.0, d_bg - 15.0), 250.0), (20.0, 250.0))

            rows.append((us_izq, us_cen, us_der, las_izq, las_der, servo, label))

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
        if label == "LOBITO_IZQ":
            assert us_izq >= las_izq + 12.0
            assert 3.0 <= las_izq <= 23.5
            assert las_der >= las_izq + 5.0
            assert -36.0 <= servo <= 36.0
        elif label == "LOBITO_DER":
            assert us_der >= las_der + 12.0
            assert 3.0 <= las_der <= 23.5
            assert las_izq >= las_der + 5.0
            assert -36.0 <= servo <= 36.0
        elif label == "LOBITO_CENTRO":
            assert us_cen >= min(las_izq, las_der) + 12.0
            assert 3.0 <= las_izq <= 23.5
            assert 3.0 <= las_der <= 23.5
            assert abs(las_izq - las_der) <= 4.5
            assert -41.0 <= servo <= 12.0

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
