#!/usr/bin/env python3
"""
Dataset Collector & Supervisor Realista - Orquitas (Keiko)
Genera datasets física y acústicamente realistas en la Bahía Semielíptica de Webots 3D.

Características clave:
1. Soporta la configuración principal de 6 sensores frontales (trisector_front_6:
   us_low_left, us_low_center, us_low_right, us_high_left, us_high_center, us_high_right)
   además de configuraciones de 4, 6-lateral, 8 y 10 sensores.
2. Aplica el modelo acústico real HC-SR04 (UltrasonicPhysicsModel):
   - Las canicas (16 mm) solo devuelven eco en el plano bajo hasta ~32 cm y dentro de ±9.5°.
   - Ruido térmico proporcional a la distancia y cuantización real.
3. Distinción física real entre ESPACIO_LIBRE y SALIDA sin mapa:
   - Dentro de la semielipse (1.80m x 1.20m), las lecturas internas de pared a pared son <= 175 cm.
   - Únicamente al apuntar a través del hueco de salida de 30 cm los haces frontales (bajo y alto)
     miden > 185-250 cm hacia el exterior de la bahía.
4. Cuando se recolectan los 6 sensores frontales, exporta simultáneamente 3 variantes de dataset
   a partir de las mismas mediciones físicas para benchmarking iterativo en Neuroph 2.98:
   - orquitas_dataset_6_sensores.csv (5 clases canónicas: ESPACIO_LIBRE, LOBITO, MURO, OBSTACULO, SALIDA)
   - orquitas_dataset_6_sensores_7clases.csv (7 clases: LOBITO_IZQ/CENTRO/DER)
   - orquitas_dataset_6_sensores_9clases.csv (9 clases: LOBITO_IZQ/CENTRO/DER + OBSTACULO_IZQ/CENTRO/DER)
"""

import sys
import os
import math
import random
import csv

try:
    from controller import Supervisor
except ImportError:
    Supervisor = None

COMMON_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "common"))
if COMMON_DIR not in sys.path:
    sys.path.insert(0, COMMON_DIR)

from ultrasonic_physics import UltrasonicPhysicsModel, SENSOR_MOUNT_GEOM


def clamp(val, min_v, max_v):
    return max(min_v, min(max_v, val))


EXIT_GEOM = {
    "north": ((0.0, 0.60), math.pi / 2.0),
    "east": ((0.60, 0.0), 0.0),
    "south": ((0.0, -0.60), -math.pi / 2.0),
    "west": ((-0.60, 0.0), math.pi),
}


def detect_exit(supervisor):
    node = supervisor.getFromDef("EXIT_PLATFORM")
    if node is not None:
        try:
            pos = node.getPosition()
            if pos is not None and len(pos) >= 2:
                ex, ey = pos[0], pos[1]
                if ey > 0.45:
                    return "north"
                if ex > 0.45:
                    return "east"
                if ey < -0.45:
                    return "south"
                if ex < -0.45:
                    return "west"
        except Exception:
            pass
    wall = os.environ.get("EXIT_WALL", "").strip().lower()
    return wall if wall in EXIT_GEOM else "north"


def is_inside_semi_ellipse(x, y, margin=0.15):
    if y < -0.60 + margin:
        return False
    a = max(0.2, 1.20 - margin)
    b = max(0.2, 0.90 - margin)
    dy = y - (-0.60)
    if dy < 0:
        return False
    return (x / b) ** 2 + (dy / a) ** 2 <= 1.0


def generate_pair_dataset(rows_9, out_dir):
    """Genera el dataset por par coaxial (dist_low, dist_high -> 4 clases con umbral cerrado)."""
    # -------------------------------------------------------------------------
    # DATASET POR PAR COAXIAL (dist_low, dist_high -> 4 CLASES CON UMBRAL CERRADO)
    # Clases: BLOQUEO (2..16 cm), LOBITO (low<=30 y high-low>=16), ESPACIO_LIBRE (17.5..180 cm), SALIDA (>=185 cm)
    # -------------------------------------------------------------------------
    pair_rows = []
    pair_counts = {"BLOQUEO": 0, "LOBITO": 0, "ESPACIO_LIBRE": 0, "SALIDA": 0}
    target_per_pair_class = 600
    # Reservar la mitad del cupo a muestras uniformes para cubrir los bordes 16/17.5 y 180/185 cm.
    real_samples_per_class = target_per_pair_class // 2

    # 1. Extraer pares reales medidos en Webots de las filas recolectadas
    for r in rows_9:
        pairs = [
            (r.get("dist_low_left"), r.get("dist_high_left")),
            (r.get("dist_low_center"), r.get("dist_high_center")),
            (r.get("dist_low_right"), r.get("dist_high_right")),
        ]
        for dl, dh in pairs:
            if dl is None or dh is None:
                continue
            delta = dh - dl
            if dl <= 30.0 and delta >= 16.0 and dh >= 30.0:
                p_lbl = "LOBITO"
            elif dl <= 16.0 and dh <= 16.0 and abs(delta) <= 8.0:
                p_lbl = "BLOQUEO"
            elif dl >= 185.0 and dh >= 185.0 and abs(delta) <= 18.0:
                p_lbl = "SALIDA"
            elif 17.5 <= dl <= 180.0 and 17.5 <= dh <= 180.0 and abs(delta) <= 12.0:
                p_lbl = "ESPACIO_LIBRE"
            else:
                continue
            if pair_counts[p_lbl] < real_samples_per_class:
                pair_rows.append({"dist_low": round(dl, 2), "dist_high": round(dh, 2), "class_label": p_lbl})
                pair_counts[p_lbl] += 1

    # 2. Completar cobertura uniforme en las 4 regiones físicas para evitar puntos ciegos en [2.0, 250.0] cm
    rng_pair = random.Random(101)
    # Anclas exactas de escala [2.0, 250.0] cm (solo si queda cupo en su clase)
    if pair_counts["BLOQUEO"] < target_per_pair_class:
        pair_rows.append({"dist_low": 2.0, "dist_high": 2.0, "class_label": "BLOQUEO"})
        pair_counts["BLOQUEO"] += 1
    if pair_counts["SALIDA"] < target_per_pair_class:
        pair_rows.append({"dist_low": 250.0, "dist_high": 250.0, "class_label": "SALIDA"})
        pair_counts["SALIDA"] += 1

    while pair_counts["BLOQUEO"] < target_per_pair_class:
        dl = rng_pair.uniform(2.0, 16.0)
        dh = max(2.0, min(16.0, dl + rng_pair.uniform(-5.0, 7.0)))
        dl = round(dl / 0.25) * 0.25
        dh = round(dh / 0.25) * 0.25
        pair_rows.append({"dist_low": round(dl, 2), "dist_high": round(dh, 2), "class_label": "BLOQUEO"})
        pair_counts["BLOQUEO"] += 1

    while pair_counts["LOBITO"] < target_per_pair_class:
        dl = round(rng_pair.uniform(2.25, 30.0) / 0.25) * 0.25
        min_dh = max(30.0, dl + 16.0)
        dh = round(rng_pair.uniform(min_dh, 250.0) / 0.25) * 0.25
        pair_rows.append({"dist_low": round(dl, 2), "dist_high": round(dh, 2), "class_label": "LOBITO"})
        pair_counts["LOBITO"] += 1

    while pair_counts["ESPACIO_LIBRE"] < target_per_pair_class:
        dl = rng_pair.uniform(17.5, 180.0)
        dh = max(17.5, min(180.0, dl + rng_pair.uniform(-8.0, 11.5)))
        dl = round(dl / 0.25) * 0.25
        dh = round(dh / 0.25) * 0.25
        pair_rows.append({"dist_low": round(dl, 2), "dist_high": round(dh, 2), "class_label": "ESPACIO_LIBRE"})
        pair_counts["ESPACIO_LIBRE"] += 1

    while pair_counts["SALIDA"] < target_per_pair_class:
        base = rng_pair.uniform(186.0, 250.0)
        dl = round(max(185.5, min(250.0, base + rng_pair.gauss(0.0, 1.8))) / 0.25) * 0.25
        dh = round(max(185.5, min(250.0, base + rng_pair.gauss(0.0, 1.8))) / 0.25) * 0.25
        pair_rows.append({"dist_low": round(dl, 2), "dist_high": round(dh, 2), "class_label": "SALIDA"})
        pair_counts["SALIDA"] += 1

    rng_pair.shuffle(pair_rows)
    pair_csv_path = os.path.join(out_dir, "orquitas_dataset_par_coaxial.csv")
    with open(pair_csv_path, mode="w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["dist_low", "dist_high", "class_label"])
        writer.writeheader()
        writer.writerows(pair_rows)
    print(f"  [OK] Guardado {pair_csv_path} ({len(pair_rows)} filas, {pair_counts})")


def load_rows_from_csv(path):
    rows = []
    with open(path, mode="r", newline="") as f:
        for r in csv.DictReader(f):
            try:
                rows.append({k: float(v) for k, v in r.items() if k != "class_label"})
            except (TypeError, ValueError):
                continue
    return rows


def generate_pair_dataset_standalone():
    out_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "data", "datasets"))
    source = os.path.join(out_dir, "orquitas_dataset_6_sensores_9clases.csv")
    if not os.path.exists(source):
        print(f"[ERROR] No se encontró el dataset base: {source}", file=sys.stderr)
        sys.exit(1)
    rows = load_rows_from_csv(source)
    print(f"  [MODO STANDALONE] {len(rows)} filas cargadas desde {source}")
    generate_pair_dataset(rows, out_dir)


def main():
    supervisor = Supervisor()
    time_step = int(supervisor.getBasicTimeStep())
    if time_step <= 0:
        time_step = 32

    has_center_dev = supervisor.getDevice("us_low_center") is not None
    has_side_dev = supervisor.getDevice("us_side_left") is not None
    has_rear_dev = supervisor.getDevice("us_rear_left") is not None

    # Determinar configuración activa según dispositivos presentes en el mundo o argumento
    sensor_count = 6 if has_center_dev and not has_side_dev else (10 if has_center_dev and has_rear_dev else (8 if has_rear_dev else 4))
    if len(sys.argv) > 1:
        try:
            sensor_count = int(sys.argv[1])
        except ValueError:
            pass
    elif "SENSOR_CONFIG" in os.environ:
        try:
            sensor_count = int(os.environ["SENSOR_CONFIG"])
        except ValueError:
            pass

    if sensor_count == 10:
        sensor_names = ["us_low_left", "us_low_center", "us_low_right", "us_high_left", "us_high_center", "us_high_right", "us_side_left", "us_side_right", "us_rear_left", "us_rear_right"]
        csv_filename = "orquitas_dataset_10_sensores.csv"
    elif sensor_count == 6 and has_center_dev:
        # Configuración principal: 6 sensores frontales (3 bajos + 3 altos)
        sensor_names = ["us_low_left", "us_low_center", "us_low_right", "us_high_left", "us_high_center", "us_high_right"]
        csv_filename = "orquitas_dataset_6_sensores.csv"
    elif sensor_count == 6:
        sensor_names = ["us_low_left", "us_low_right", "us_high_left", "us_high_right", "us_side_left", "us_side_right"]
        csv_filename = "orquitas_dataset_6_side_sensores.csv"
    elif sensor_count == 8:
        sensor_names = ["us_low_left", "us_low_right", "us_high_left", "us_high_right", "us_side_left", "us_side_right", "us_rear_left", "us_rear_right"]
        csv_filename = "orquitas_dataset_8_sensores.csv"
    else:
        sensor_count = 4
        sensor_names = ["us_low_left", "us_low_right", "us_high_left", "us_high_right"]
        csv_filename = "orquitas_dataset_4_sensores.csv"

    print("==========================================================")
    print(f"  ORQUITAS (KEIKO) - GENERADOR REALISTA DE DATASETS ({sensor_count} SENSORES)")
    print(f"  Canales: {sensor_names}")
    print("==========================================================")

    sensors = {}
    for name in sensor_names:
        s = supervisor.getDevice(name)
        if s is not None:
            s.enable(time_step)
            sensors[name] = s
        else:
            print(f"[ERROR] No se encontró el sensor: {name}", file=sys.stderr)
            sys.exit(1)

    robot_node = supervisor.getFromDef("ORQUITA")
    if not robot_node:
        print("[ERROR] Nodo DEF ORQUITA no encontrado.", file=sys.stderr)
        sys.exit(1)

    trans_field = robot_node.getField("translation")
    rot_field = robot_node.getField("rotation")

    lobitos = [
        (0.24, 0.28),
        (-0.52, -0.05),
        (0.50, -0.32),
    ]
    obstaculos = [
        (-0.42, 0.16),
        (0.42, 0.08),
        (-0.28, -0.30),
    ]

    exit_wall = detect_exit(supervisor)
    salida_pos, salida_yaw = EXIT_GEOM[exit_wall]
    us_physics = UltrasonicPhysicsModel(mode="realistic", seed=2026)

    out_dir = os.path.join(os.path.dirname(__file__), "..", "..", "data", "datasets")
    os.makedirs(out_dir, exist_ok=True)

    # Recolectamos sub-clases detalladas y luego proyectamos a 5, 7 y 9 clases
    is_front_6 = (sensor_count == 6 and has_center_dev)
    if is_front_6:
        sub_targets = [
            ("LOBITO_IZQ", 220),
            ("LOBITO_CENTRO", 220),
            ("LOBITO_DER", 220),
            ("OBSTACULO_IZQ", 220),
            ("OBSTACULO_CENTRO", 220),
            ("OBSTACULO_DER", 220),
            ("MURO", 450),
            ("SALIDA", 450),
            ("ESPACIO_LIBRE", 450),
        ]
    else:
        sub_targets = [
            ("LOBITO", 400),
            ("OBSTACULO", 400),
            ("MURO", 400),
            ("SALIDA", 400),
            ("ESPACIO_LIBRE", 400),
        ]

    rows_5 = []
    rows_7 = []
    rows_9 = []

    sector_angles = {
        "IZQ": +0.3840,     # +22°
        "CENTRO": 0.0000,   #   0°
        "DER": -0.3840,     # -22°
    }
    sector_mount = {
        "IZQ": (0.128, +0.052),
        "CENTRO": (0.134, 0.000),
        "DER": (0.128, -0.052),
    }

    for sub_class, target_n in sub_targets:
        print(f"-> Recolectando {target_n} muestras para: {sub_class}...")
        count = 0
        attempts = 0
        max_attempts = target_n * 80

        while count < target_n and attempts < max_attempts:
            attempts += 1

            if sub_class.startswith("LOBITO"):
                lob = random.choice(lobitos)
                sector = sub_class.split("_")[1] if "_" in sub_class else random.choice(["IZQ", "CENTRO", "DER"])
                s_ang = sector_angles[sector]
                sx_loc, sy_loc = sector_mount[sector]
                # Distancia física realista para detectar canica de 16 mm: 6 cm a 29 cm desde el sensor
                sensor_dist = random.uniform(0.06, 0.29)
                base_angle = random.uniform(-math.pi, math.pi)
                heading = base_angle - s_ang + random.uniform(-0.07, 0.07)
                cos_h, sin_h = math.cos(heading), math.sin(heading)
                beam_dir_x = math.cos(heading + s_ang)
                beam_dir_y = math.sin(heading + s_ang)
                # Posicionar robot para que el sensor del sector elegido quede a sensor_dist de la canica
                rx = lob[0] - beam_dir_x * sensor_dist - (sx_loc * cos_h - sy_loc * sin_h)
                ry = lob[1] - beam_dir_y * sensor_dist - (sx_loc * sin_h + sy_loc * cos_h)
                if not is_inside_semi_ellipse(rx, ry, margin=0.14):
                    continue

            elif sub_class.startswith("OBSTACULO"):
                obs = random.choice(obstaculos)
                sector = sub_class.split("_")[1] if "_" in sub_class else random.choice(["IZQ", "CENTRO", "DER"])
                s_ang = sector_angles[sector]
                sx_loc, sy_loc = sector_mount[sector]
                sensor_dist = random.uniform(0.10, 0.44)
                base_angle = random.uniform(-math.pi, math.pi)
                heading = base_angle - s_ang + random.uniform(-0.08, 0.08)
                cos_h, sin_h = math.cos(heading), math.sin(heading)
                beam_dir_x = math.cos(heading + s_ang)
                beam_dir_y = math.sin(heading + s_ang)
                rx = obs[0] - beam_dir_x * sensor_dist - (sx_loc * cos_h - sy_loc * sin_h)
                ry = obs[1] - beam_dir_y * sensor_dist - (sx_loc * sin_h + sy_loc * cos_h)
                if not is_inside_semi_ellipse(rx, ry, margin=0.15):
                    continue

            elif sub_class == "MURO":
                wall = random.choice(["south", "arc_left", "arc_right"])
                dist = random.uniform(0.14, 0.46)
                heading_jitter = random.uniform(-0.22, 0.22)
                if wall == "south":
                    rx = random.uniform(-0.62, 0.62)
                    ry = -0.60 + dist
                    heading = -math.pi / 2.0 + heading_jitter
                elif wall == "arc_left":
                    theta = random.uniform(math.pi * 0.60, math.pi * 0.90)
                    wx = 0.90 * math.cos(theta)
                    wy = -0.60 + 1.20 * math.sin(theta)
                    nx, ny = math.cos(theta) / 0.90, math.sin(theta) / 1.20
                    nlen = math.hypot(nx, ny)
                    nx, ny = nx / nlen, ny / nlen
                    rx = wx - nx * dist
                    ry = wy - ny * dist
                    heading = math.atan2(ny, nx) + heading_jitter
                else:
                    theta = random.uniform(math.pi * 0.10, math.pi * 0.40)
                    wx = 0.90 * math.cos(theta)
                    wy = -0.60 + 1.20 * math.sin(theta)
                    nx, ny = math.cos(theta) / 0.90, math.sin(theta) / 1.20
                    nlen = math.hypot(nx, ny)
                    nx, ny = nx / nlen, ny / nlen
                    rx = wx - nx * dist
                    ry = wy - ny * dist
                    heading = math.atan2(ny, nx) + heading_jitter

            elif sub_class == "SALIDA":
                dist_to_exit = random.uniform(0.18, 0.90)
                lateral_off = random.uniform(-0.11, 0.11)
                dir_x, dir_y = math.cos(salida_yaw), math.sin(salida_yaw)
                rx = salida_pos[0] - dir_x * dist_to_exit - dir_y * lateral_off
                ry = salida_pos[1] - dir_y * dist_to_exit + dir_x * lateral_off
                if not is_inside_semi_ellipse(rx, ry, margin=0.14):
                    continue
                # Apuntar hacia el hueco de 30 cm de modo que el eje central del robot enfile la salida
                angle_to_gap = math.atan2(salida_pos[1] - ry, salida_pos[0] - rx)
                heading = angle_to_gap + random.uniform(-0.20, 0.20)

            else:  # ESPACIO_LIBRE
                rx = random.uniform(-0.45, 0.45)
                ry = random.uniform(-0.38, 0.18)
                heading = random.uniform(-math.pi, math.pi)
                if not is_inside_semi_ellipse(rx, ry, margin=0.20):
                    continue

            trans_field.setSFVec3f([rx, ry, 0.001])
            rot_field.setSFRotation([0, 0, 1, heading])
            supervisor.simulationResetPhysics()
            supervisor.step(time_step)

            us_physics.reset_history()
            raw = {name: sensors[name].getValue() for name in sensor_names}
            realistic = us_physics.apply(raw, robot_pose=(rx, ry, heading), marble_positions=lobitos)

            d_ll = realistic["us_low_left"]
            d_lr = realistic["us_low_right"]
            d_hl = realistic["us_high_left"]
            d_hr = realistic["us_high_right"]
            d_lc = realistic.get("us_low_center", min(d_ll, d_lr))
            d_hc = realistic.get("us_high_center", min(d_hl, d_hr))

            delta_l = d_hl - d_ll
            delta_c = d_hc - d_lc
            delta_r = d_hr - d_lr
            min_low = min(d_ll, d_lc, d_lr)
            min_high = min(d_hl, d_hc, d_hr)
            max_both = max(min(d_ll, d_hl), min(d_lc, d_hc), min(d_lr, d_hr))

            # Validar que la muestra física corresponda limpiamente al fenómeno buscado
            if sub_class.startswith("LOBITO"):
                # Debe haber eco válido de canica (<= 32 cm) y contraste vertical claro (> 14 cm)
                if sub_class == "LOBITO_IZQ":
                    if not (d_ll <= 32.0 and delta_l >= 14.0 and d_ll < d_lc - 3.0 and d_ll < d_lr - 3.0):
                        continue
                elif sub_class == "LOBITO_CENTRO":
                    if not (d_lc <= 32.0 and delta_c >= 14.0 and d_lc <= d_ll and d_lc <= d_lr):
                        continue
                elif sub_class == "LOBITO_DER":
                    if not (d_lr <= 32.0 and delta_r >= 14.0 and d_lr < d_lc - 3.0 and d_lr < d_ll - 3.0):
                        continue
                else:
                    if not (min_low <= 32.0 and max(delta_l, delta_c, delta_r) >= 14.0):
                        continue

            elif sub_class.startswith("OBSTACULO"):
                # El borrador mide 12 cm de alto (bloquea bajo y alto del sector, |delta| < 10 cm),
                # pero es angosto (6-14 cm), dejando al menos otro sector frontal mucho más libre (> +18 cm)
                if max(delta_l, delta_c, delta_r) > 12.0:
                    continue
                s_l = 0.5 * (d_ll + d_hl)
                s_c = 0.5 * (d_lc + d_hc)
                s_r = 0.5 * (d_lr + d_hr)
                if sub_class == "OBSTACULO_IZQ":
                    if not (s_l <= 46.0 and abs(delta_l) <= 10.0 and s_c > s_l + 15.0 and s_r > s_l + 18.0):
                        continue
                elif sub_class == "OBSTACULO_CENTRO":
                    if not (s_c <= 46.0 and abs(delta_c) <= 10.0 and s_l > s_c + 15.0 and s_r > s_c + 15.0):
                        continue
                elif sub_class == "OBSTACULO_DER":
                    if not (s_r <= 46.0 and abs(delta_r) <= 10.0 and s_c > s_r + 15.0 and s_l > s_r + 18.0):
                        continue
                else:
                    if not (min(s_l, s_c, s_r) <= 46.0 and (max(s_l, s_c, s_r) - min(s_l, s_c, s_r)) >= 18.0):
                        continue

            elif sub_class == "MURO":
                # Muro continuo: al menos 2 sectores adyacentes ven cerca (<= 48 cm), sin delta vertical
                if max(abs(delta_l), abs(delta_c), abs(delta_r)) > 10.0:
                    continue
                s_l, s_c, s_r = 0.5 * (d_ll + d_hl), 0.5 * (d_lc + d_hc), 0.5 * (d_lr + d_hr)
                if not (s_c <= 48.0 and (abs(s_l - s_c) <= 14.0 or abs(s_r - s_c) <= 14.0) and max(s_l, s_c, s_r) <= 68.0):
                    continue

            elif sub_class == "SALIDA":
                # Hueco de salida de 30 cm: el par central frontal atraviesa el hueco (> 185 cm)
                # y los sensores laterales están libres de colisión con los bordes del hueco (>= 45 cm)
                if min(d_lc, d_hc) < 185.0 or min_low < 45.0 or min_high < 45.0:
                    continue

            elif sub_class == "ESPACIO_LIBRE":
                # Interior despejado (> 52 cm en todos los sensores frontales, pero <= 178 cm porque está dentro de la bahía)
                if min_low < 52.0 or min_high < 52.0 or max_both >= 180.0:
                    continue

            base_row = {f"dist_{name.replace('us_', '')}": round(realistic[name], 2) for name in sensor_names}

            # Etiqueta para 5 clases
            if sub_class.startswith("LOBITO"):
                lbl5 = "LOBITO"
                lbl7 = sub_class
                lbl9 = sub_class
            elif sub_class.startswith("OBSTACULO"):
                lbl5 = "OBSTACULO"
                lbl7 = "OBSTACULO"
                lbl9 = sub_class
            else:
                lbl5 = sub_class
                lbl7 = sub_class
                lbl9 = sub_class

            r5 = dict(base_row); r5["class_label"] = lbl5; rows_5.append(r5)
            r7 = dict(base_row); r7["class_label"] = lbl7; rows_7.append(r7)
            r9 = dict(base_row); r9["class_label"] = lbl9; rows_9.append(r9)
            count += 1

        print(f"   -> Obtenidas {count}/{target_n} muestras para {sub_class} ({attempts} intentos).")

    def save_csv(filename, rows):
        rng = random.Random(42)
        shuffled = list(rows)
        rng.shuffle(shuffled)
        path = os.path.join(out_dir, filename)
        fieldnames = [f"dist_{name.replace('us_', '')}" for name in sensor_names] + ["class_label"]
        with open(path, mode="w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(shuffled)
        print(f"  [OK] Guardado {path} ({len(shuffled)} filas)")

    save_csv(csv_filename, rows_5)
    if is_front_6:
        save_csv("orquitas_dataset_6_sensores_7clases.csv", rows_7)
        save_csv("orquitas_dataset_6_sensores_9clases.csv", rows_9)

    generate_pair_dataset(rows_9, out_dir)

    supervisor.simulationQuit(0)


if __name__ == "__main__":
    standalone = "--standalone" in sys.argv or Supervisor is None
    if not standalone:
        try:
            main()
        except Exception as exc:
            print(f"[WARN] Webots no disponible ({exc}); usando modo standalone.", file=sys.stderr)
            standalone = True
    if standalone:
        generate_pair_dataset_standalone()
