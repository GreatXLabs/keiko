#!/usr/bin/env python3
"""
Generador Paramétrico de Mundos Webots 3D - Proyecto Orquitas (Keiko)
Construye el escenario reglamentario de la Bahía de Caza según las especificaciones reales:
1. Geometría de semielipse (~1.80 m de base contra pared plana x ~1.20 m de profundidad,
   área ~1.70 m²), delimitada por muros de cartón de 5 mm de espesor y 18 cm de altura.
2. Hueco de salida fijo de 30 cm en el centro del arco curvo.
3. Exactamente 2 obstáculos 3D reglamentarios (6 cm x 6 cm x 6 cm).
4. Tres Lobitos (canicas de 16 mm de diámetro, vidrio blanco opaco "lechero").
5. Modelo 3D del robot Orquita 4WD (chasis 257x150x76.1 mm, ruedas 67.2 mm):
   - Fila superior: 3 sensores ultrasónicos HC-SR04 fijos (yaw=0°, pitch=0°, altura 5.5 cm).
   - Fila inferior: 2 sensores láser ToF VL53L0X sobre 2 servos SG90 (altura 1.8 cm, brazo 2.0 cm).
   - Hueco frontal del picker de exactamente 7.0 cm libres (|Y| <= 0.035 m) a ras de suelo.
   - Paragolpes físicos laterales que desvían canicas fuera de los 7.0 cm.
"""

import math
import os
import random
import sys

ARENA_PROFILES = {
    "standard": {
        "b": 0.90,         # Semieje menor sobre la pared (base = 2b = 1.80 m)
        "a": 1.20,         # Semieje mayor de profundidad hacia la salida (1.20 m)
        "d_sin2": 0.00,
        "d_sin3": 0.00,
        "d_asym": 0.00,
    },
}

# Configuraciones de escenarios de evaluación (14 escenarios fijos + aleatorio reglamentario)
# La pared plana del fondo está en Y = -0.60 m y el vértice de salida en (0.0, +0.60 m).
# Obstáculos: exactamente 2 cubos de 6x6x6 cm (0.06 x 0.06 x 0.06 m, z=0.03 m).
# Canicas: 3 esferas de 16 mm (radio 0.008 m, z=0.008 m), blancas opacas.
MAP_CONFIGS = {
    "standard": {
        "marbles": [
            ("lobito_1", 0.18, 0.15),
            ("lobito_2", -0.35, -0.12),
            ("lobito_3", 0.21, -0.32),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.25, 0.18, 0.03, 0.06, 0.06, 0.06, 0.35, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.28, -0.08, 0.03, 0.06, 0.06, 0.06, -0.50, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_1": {
        "marbles": [
            ("lobito_1", 0.21, 0.14),
            ("lobito_2", -0.3, -0.15),
            ("lobito_3", 0.15, -0.35),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.22, 0.14, 0.03, 0.06, 0.06, 0.06, 0.20, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.28, -0.10, 0.03, 0.06, 0.06, 0.06, -0.40, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_2": {
        "marbles": [
            ("lobito_1", -0.4, -0.02),
            ("lobito_2", 0.41, -0.04),
            ("lobito_3", 0.0, -0.3),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.15, 0.00, 0.03, 0.06, 0.06, 0.06, 0.50, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.20, 0.10, 0.03, 0.06, 0.06, 0.06, -0.30, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_3": {
        "marbles": [
            ("lobito_1", -0.27, 0.1),
            ("lobito_2", 0.26, 0.11),
            ("lobito_3", 0.25, -0.25),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", 0.00, 0.12, 0.03, 0.06, 0.06, 0.06, 0.00, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", -0.28, -0.15, 0.03, 0.06, 0.06, 0.06, 0.80, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_4": {
        "marbles": [
            ("lobito_1", 0.08, 0.18),
            ("lobito_2", -0.25, -0.1),
            ("lobito_3", -0.1, -0.35),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.20, 0.20, 0.03, 0.06, 0.06, 0.06, 0.30, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.18, -0.05, 0.03, 0.06, 0.06, 0.06, -0.60, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_5": {
        "marbles": [
            ("lobito_1", 0.0, 0.19),
            ("lobito_2", -0.35, -0.2),
            ("lobito_3", 0.35, -0.2),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.26, 0.08, 0.03, 0.06, 0.06, 0.06, 0.45, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.26, 0.08, 0.03, 0.06, 0.06, 0.06, -0.45, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_6": {
        "marbles": [
            ("lobito_1", -0.26, 0.11),
            ("lobito_2", 0.01, 0.19),
            ("lobito_3", 0.2, -0.3),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", 0.25, 0.10, 0.03, 0.06, 0.06, 0.06, -0.25, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", -0.15, -0.15, 0.03, 0.06, 0.06, 0.06, 0.60, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_7": {
        "marbles": [
            ("lobito_1", -0.36, -0.05),
            ("lobito_2", 0.17, 0.15),
            ("lobito_3", -0.05, -0.32),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.10, 0.18, 0.03, 0.06, 0.06, 0.06, 0.15, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.32, -0.05, 0.03, 0.06, 0.06, 0.06, -0.70, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_8": {
        "marbles": [
            ("lobito_1", -0.46, -0.12),
            ("lobito_2", 0.37, 0.01),
            ("lobito_3", -0.1, 0.18),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", 0.12, 0.05, 0.03, 0.06, 0.06, 0.06, -0.35, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", -0.22, -0.05, 0.03, 0.06, 0.06, 0.06, 0.40, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    # --- NUEVOS ESCENARIOS DE PRUEBA REGLAMENTARIOS (scen_9 a scen_14) ---
    "scen_9": {
        # Escolta de Obstáculos: canicas ubicadas a ~15 cm de los cubos 6x6x6 cm
        # para evaluar la discriminación por ancho en tics entre canica y obstáculo.
        "marbles": [
            ("lobito_1", 0.0, 0.17),
            ("lobito_2", 0.02, -0.11),
            ("lobito_3", -0.26, -0.15),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.24, 0.10, 0.03, 0.06, 0.06, 0.06, 0.25, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.24, 0.02, 0.03, 0.06, 0.06, 0.06, -0.35, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_10": {
        # Corredor Diagonal (Slalom): los 2 obstáculos forman una chicana central diagonal
        # obligando a esquivar por el centro mientras busca canicas en los flancos y norte.
        "marbles": [
            ("lobito_1", 0.11, -0.09),
            ("lobito_2", -0.39, -0.01),
            ("lobito_3", 0.36, -0.22),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.14, -0.04, 0.03, 0.06, 0.06, 0.06, 0.40, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.14, 0.16, 0.03, 0.06, 0.06, 0.06, -0.40, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_11": {
        # Perímetro Curvo: canicas próximas al arco elíptico exterior para evaluar
        # que el filtro anti-pared no descarte canicas cercanas a los muros curvos.
        "marbles": [
            ("lobito_1", -0.44, -0.08),
            ("lobito_2", 0.43, -0.06),
            ("lobito_3", 0.04, -0.09),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.16, 0.06, 0.03, 0.06, 0.06, 0.06, 0.15, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.18, 0.12, 0.03, 0.06, 0.06, 0.06, -0.25, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_12": {
        # Racimo Cercano (Cluster): dos canicas próximas entre sí en el sector noroeste
        # (~17 cm de separación) y una tercera al sureste.
        "marbles": [
            ("lobito_1", -0.21, 0.14),
            ("lobito_2", 0.05, -0.11),
            ("lobito_3", 0.34, -0.18),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", 0.22, 0.08, 0.03, 0.06, 0.06, 0.06, -0.50, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", -0.20, -0.12, 0.03, 0.06, 0.06, 0.06, 0.30, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_13": {
        # Guardia de Salida: los 2 obstáculos flanquean la aproximación al hueco norte
        # de 30 cm (-0.18, 0.22) y (+0.18, 0.22), con canica central frontal y en el sur.
        "marbles": [
            ("lobito_1", 0.0, 0.04),
            ("lobito_2", -0.36, -0.14),
            ("lobito_3", 0.36, -0.14),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.18, 0.22, 0.03, 0.06, 0.06, 0.06, 0.20, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.18, 0.22, 0.03, 0.06, 0.06, 0.06, -0.20, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
    "scen_14": {
        # Triángulo Profundo Asimétrico: spawn en extremo Este apuntando al Noroeste (105°)
        # con un obstáculo directamente en la diagonal inicial de salida.
        "marbles": [
            ("lobito_1", -0.01, 0.19),
            ("lobito_2", -0.38, -0.06),
            ("lobito_3", 0.26, 0.11),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", 0.04, -0.06, 0.03, 0.06, 0.06, 0.06, 0.65, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", -0.26, 0.16, 0.03, 0.06, 0.06, 0.06, -0.35, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.74, -0.42, 1.5708),
    },
}


def generate_random_scenario_config(seed=None):
    """
    Genera una configuración aleatoria 100% reglamentaria y libre de atascos:
    - Semielipse estándar (1.80 m x 1.20 m, salida norte de 30 cm en (0, +0.60)).
    - Spawn del robot en esquina suroeste (-0.74, -0.42, 90°).
    - Canicas lejos de muros (rho <= 0.66, y in [-0.38, 0.30]), lejos de obstáculos (>= 0.25m),
      lejos entre sí (>= 0.28m) y lejos del spawn (>= 0.40m).
    - Exactamente 2 obstáculos de 6x6x6 cm dentro de la arena sin bloquear la salida ni el spawn.
    """
    rng = random.Random(seed)
    sx = -0.74
    sy = -0.42
    syaw = 1.5708

    def inside_safe_ellipse(x, y, max_rho=0.66):
        rho = math.hypot(x / 0.90, (y + 0.60) / 1.20)
        return rho <= max_rho and -0.38 <= y <= 0.30

    boxes = []
    colors = ["0.85 0.35 0.15", "0.25 0.45 0.80"]
    for idx in range(2):
        for _ in range(1000):
            bx = round(rng.uniform(-0.35, 0.35), 2)
            by = round(rng.uniform(-0.16, 0.20), 2)
            if not inside_safe_ellipse(bx, by, max_rho=0.64):
                continue
            if abs(bx) < 0.16 and by > 0.14:
                continue
            if math.hypot(bx - sx, by - sy) < 0.35:
                continue
            if any(math.hypot(bx - ox, by - oy) < 0.28 for _, _, ox, oy, *_ in boxes):
                continue
            byaw = round(rng.uniform(-0.80, 0.80), 2)
            boxes.append((
                f"OBSTACLE_BOX_{idx + 1}",
                f"obstacle_3d_{idx + 1}",
                bx, by, 0.03, 0.06, 0.06, 0.06, byaw, colors[idx]
            ))
            break

    marbles = []
    for idx in range(3):
        for _ in range(2000):
            mx = round(rng.uniform(-0.55, 0.55), 2)
            my = round(rng.uniform(-0.38, 0.30), 2)
            if not inside_safe_ellipse(mx, my, max_rho=0.66):
                continue
            # Lejos de la posición de inicio del auto (mínimo 40 cm)
            if math.hypot(mx - sx, my - sy) < 0.40:
                continue
            # Lejos de obstáculos (mínimo 25 cm al centro de la caja)
            if any(math.hypot(mx - ox, my - oy) < 0.25 for _, _, ox, oy, *_ in boxes):
                continue
            # Lejos entre sí (mínimo 28 cm)
            if any(math.hypot(mx - omx, my - omy) < 0.28 for _, omx, omy in marbles):
                continue
            marbles.append((f"lobito_{idx + 1}", mx, my))
            break

    return {
        "marbles": marbles,
        "boxes": boxes,
        "spawn": (sx, sy, syaw),
    }


def get_arena_geometry(variant="standard"):
    prof = ARENA_PROFILES.get(variant, ARENA_PROFILES["standard"])
    b = prof["b"]
    a = prof["a"]
    d_sin2 = prof["d_sin2"]
    d_sin3 = prof["d_sin3"]
    d_asym = prof["d_asym"]

    y_back = -0.60
    wall_thickness = 0.005  # 5 mm
    wall_height = 0.18      # 18 cm
    exit_width = 0.30       # 30 cm
    half_gap = exit_width / 2.0

    def point_on_arc(theta):
        envelope = math.sin(theta) * abs(math.cos(theta))
        perturbation = (
            d_sin2 * math.sin(2.0 * theta)
            + d_sin3 * math.sin(3.0 * theta)
            + d_asym * math.cos(theta)
        )
        r_mod = 1.0 + envelope * perturbation
        x = b * math.cos(theta) * r_mod
        y = y_back + a * math.sin(theta) * r_mod
        return (x, y)

    theta_exit_right = math.acos(min(0.999, half_gap / b))
    theta_exit_left = math.pi - theta_exit_right

    n_half_segments = 16
    right_pts = [point_on_arc(0.0 + (theta_exit_right - 0.0) * (i / n_half_segments)) for i in range(n_half_segments + 1)]
    left_pts = [point_on_arc(theta_exit_left + (math.pi - theta_exit_left) * (i / n_half_segments)) for i in range(n_half_segments + 1)]

    segments = []
    for i in range(n_half_segments):
        x0, y0 = right_pts[i]
        x1, y1 = right_pts[i + 1]
        cx, cy = 0.5 * (x0 + x1), 0.5 * (y0 + y1)
        length = math.hypot(x1 - x0, y1 - y0) + 0.002
        yaw = math.atan2(y1 - y0, x1 - x0)
        segments.append({
            "def": f"WALL_ARC_R{i+1:02d}",
            "name": f"wall_arc_r{i+1:02d}",
            "cx": cx,
            "cy": cy,
            "length": length,
            "yaw": yaw,
        })

    for i in range(n_half_segments):
        x0, y0 = left_pts[i]
        x1, y1 = left_pts[i + 1]
        cx, cy = 0.5 * (x0 + x1), 0.5 * (y0 + y1)
        length = math.hypot(x1 - x0, y1 - y0) + 0.002
        yaw = math.atan2(y1 - y0, x1 - x0)
        segments.append({
            "def": f"WALL_ARC_L{i+1:02d}",
            "name": f"wall_arc_l{i+1:02d}",
            "cx": cx,
            "cy": cy,
            "length": length,
            "yaw": yaw,
        })

    poly_pts = [point_on_arc(math.pi * (i / 64.0)) for i in range(65)]
    area = 0.0
    for i in range(len(poly_pts)):
        x0, y0 = poly_pts[i]
        x1, y1 = poly_pts[(i + 1) % len(poly_pts)]
        area += (x0 * y1 - x1 * y0)
    area = 0.5 * abs(area)

    gap_r = right_pts[-1]
    gap_l = left_pts[0]
    actual_gap = math.hypot(gap_r[0] - gap_l[0], gap_r[1] - gap_l[1])

    return {
        "variant": variant,
        "b": b,
        "a": a,
        "y_back": y_back,
        "exit_pos": (0.0, y_back + a),
        "wall_thickness": wall_thickness,
        "wall_height": wall_height,
        "exit_width": exit_width,
        "actual_exit_gap_m": actual_gap,
        "area_m2": area,
        "back_wall": {
            "def": "WALL_SOUTH",
            "name": "wall_back",
            "cx": 0.0,
            "cy": y_back,
            "length": 2.0 * b + 0.01,
            "yaw": 0.0,
        },
        "arc_segments": segments,
    }


def render_arena_walls(map_name="standard", exit_wall="north"):
    geom = get_arena_geometry("standard")
    exit_rotation = "0 0 1 0" if exit_wall == "north" else ("0 0 1 -1.5708" if exit_wall == "east" else "0 0 1 3.1416")
    t = geom["wall_thickness"]
    h = geom["wall_height"]
    z_center = h / 2.0
    bw = geom["back_wall"]

    nodes = []
    nodes.append(f"""    # Pared Plana de Base (~1.80m sobre la pared del laboratorio)
    DEF {bw['def']} Solid {{
      translation {bw['cx']:.4f} {bw['cy']:.4f} {z_center:.4f}
      children [
        Shape {{
          appearance DEF BACK_WALL_APP PBRAppearance {{
            baseColor 0.82 0.85 0.88
            roughness 0.5
          }}
          geometry Box {{
            size {bw['length']:.4f} 0.020 {h:.4f}
          }}
        }}
      ]
      name "{bw['name']}"
      contactMaterial "wall"
      boundingObject Box {{
        size {bw['length']:.4f} 0.020 {h:.4f}
      }}
    }}""")

    defined_cardboard = False
    for seg in geom["arc_segments"]:
        if not defined_cardboard:
            app_str = """DEF CARDBOARD_APP PBRAppearance {
            baseColor 0.76 0.60 0.42
            roughness 0.75
            metalness 0.0
          }"""
            defined_cardboard = True
        else:
            app_str = "USE CARDBOARD_APP"

        nodes.append(f"""    DEF {seg['def']} Solid {{
      translation {seg['cx']:.4f} {seg['cy']:.4f} {z_center:.4f}
      rotation 0 0 1 {seg['yaw']:.5f}
      children [
        Shape {{
          appearance {app_str}
          geometry Box {{
            size {seg['length']:.4f} {t:.4f} {h:.4f}
          }}
        }}
      ]
      name "{seg['name']}"
      contactMaterial "wall"
      boundingObject Box {{
        size {seg['length']:.4f} 0.020 {h:.4f}
      }}
    }}""")

    ey = geom["exit_pos"][1]
    nodes.append(f"""    # Marcador de Referencia de Salida (30 cm)
    DEF EXIT_PLATFORM Solid {{
      translation 0 {ey + 0.35:.4f} -0.10
      name "exit_platform"
    }}""")

    inner = "\n\n".join(nodes)
    return f"""# Perimetro Semieliptico de Carton (5mm) y Hueco de Salida (30cm, orientacion: {exit_wall})
Transform {{
  rotation {exit_rotation}
  children [
{inner}
  ]
}}"""


def render_map_elements(map_name="standard"):
    cfg = MAP_CONFIGS.get(map_name, MAP_CONFIGS["standard"])
    lines = []

    # Renderizar exactamente 2 obstáculos 3D (6x6x6 cm)
    for ob in cfg.get("boxes", []):
        def_id, name, x, y, z, sx, sy, sz, yaw, color = ob
        lines.append(f"""# Obstaculo 3D ({sx*100:.0f}x{sy*100:.0f}x{sz*100:.0f} cm): {name}
DEF {def_id} Solid {{
  translation {x} {y} {z}
  rotation 0 0 1 {yaw}
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor {color}
        roughness 0.6
      }}
      geometry Box {{
        size {sx} {sy} {sz}
      }}
    }}
  ]
  name "{name}"
  contactMaterial "wall"
  boundingObject Box {{
    size {sx} {sy} {sz}
  }}
}}""")

    # Renderizar exactamente 3 canicas (Lobitos) de 16 mm (blancas opacas "lecheras")
    for idx, (m_name, mx, my) in enumerate(cfg.get("marbles", []), start=1):
        def_name = f"LOBITO_{idx}"
        lines.append(f"""# Canica {idx} (Lobito): 16mm diametro (vidrio blanco opaco)
DEF {def_name} Solid {{
  translation {mx} {my} 0.008
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.95 0.95 0.95
        roughness 0.15
        metalness 0.05
      }}
      geometry DEF MARBLE_GEOM Sphere {{
        radius 0.008
      }}
    }}
  ]
  name "{m_name}"
  contactMaterial "marble"
  boundingObject USE MARBLE_GEOM
  physics Physics {{
    density -1
    mass 0.0055
  }}
}}""")

    return "\n\n".join(lines)


def render_sensor_rig():
    """
    Renderiza la nueva configuración real del hardware:
    1. Fila superior: 3 sensores ultrasónicos HC-SR04 fijos (yaw=0°, pitch=0°, Z=0.055 m).
    2. Fila inferior: 2 sensores láser ToF VL53L0X sobre 2 servos SG90 (Z=0.018 m, brazo 2.0 cm).
    """
    nodes = []

    # 1. ULTRASONICOS HC-SR04 FIJOS (Z = 0.055 m, yaw = 0°, pitch = 0°)
    us_sensors = [
        ("us_left",   0.1325,  0.054, 0.055),
        ("us_center", 0.1325,  0.000, 0.055),
        ("us_right",  0.1325, -0.054, 0.055),
    ]

    for name, x, y, z in us_sensors:
        nodes.append(f"""    # Sensor Ultrasonico HC-SR04: {name} (Z={z}m, yaw=0°)
    DistanceSensor {{
      translation {x} {y} {z}
      rotation 0 0 1 0
      name "{name}"
      type "generic"
      numberOfRays 1
      lookupTable [ 0.0 0.0 0.0, 0.02 2.0 0.02, 2.50 250.0 0.02 ]
      children [
        DEF US_TRANSDUCER Shape {{
          appearance PBRAppearance {{ baseColor 0.1 0.5 0.9 roughness 0.3 }}
          geometry Cylinder {{ height 0.012 radius 0.008 }}
        }}
      ]
    }}""")

    # 2. LASERES ToF VL53L0X SOBRE SERVOS SG90
    laser_lookup = "0.0 0.0 0.0, 0.02 2.0 0.02, 2.00 200.0 0.02, 2.01 999.0 0.0"

    # Servo Izquierdo (eje en X=+0.1405, Y=+0.083, Z=0.018)
    # Rango en espejo: -45° (centro) a +60° (afuera)
    nodes.append(f"""    # Servo SG90 + VL53L0X Izquierdo (Eje en X=+0.1405, Y=+0.083, Z=0.018)
    HingeJoint {{
      jointParameters HingeJointParameters {{
        axis 0 0 1
        anchor 0.1405 0.083 0.018
      }}
      device [
        RotationalMotor {{
          name "servo_left"
          minPosition -0.80
          maxPosition 1.10
          maxVelocity 15.0
        }}
        PositionSensor {{
          name "servo_left_sensor"
        }}
      ]
      endPoint Solid {{
        translation 0.1405 0.083 0.018
        children [
          DEF SERVO_HORN_L Shape {{
            appearance PBRAppearance {{ baseColor 0.2 0.2 0.25 roughness 0.4 }}
            geometry Box {{ size 0.022 0.006 0.005 }}
          }}
          DistanceSensor {{
            translation 0.020 0 0
            rotation 0 1 0 -0.021
            name "laser_left"
            type "generic"
            numberOfRays 7
            aperture 0.4363
            lookupTable [ {laser_lookup} ]
            children [
              DEF LASER_CHIP Shape {{
                appearance PBRAppearance {{ baseColor 0.85 0.15 0.15 roughness 0.2 }}
                geometry Box {{ size 0.005 0.010 0.005 }}
              }}
            ]
          }}
        ]
        name "bracket_laser_left"
        physics Physics {{
          density -1
          mass 0.01
        }}
      }}
    }}""")

    # Servo Derecho (eje en X=+0.1405, Y=-0.083, Z=0.018)
    # Movimiento en espejo: rotación invertida respecto al izquierdo
    nodes.append(f"""    # Servo SG90 + VL53L0X Derecho (Eje en X=+0.1405, Y=-0.083, Z=0.018)
    HingeJoint {{
      jointParameters HingeJointParameters {{
        axis 0 0 1
        anchor 0.1405 -0.083 0.018
      }}
      device [
        RotationalMotor {{
          name "servo_right"
          minPosition -1.10
          maxPosition 0.80
          maxVelocity 15.0
        }}
        PositionSensor {{
          name "servo_right_sensor"
        }}
      ]
      endPoint Solid {{
        translation 0.1405 -0.083 0.018
        children [
          DEF SERVO_HORN_R Shape {{
            appearance PBRAppearance {{ baseColor 0.2 0.2 0.25 roughness 0.4 }}
            geometry Box {{ size 0.022 0.006 0.005 }}
          }}
          DistanceSensor {{
            translation 0.020 0 0
            rotation 0 1 0 -0.021
            name "laser_right"
            type "generic"
            numberOfRays 7
            aperture 0.4363
            lookupTable [ {laser_lookup} ]
            children [
              USE LASER_CHIP
            ]
          }}
        ]
        name "bracket_laser_right"
        physics Physics {{
          density -1
          mass 0.01
        }}
      }}
    }}""")

    hud_node = """    # Display HUD Radar
    Display {
      name "sensor_hud"
      width 340
      height 340
    }"""
    nodes.append(hud_node)

    return "\n\n".join(nodes)


def generate_world(controller_name, map_name="standard", exit_wall="north", include_capote=None):
    if include_capote is None:
        include_capote = os.environ.get("ORQUITAS_CAPOTE", "0").strip() in ("1", "true", "TRUE", "yes")

    capote_visual_node = ""
    if include_capote:
        capote_visual_node = """    # Carcasa Exterior "Capote"
    DEF ORQUITA_CAPOTE_VISUAL Shape {
      appearance PBRAppearance {
        baseColor 0.12 0.14 0.18
        roughness 0.35
        metalness 0.15
        transparency 0.25
      }
      geometry Mesh {
        url [ "../meshes/orquita_capote.stl" ]
      }
    }"""

    cfg = MAP_CONFIGS.get(map_name, MAP_CONFIGS["standard"])
    spawn_x, spawn_y, spawn_yaw = cfg.get("spawn", (-0.74, -0.42, 1.5708))

    return f"""#VRML_SIM R2025a utf8

WorldInfo {{
  info [
    "Bahia de Caza - Entorno de simulacion 3D realista para el Proyecto Orquitas (Keiko)",
    "Semielipse de ~1.80m x 1.20m (~1.70 m2) con muros de carton de 5mm, hueco de salida de 30cm, 2 obstaculos 3D (6x6x6cm) y 3 canicas de 16mm."
  ]
  title "Bahia de Caza - Orquitas 3US + 2Laser"
  basicTimeStep 32
  FPS 60
  contactProperties [
    ContactProperties {{
      material1 "wheel"
      material2 "floor"
      coulombFriction [ 0.82 ]
      forceDependentSlip [ 0.010 ]
      bounce 0.0
    }}
    ContactProperties {{
      material1 "wheel"
      material2 "wall"
      coulombFriction [ 0.05 ]
      forceDependentSlip [ 0.05 ]
      bounce 0.0
    }}
    ContactProperties {{
      material1 "marble"
      material2 "floor"
      coulombFriction [ 0.12 ]
      bounce 0.04
    }}
    ContactProperties {{
      material1 "marble"
      material2 "wheel"
      coulombFriction [ 0.45 ]
      bounce 0.0
    }}
    ContactProperties {{
      material1 "marble"
      material2 "chassis"
      coulombFriction [ 0.25 ]
      bounce 0.02
    }}
    ContactProperties {{
      material1 "marble"
      material2 "wall"
      coulombFriction [ 0.15 ]
      bounce 0.2
    }}
    ContactProperties {{
      material1 "chassis"
      material2 "floor"
      coulombFriction [ 0.18 ]
      forceDependentSlip [ 0.015 ]
      bounce 0.01
    }}
    ContactProperties {{
      material1 "chassis"
      material2 "wall"
      coulombFriction [ 0.05 ]
      bounce 0.01
    }}
  ]
}}

Viewpoint {{
  orientation 0 1 0 0.78
  position -1.9 0 1.65
  follow "orquita_robot"
}}

Background {{
  skyColor [ 0.35 0.65 0.90 ]
}}

DirectionalLight {{
  ambientIntensity 0.8
  direction 0.4 0.3 -1.0
  intensity 1.5
  castShadows TRUE
}}

PointLight {{
  ambientIntensity 0.5
  attenuation 0 0 1
  intensity 2.0
  location 0 0 2.2
}}

# Suelo Continuo
DEF FLOOR Solid {{
  translation 0 0 -0.10
  children [
    Shape {{
      appearance DEF FLOOR_APP PBRAppearance {{
        baseColor 0.15 0.25 0.35
        roughness 0.6
        metalness 0.1
      }}
      geometry Box {{
        size 12.0 12.0 0.20
      }}
    }}
  ]
  name "floor"
  contactMaterial "floor"
  boundingObject Box {{
    size 12.0 12.0 0.20
  }}
}}

# Muros de Contencion Lejanos
DEF SAFETY_WALL_NORTH Solid {{
  translation 0 6.0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{ baseColor 0.1 0.15 0.2 roughness 0.8 transparency 0.7 }}
      geometry Box {{ size 12.0 0.08 0.60 }}
    }}
  ]
  name "safety_wall_north"
  contactMaterial "wall"
  boundingObject Box {{ size 12.0 0.08 0.60 }}
}}

DEF SAFETY_WALL_SOUTH Solid {{
  translation 0 -6.0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{ baseColor 0.1 0.15 0.2 roughness 0.8 transparency 0.7 }}
      geometry Box {{ size 12.0 0.08 0.60 }}
    }}
  ]
  name "safety_wall_south"
  contactMaterial "wall"
  boundingObject Box {{ size 12.0 0.08 0.60 }}
}}

DEF SAFETY_WALL_WEST Solid {{
  translation -6.0 0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{ baseColor 0.1 0.15 0.2 roughness 0.8 transparency 0.7 }}
      geometry Box {{ size 0.08 12.0 0.60 }}
    }}
  ]
  name "safety_wall_west"
  contactMaterial "wall"
  boundingObject Box {{ size 0.08 12.0 0.60 }}
}}

DEF SAFETY_WALL_EAST Solid {{
  translation 6.0 0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{ baseColor 0.1 0.15 0.2 roughness 0.8 transparency 0.7 }}
      geometry Box {{ size 0.08 12.0 0.60 }}
    }}
  ]
  name "safety_wall_east"
  contactMaterial "wall"
  boundingObject Box {{ size 0.08 12.0 0.60 }}
}}

{render_arena_walls(map_name, exit_wall)}

{render_map_elements(map_name)}

# Robot Autonomo: La Orquita 4WD (Modelo 3D Real: 257mm x 150mm x 76.1mm)
DEF ORQUITA Robot {{
  translation {spawn_x:.4f} {spawn_y:.4f} 0.001
  rotation 0 0 1 {spawn_yaw:.4f}
  children [
    # Malla CAD del Chasis 4WD
    DEF ORQUITA_CHASSIS_VISUAL Shape {{
      appearance PBRAppearance {{
        baseColor 0.82 0.84 0.88
        roughness 0.25
        metalness 0.20
      }}
      geometry Mesh {{
        url [ "../meshes/orquita_chassis_body.stl" ]
      }}
    }}
{capote_visual_node}

    # -------------------------------------------------------------------------
    # CONJUNTO VISUAL DEL PICKER MOUTH (7.0 cm libres, rodillo y guías embudo)
    # -------------------------------------------------------------------------
    # Rodillo activo del picker (cilindro horizontal giratorio en el túnel)
    Pose {{
      translation 0.095 0 0.016
      rotation 1 0 0 1.5708
      children [
        DEF PICKER_ROLLER Shape {{
          appearance PBRAppearance {{
            baseColor 0.95 0.35 0.05
            roughness 0.40
            metalness 0.10
          }}
          geometry Cylinder {{
            height 0.066
            radius 0.010
          }}
        }}
      ]
    }}
    # Guía embudo izquierda (alta visibilidad naranja flanqueando la boca de 7.0 cm)
    Pose {{
      translation 0.115 0.038 0.016
      rotation 0 0 1 -0.2618
      children [
        DEF PICKER_GUIDE_L Shape {{
          appearance PBRAppearance {{
            baseColor 0.95 0.55 0.05
            roughness 0.30
          }}
          geometry Box {{
            size 0.035 0.005 0.024
          }}
        }}
      ]
    }}
    # Guía embudo derecha (alta visibilidad naranja flanqueando la boca de 7.0 cm)
    Pose {{
      translation 0.115 -0.038 0.016
      rotation 0 0 1 0.2618
      children [
        DEF PICKER_GUIDE_R Shape {{
          appearance PBRAppearance {{
            baseColor 0.95 0.55 0.05
            roughness 0.30
          }}
          geometry Box {{
            size 0.035 0.005 0.024
          }}
        }}
      ]
    }}
    # Placa indicadora superior de la boca del picker (marcador frontal de 7.0 cm)
    Pose {{
      translation 0.095 0 0.029
      children [
        DEF PICKER_ROOF_LIP Shape {{
          appearance PBRAppearance {{
            baseColor 0.10 0.75 0.95
            roughness 0.20
          }}
          geometry Box {{
            size 0.040 0.070 0.004
          }}
        }}
      ]
    }}

    # TREN DE TRACCION 4WD: 4 Motores DC TT
    # Motor 1: Delantero Izquierdo
    HingeJoint {{
      jointParameters HingeJointParameters {{
        axis 0 1 0
        anchor 0.0586 0.0596 0.0336
      }}
      device [
        RotationalMotor {{
          name "front_left_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "front_left_encoder" }}
      ]
      endPoint Solid {{
        translation 0.0586 0.0596 0.0336
        children [
          DEF WHEEL_CAD_SHAPE Shape {{
            appearance PBRAppearance {{
              baseColor 0.92 0.80 0.08
              roughness 0.55
            }}
            geometry Mesh {{
              url [ "../meshes/orquita_wheel.stl" ]
            }}
          }}
        ]
        name "wheel_fl"
        contactMaterial "wheel"
        boundingObject Pose {{
          rotation 1 0 0 1.5708
          children [
            Capsule {{
              height 0.002
              radius 0.0336
            }}
          ]
        }}
        physics Physics {{
          density -1
          mass 0.04
        }}
      }}
    }}

    # Motor 2: Trasero Izquierdo
    HingeJoint {{
      jointParameters HingeJointParameters {{
        axis 0 1 0
        anchor -0.0586 0.0596 0.0336
      }}
      device [
        RotationalMotor {{
          name "rear_left_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "rear_left_encoder" }}
      ]
      endPoint Solid {{
        translation -0.0586 0.0596 0.0336
        children [ USE WHEEL_CAD_SHAPE ]
        name "wheel_rl"
        contactMaterial "wheel"
        boundingObject Pose {{
          rotation 1 0 0 1.5708
          children [
            Capsule {{
              height 0.002
              radius 0.0336
            }}
          ]
        }}
        physics Physics {{
          density -1
          mass 0.04
        }}
      }}
    }}

    # Motor 3: Delantero Derecho
    HingeJoint {{
      jointParameters HingeJointParameters {{
        axis 0 1 0
        anchor 0.0586 -0.0596 0.0336
      }}
      device [
        RotationalMotor {{
          name "front_right_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "front_right_encoder" }}
      ]
      endPoint Solid {{
        translation 0.0586 -0.0596 0.0336
        children [ USE WHEEL_CAD_SHAPE ]
        name "wheel_fr"
        contactMaterial "wheel"
        boundingObject Pose {{
          rotation 1 0 0 1.5708
          children [
            Capsule {{
              height 0.002
              radius 0.0336
            }}
          ]
        }}
        physics Physics {{
          density -1
          mass 0.04
        }}
      }}
    }}

    # Motor 4: Trasero Derecho
    HingeJoint {{
      jointParameters HingeJointParameters {{
        axis 0 1 0
        anchor -0.0586 -0.0596 0.0336
      }}
      device [
        RotationalMotor {{
          name "rear_right_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "rear_right_encoder" }}
      ]
      endPoint Solid {{
        translation -0.0586 -0.0596 0.0336
        children [ USE WHEEL_CAD_SHAPE ]
        name "wheel_rr"
        contactMaterial "wheel"
        boundingObject Pose {{
          rotation 1 0 0 1.5708
          children [
            Capsule {{
              height 0.002
              radius 0.0336
            }}
          ]
        }}
        physics Physics {{
          density -1
          mass 0.04
        }}
      }}
    }}

{render_sensor_rig()}
  ]
  name "orquita_robot"
  contactMaterial "chassis"
  boundingObject Group {{
    children [
      # Paragolpes frontal izquierdo (flanco Y ∈ [+0.035, +0.080] m, baja a Z=0.003m para rebotar canicas)
      Pose {{
        translation 0.09875 0.0575 0.039
        children [
          Box {{ size 0.0675 0.045 0.072 }}
        ]
      }}
      # Paragolpes frontal derecho (flanco Y ∈ [-0.080, -0.035] m, baja a Z=0.003m para rebotar canicas)
      Pose {{
        translation 0.09875 -0.0575 0.039
        children [
          Box {{ size 0.0675 0.045 0.072 }}
        ]
      }}
      # Techo superior sobre el hueco del picker (Y ∈ [-0.035, +0.035] m, Z >= 0.030m; hueco libre abajo de 7.0 cm)
      Pose {{
        translation 0.09875 0 0.0525
        children [
          Box {{ size 0.0675 0.070 0.045 }}
        ]
      }}
      # Cuerpo central
      Pose {{
        translation 0 0 0.048
        children [
          Box {{ size 0.125 0.088 0.055 }}
        ]
      }}
      # Paragolpes trasero
      Pose {{
        translation -0.095 0 0.048
        children [
          Box {{ size 0.065 0.150 0.055 }}
        ]
      }}
    ]
  }}
  physics Physics {{
    density -1
    mass 0.84
    centerOfMass [ 0 0 0.035 ]
  }}
  controller "{controller_name}"
  supervisor TRUE
}}
"""


WORLDS = [
    ("bahia_de_caza.wbt", "live_visualizer", "standard", "north"),
    ("bahia_de_caza_client.wbt", "robot_client", "standard", "north"),
    ("bahia_de_caza_dataset.wbt", "dataset_collector", "standard", "north"),
    ("bahia_de_caza_scen_1.wbt", "robot_client", "scen_1", "north"),
    ("bahia_de_caza_scen_2.wbt", "robot_client", "scen_2", "north"),
    ("bahia_de_caza_scen_3.wbt", "robot_client", "scen_3", "north"),
    ("bahia_de_caza_scen_4.wbt", "robot_client", "scen_4", "north"),
    ("bahia_de_caza_scen_5.wbt", "robot_client", "scen_5", "north"),
    ("bahia_de_caza_scen_6.wbt", "robot_client", "scen_6", "north"),
    ("bahia_de_caza_scen_7.wbt", "robot_client", "scen_7", "north"),
    ("bahia_de_caza_scen_8.wbt", "robot_client", "scen_8", "north"),
    ("bahia_de_caza_scen_9.wbt", "robot_client", "scen_9", "north"),
    ("bahia_de_caza_scen_10.wbt", "robot_client", "scen_10", "north"),
    ("bahia_de_caza_scen_11.wbt", "robot_client", "scen_11", "north"),
    ("bahia_de_caza_scen_12.wbt", "robot_client", "scen_12", "north"),
    ("bahia_de_caza_scen_13.wbt", "robot_client", "scen_13", "north"),
    ("bahia_de_caza_scen_14.wbt", "robot_client", "scen_14", "north"),
]


def build_random_world(include_capote=None, seed=None):
    worlds_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "worlds")
    os.makedirs(worlds_dir, exist_ok=True)
    cfg = generate_random_scenario_config(seed=seed)
    MAP_CONFIGS["scen_random"] = cfg
    out_path = os.path.join(worlds_dir, "bahia_de_caza_scen_random.wbt")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(generate_world("robot_client", "scen_random", "north", include_capote=include_capote))
    sx, sy, syaw = cfg["spawn"]
    print(f"🎲 Escenario aleatorio generado: {out_path}")
    print(f"   Spawn Robot : ({sx:+.2f}, {sy:+.2f}) m, yaw={math.degrees(syaw):.1f}°")
    for _, name, bx, by, *_ in cfg["boxes"]:
        print(f"   Obstáculo 6cm: {name} en ({bx:+.2f}, {by:+.2f}) m")
    for mname, mx, my in cfg["marbles"]:
        print(f"   Canica 16mm  : {mname} en ({mx:+.2f}, {my:+.2f}) m")
    return out_path


def build_all_worlds(include_capote=None):
    worlds_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "worlds")
    os.makedirs(worlds_dir, exist_ok=True)
    for filename, controller, map_name, exit_wall in WORLDS:
        out_path = os.path.join(worlds_dir, filename)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(generate_world(controller, map_name, exit_wall, include_capote=include_capote))
    print(f"Se generaron {len(WORLDS)} mundos .wbt exitosamente (3 US + 2 Laser/Servo, picker 7.0cm, 2 obstaculos 6x6x6cm).")


if __name__ == "__main__":
    use_capote = ("--capote" in sys.argv) or (os.environ.get("ORQUITAS_CAPOTE", "0") in ("1", "true", "TRUE"))
    if "--random" in sys.argv:
        build_random_world(include_capote=use_capote)
    else:
        build_all_worlds(include_capote=use_capote)
        build_random_world(include_capote=use_capote)

