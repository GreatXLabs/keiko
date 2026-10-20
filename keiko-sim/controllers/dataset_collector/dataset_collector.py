#!/usr/bin/env python3
"""
Dataset Collector & Supervisor - Orquitas (Keiko)
Genera datasets sintéticos masivos y balanceados a partir de simulaciones físicas en Webots 3D.
Extrae lecturas de configuraciones modulares de 4, 6, 8 o 10 sensores ultrasónicos
(planos duales o trisector) y las etiqueta con Ground Truth de alta fidelidad para
entrenamiento directo en Neuroph 2.98.
"""

import sys
import os
import math
import random
import csv
from controller import Supervisor

def clamp(val, min_v, max_v):
    return max(min_v, min(max_v, val))

# Geometría canónica de salida por pared: (posición del estrecho, yaw de egreso)
EXIT_GEOM = {
    "north": ((0.0, 0.60), math.pi / 2.0),
    "east": ((0.60, 0.0), 0.0),
    "south": ((0.0, -0.60), -math.pi / 2.0),
    "west": ((-0.60, 0.0), math.pi),
}

def detect_exit(supervisor):
    """Detecta la pared de salida dinámicamente (nodo EXIT_PLATFORM -> env EXIT_WALL -> norte)."""
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

def main():
    supervisor = Supervisor()
    time_step = int(supervisor.getBasicTimeStep())
    if time_step <= 0:
        time_step = 32

    # Configuración Modular de Sensores (4, 6 u 8 sensores)
    sensor_count = 4
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
    elif sensor_count == 6:
        sensor_names = ["us_low_left", "us_low_right", "us_high_left", "us_high_right", "us_side_left", "us_side_right"]
        csv_filename = "orquitas_dataset_6_sensores.csv"
    elif sensor_count == 8:
        sensor_names = ["us_low_left", "us_low_right", "us_high_left", "us_high_right", "us_side_left", "us_side_right", "us_rear_left", "us_rear_right"]
        csv_filename = "orquitas_dataset_8_sensores.csv"
    else:
        sensor_count = 4
        sensor_names = ["us_low_left", "us_low_right", "us_high_left", "us_high_right"]
        csv_filename = "orquitas_dataset_4_sensores.csv"

    print("==========================================================")
    print(f"  ORQUITAS (KEIKO) - GENERADOR MASIVO DE DATASETS ({sensor_count} SENSORES)")
    print(f"  TimeStep: {time_step} ms | Sensores: {len(sensor_names)} habilitados")
    print("==========================================================")

    # 1. Obtener dispositivos de sensores
    sensors = {}
    for name in sensor_names:
        s = supervisor.getDevice(name)
        if s is not None:
            s.enable(time_step)
            sensors[name] = s
        else:
            print(f"[ERROR] No se encontro el sensor: {name}", file=sys.stderr)
            sys.exit(1)

    # 2. Obtener nodo del robot para control de pose
    robot_node = supervisor.getFromDef("ORQUITA")
    if not robot_node:
        print("[ERROR] Nodo DEF ORQUITA no encontrado en el mundo.", file=sys.stderr)
        sys.exit(1)

    trans_field = robot_node.getField("translation")
    rot_field = robot_node.getField("rotation")

    # Posiciones fijas de referencia en la Bahia Semieliptica
    lobitos = [
        (0.24, 0.28),
        (-0.52, -0.05),
        (0.50, -0.32)
    ]
    
    obstaculos = [
        (-0.42, 0.16),
        (0.42, 0.08),
        (-0.28, -0.30)
    ]

    # Salida dinámica: soporta paredes Norte, Este, Sur y Oeste
    exit_wall = detect_exit(supervisor)
    salida_pos, salida_yaw = EXIT_GEOM[exit_wall]
    print(f"[INFO] Salida detectada: {exit_wall.upper()} @ ({salida_pos[0]:.2f}, {salida_pos[1]:.2f})")

    # Configuracion de Dataset
    SAMPLES_PER_CLASS = 400
    classes = ["LOBITO", "MURO", "OBSTACULO", "SALIDA", "ESPACIO_LIBRE"]
    dataset = []

    out_dir = os.path.join(os.path.dirname(__file__), "..", "..", "data", "datasets")
    os.makedirs(out_dir, exist_ok=True)
    out_csv = os.path.join(out_dir, csv_filename)

    print(f"Generando {SAMPLES_PER_CLASS * len(classes)} muestras ({SAMPLES_PER_CLASS} por clase)...")

    for target_class in classes:
        print(f"-> Generando muestras para clase: {target_class}...")
        count = 0
        attempts = 0

        while count < SAMPLES_PER_CLASS and attempts < SAMPLES_PER_CLASS * 60:
            attempts += 1

            if target_class == "LOBITO":
                # Posicionar frente a una canica en el suelo (10 a 40 cm del sensor frontal a X=0.138)
                lob = random.choice(lobitos)
                sensor_dist = random.uniform(0.10, 0.40)
                dist = 0.138 + sensor_dist
                # Ángulo apuntando hacia el lobito con leve variación frontal
                base_angle = random.uniform(0, 2 * math.pi)
                rx = lob[0] - dist * math.cos(base_angle)
                ry = lob[1] - dist * math.sin(base_angle)
                heading = base_angle + random.uniform(-0.04, 0.04)

            elif target_class == "MURO":
                # Posicionar de frente a un muro perimetral (base plana o arco semielíptico)
                wall = random.choice(["south", "arc_left", "arc_right"])
                dist = random.uniform(0.18, 0.45)
                heading_jitter = random.uniform(-0.15, 0.15) # perpendicular al muro

                if wall == "south":
                    rx = random.uniform(-0.65, 0.65)
                    ry = -0.60 + dist
                    heading = -math.pi / 2 + heading_jitter
                elif wall == "arc_left":
                    theta = random.uniform(math.pi * 0.58, math.pi * 0.90)
                    wx = 0.90 * math.cos(theta)
                    wy = -0.60 + 1.20 * math.sin(theta)
                    nx = math.cos(theta) / 0.90
                    ny = math.sin(theta) / 1.20
                    nlen = math.hypot(nx, ny)
                    nx, ny = nx / nlen, ny / nlen
                    rx = wx - nx * dist
                    ry = wy - ny * dist
                    heading = math.atan2(ny, nx) + heading_jitter
                else: # arc_right
                    theta = random.uniform(math.pi * 0.10, math.pi * 0.42)
                    wx = 0.90 * math.cos(theta)
                    wy = -0.60 + 1.20 * math.sin(theta)
                    nx = math.cos(theta) / 0.90
                    ny = math.sin(theta) / 1.20
                    nlen = math.hypot(nx, ny)
                    nx, ny = nx / nlen, ny / nlen
                    rx = wx - nx * dist
                    ry = wy - ny * dist
                    heading = math.atan2(ny, nx) + heading_jitter

            elif target_class == "OBSTACULO":
                # Posicionar lateralmente frente a un obstaculo interno (deteccion asimetrica)
                obs = random.choice(obstaculos)
                dist = random.uniform(0.15, 0.40)
                offset_side = random.choice([-0.10, 0.10])
                base_angle = random.uniform(0, 2 * math.pi)
                rx = obs[0] - dist * math.cos(base_angle) + offset_side * math.sin(base_angle)
                ry = obs[1] - dist * math.sin(base_angle) - offset_side * math.cos(base_angle)
                heading = base_angle + random.uniform(-0.15, 0.15)

            elif target_class == "SALIDA":
                # Pasillo de aproximación al Estrecho de Salida (pared detectada),
                # con variaciones laterales y rotacionales reales sobre el eje de egreso.
                dist_to_exit = random.uniform(0.35, 0.85)
                lateral_off = random.uniform(-0.08, 0.08)
                dir_x, dir_y = math.cos(salida_yaw), math.sin(salida_yaw)
                rx = salida_pos[0] - dir_x * dist_to_exit - dir_y * lateral_off
                ry = salida_pos[1] - dir_y * dist_to_exit + dir_x * lateral_off
                heading = salida_yaw + random.uniform(-0.12, 0.12)

            else: # ESPACIO_LIBRE
                # En áreas centrales despejadas orientadas a corredores libres
                rx = random.uniform(-0.20, 0.20)
                ry = random.uniform(-0.30, 0.05)
                heading = random.uniform(0, 2 * math.pi)

            # Limitar dentro de la pista semielíptica
            rx = clamp(rx, -0.75, 0.75)
            ry = clamp(ry, -0.45, 0.42)

            # Aplicar pose al robot
            trans_field.setSFVec3f([rx, ry, 0.03])
            rot_field.setSFRotation([0, 0, 1, heading])
            supervisor.simulationResetPhysics()

            # Avanzar pasos de simulacion para refrescar sensores
            supervisor.step(time_step)
            supervisor.step(time_step)

            d_ll = sensors["us_low_left"].getValue()
            d_lr = sensors["us_low_right"].getValue()
            d_hl = sensors["us_high_left"].getValue()
            d_hr = sensors["us_high_right"].getValue()

            min_low = min(d_ll, d_lr)
            min_high = min(d_hl, d_hr)
            if "us_low_center" in sensors:
                min_low = min(min_low, sensors["us_low_center"].getValue())
            if "us_high_center" in sensors:
                min_high = min(min_high, sensors["us_high_center"].getValue())

            # Validacion física de la firma
            if target_class == "LOBITO":
                # El lobito es una canica baja: bajo detecta cercania, alto ve significativamente mas lejos
                if min_low > 50.0 or min_high < (min_low + 8.0):
                    continue
            elif target_class == "MURO":
                # Muro perpendicular continuo: ambos planos ven distancia similar y simétrica
                if min_low > 55.0 or min_high > 55.0:
                    continue
                if abs(d_ll - d_lr) > 18.0 or abs(min_low - min_high) > 15.0:
                    continue
            elif target_class == "OBSTACULO":
                # Obstáculo puntual: asimetría clara entre flancos
                if min(min_low, min_high) > 50.0:
                    continue
                asym = abs(min(d_ll, d_hl) - min(d_lr, d_hr))
                if asym < 14.0:
                    continue
            elif target_class == "SALIDA":
                # Salida: sensores frontales ven espacio abierto hacia el estrecho pero muros laterales
                if min_low < 35.0 or min_high < 35.0 or min(d_ll, d_lr) > 240.0:
                    continue
            elif target_class == "ESPACIO_LIBRE":
                # Todo despejado a más de 80 cm
                if min_low < 80.0 or min_high < 80.0:
                    continue

            # Muestra válida: registrar lecturas de todos los sensores configurados
            row_data = {f"dist_{name.replace('us_', '')}": round(sensors[name].getValue(), 2) for name in sensor_names}
            row_data["class_label"] = target_class
            dataset.append(row_data)
            count += 1

        print(f"   -> Obtenidas {count} muestras para {target_class} en {attempts} intentos.")

    # Guardar a CSV
    random.shuffle(dataset)
    with open(out_csv, mode="w", newline="") as f:
        fieldnames = [f"dist_{name.replace('us_', '')}" for name in sensor_names] + ["class_label"]
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(dataset)

    print("\n==========================================================")
    print(f"  DATASET GENERADO CON EXITO: {len(dataset)} filas")
    print(f"  Ruta: {out_csv}")
    print("==========================================================")
    
    supervisor.simulationQuit(0)

if __name__ == "__main__":
    main()
