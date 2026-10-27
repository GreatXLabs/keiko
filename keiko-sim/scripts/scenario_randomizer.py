#!/usr/bin/env python3
"""
Generador y Evaluador de Escenarios Estocásticos (Domain Randomization) - Proyecto Orquitas (Keiko)

Genera mundos Webots (.wbt) con aleatorización total de:
1. Geometría de semielipse (semiejes a y b variables, ondulaciones armónicas de cartón).
2. Posición estocástica de las 3 canicas (incluyendo clusters de canicas contiguas o cerca de bordes).
3. 1 a 5 obstáculos internos de formas y orientaciones aleatorias.
4. Desplazamiento y orientación de la salida.
5. Calibración física reglamentaria: masa total del robot = 1.00 kg.
"""

import math
import random
import os
import sys
import argparse

# Vincular directorio de scripts
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SIM_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
WORLDS_DIR = os.path.join(SIM_DIR, "worlds")
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import build_worlds

def point_on_arc(theta, b, a, y_back, d_sin2=0.0, d_sin3=0.0, d_asym=0.0):
    envelope = math.sin(theta) * abs(math.cos(theta))
    r_mod = 1.0 + envelope * (d_sin2 * math.sin(2.0 * theta) + d_sin3 * math.sin(3.0 * theta) + d_asym * math.cos(theta))
    x = b * r_mod * math.cos(theta)
    y = y_back + a * r_mod * math.sin(theta)
    return x, y

def is_point_inside_arena(x, y, b, a, y_back, margin=0.12, d_sin2=0.0, d_sin3=0.0, d_asym=0.0):
    if y < (y_back + margin):
        return False
    dy = y - y_back
    if dy <= 0:
        return False
    theta = math.atan2(dy, x)
    if theta < 0.05 or theta > (math.pi - 0.05):
        return False
    bx, by = point_on_arc(theta, b, a, y_back, d_sin2, d_sin3, d_asym)
    b_dist = math.hypot(bx, by - y_back)
    p_dist = math.hypot(x, dy)
    return p_dist < (b_dist - margin)

def generate_random_scenario(seed=None, num_obstacles=None, cluster_marbles=False, controller_name="robot_client", include_capote=None):
    if seed is not None:
        random.seed(seed)

    # 1. Variaciones geométricas de la semielipse (simulación de armado manual con cartón)
    b = random.uniform(0.86, 0.95)       # semieje menor (base 1.72 a 1.90 m)
    a = random.uniform(1.15, 1.25)       # semieje mayor (profundidad 1.15 a 1.25 m)
    d_sin2 = random.uniform(-0.03, 0.03)
    d_sin3 = random.uniform(-0.03, 0.03)
    d_asym = random.uniform(-0.04, 0.04)
    y_back = -0.60
    exit_pos = (0.0, y_back + a)

    # Registrar perfil dinámico en ARENA_PROFILES
    profile_name = f"random_{random.randint(1000, 9999)}"
    build_worlds.ARENA_PROFILES[profile_name] = {
        "b": b,
        "a": a,
        "d_sin2": d_sin2,
        "d_sin3": d_sin3,
        "d_asym": d_asym,
    }

    # 2. Obstáculos aleatorios (1 a 5 borradores)
    if num_obstacles is None:
        num_obstacles = random.randint(1, 4)

    boxes = []
    placed_obstacles = []

    for idx in range(num_obstacles):
        for _ in range(100):
            ox = random.uniform(-b * 0.70, b * 0.70)
            oy = random.uniform(y_back + 0.15, y_back + a - 0.25)
            # No tapar la salida
            if math.hypot(ox - exit_pos[0], oy - exit_pos[1]) < 0.35:
                continue
            # No estar en la posición inicial del robot (0.0, -0.45)
            if math.hypot(ox, oy - (y_back + 0.15)) < 0.25:
                continue
            # Separación con otros obstáculos
            if any(math.hypot(ox - px, oy - py) < 0.28 for px, py in placed_obstacles):
                continue
            if is_point_inside_arena(ox, oy, b, a, y_back, margin=0.18, d_sin2=d_sin2, d_sin3=d_sin3, d_asym=d_asym):
                sx = random.uniform(0.12, 0.16)  # ~14 cm
                sy = random.uniform(0.05, 0.08)  # ~6 cm
                sz = random.uniform(0.10, 0.14)  # ~12 cm
                yaw = random.uniform(-math.pi, math.pi)
                color = random.choice(["0.85 0.35 0.15", "0.25 0.45 0.80", "0.75 0.60 0.15", "0.35 0.65 0.35"])
                boxes.append((f"OBSTACLE_BOX_{idx+1}", f"obstacle_eraser_{idx+1}", ox, oy, sz/2.0, sx, sy, sz, yaw, color))
                placed_obstacles.append((ox, oy))
                break

    # 3. Tres Lobitos (canicas de 16 mm)
    marbles = []
    placed_marbles = []

    # Canica 1
    for _ in range(100):
        mx = random.uniform(-b * 0.70, b * 0.70)
        my = random.uniform(y_back + 0.15, y_back + a - 0.20)
        if math.hypot(mx, my - (y_back + 0.15)) < 0.25: continue
        if any(math.hypot(mx - ox, my - oy) < 0.14 for ox, oy in placed_obstacles): continue
        if is_point_inside_arena(mx, my, b, a, y_back, margin=0.14, d_sin2=d_sin2, d_sin3=d_sin3, d_asym=d_asym):
            marbles.append(("lobito_1", mx, my))
            placed_marbles.append((mx, my))
            break

    # Canica 2 (puede estar en cluster pegada a la canica 1 si cluster_marbles=True)
    if cluster_marbles and len(placed_marbles) > 0:
        m1x, m1y = placed_marbles[0]
        angle = random.uniform(0, 2 * math.pi)
        dist = random.uniform(0.03, 0.05)  # 3 a 5 cm de separación (cluster contiguo)
        m2x = m1x + dist * math.cos(angle)
        m2y = m1y + dist * math.sin(angle)
        if is_point_inside_arena(m2x, m2y, b, a, y_back, margin=0.10, d_sin2=d_sin2, d_sin3=d_sin3, d_asym=d_asym):
            marbles.append(("lobito_2", m2x, m2y))
            placed_marbles.append((m2x, m2y))

    while len(marbles) < 2:
        for _ in range(100):
            mx = random.uniform(-b * 0.70, b * 0.70)
            my = random.uniform(y_back + 0.15, y_back + a - 0.20)
            if math.hypot(mx, my - (y_back + 0.15)) < 0.25: continue
            if any(math.hypot(mx - px, my - py) < 0.18 for px, py in placed_marbles): continue
            if any(math.hypot(mx - ox, my - oy) < 0.14 for ox, oy in placed_obstacles): continue
            if is_point_inside_arena(mx, my, b, a, y_back, margin=0.14, d_sin2=d_sin2, d_sin3=d_sin3, d_asym=d_asym):
                marbles.append(("lobito_2", mx, my))
                placed_marbles.append((mx, my))
                break

    # Canica 3
    for _ in range(100):
        mx = random.uniform(-b * 0.70, b * 0.70)
        my = random.uniform(y_back + 0.15, y_back + a - 0.20)
        if math.hypot(mx, my - (y_back + 0.15)) < 0.25: continue
        if any(math.hypot(mx - px, my - py) < 0.18 for px, py in placed_marbles): continue
        if any(math.hypot(mx - ox, my - oy) < 0.14 for ox, oy in placed_obstacles): continue
        if is_point_inside_arena(mx, my, b, a, y_back, margin=0.14, d_sin2=d_sin2, d_sin3=d_sin3, d_asym=d_asym):
            marbles.append(("lobito_3", mx, my))
            placed_marbles.append((mx, my))
            break

    # Registrar mapa dinámico en MAP_CONFIGS
    map_config_name = f"random_map_{random.randint(1000, 9999)}"
    build_worlds.MAP_CONFIGS[map_config_name] = {
        "marbles": marbles,
        "boxes": boxes,
        "cylinders": []
    }

    # Generar WBT usando build_worlds
    wbt_str = build_worlds.generate_world(
        controller_name=controller_name,
        map_name=map_config_name,
        sensor_config="trisector_front_6",
        exit_wall="north",
        include_capote=include_capote
    )

    return wbt_str, {
        "seed": seed,
        "b": b, "a": a,
        "num_obstacles": len(boxes),
        "marbles": marbles,
        "obstacles": [(b_name, bx, by) for _, b_name, bx, by, *_ in boxes]
    }

def main():
    parser = argparse.ArgumentParser(description="Generador Paramétrico Estocástico de la Bahía de Caza")
    parser.add_argument("--seed", type=int, default=None, help="Semilla aleatoria")
    parser.add_argument("--obstacles", type=int, default=None, help="Cantidad de obstáculos (1 a 5)")
    parser.add_argument("--clusters", action="store_true", help="Generar canicas contiguas pegadas (stress test)")
    parser.add_argument("--capote", action="store_true", help="Incluir carcasa exterior 'Capote' 3D")
    parser.add_argument("--client", action="store_true", help="Usar controlador robot_client")
    parser.add_argument("--output", type=str, default=os.path.join(WORLDS_DIR, "bahia_de_caza_random_client.wbt"))
    args = parser.parse_args()

    controller = "robot_client" if args.client else "robot_client"
    wbt_content, meta = generate_random_scenario(
        seed=args.seed,
        num_obstacles=args.obstacles,
        cluster_marbles=args.clusters,
        controller_name=controller,
        include_capote=args.capote or None
    )

    with open(args.output, "w", encoding="utf-8") as f:
        f.write(wbt_content)

    print(f"✅ Mundo estocástico generado en: {args.output}")
    print(f"   Semiejes: a={meta['a']:.2f}m, b={meta['b']:.2f}m | Obstáculos: {meta['num_obstacles']}")
    for m_name, mx, my in meta['marbles']:
        print(f"   Canica {m_name}: ({mx:.2f}, {my:.2f})")

if __name__ == "__main__":
    main()
