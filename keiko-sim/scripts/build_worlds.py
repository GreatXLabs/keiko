#!/usr/bin/env python3
"""
Generador Paramétrico de Mundos Webots 3D - Proyecto Orquitas (Keiko)
Construye el escenario reglamentario de la Bahía de Caza según las especificaciones reales:
1. Geometría de semielipse irregular (~1.80 m de base contra pared plana x ~1.20 m de profundidad,
   área ~1.70 m²), delimitada por muros de cartón de 5 mm de espesor y 18 cm de altura.
2. Hueco de salida fijo de 30 cm en el centro del arco curvo, sin chaflanes, marcos ni túneles
   exteriores (los ultrasonidos ven espacio libre > 200 cm únicamente a través de esta abertura).
3. Obstáculos internos (2 a 3) del tamaño de un borrador de pizarra (14 cm x 6 cm x 12 cm).
4. Tres Lobitos (canicas de 16 mm) con amplio margen respecto a muros y obstáculos.
5. Modelo 3D real del robot exportado desde GreatXLabs/Keiko-Modelos-3D (chasis 257x150x76.1 mm,
   ruedas giratorias TT de 67.2 mm y carcasa "Capote" opcional configurable).
"""

import math
import os
import sys

# Perfiles de deformación de la semielipse (simulando el armado manual con cartón)
ARENA_PROFILES = {
    "standard": {
        "b": 0.90,         # Semieje menor sobre la pared (base = 2b = 1.80 m)
        "a": 1.20,         # Semieje mayor de profundidad hacia la salida (1.20 m)
        "d_sin2": 0.00,    # Perturbación simétrica
        "d_sin3": 0.00,    # Perturbación de 3er armónico
        "d_asym": 0.00,    # Asimetría izquierda/derecha
    },
    "deformed_wide": {
        "b": 0.96,
        "a": 1.15,
        "d_sin2": 0.03,
        "d_sin3": -0.02,
        "d_asym": 0.02,
    },
    "deformed_narrow": {
        "b": 0.84,
        "a": 1.25,
        "d_sin2": -0.03,
        "d_sin3": 0.02,
        "d_asym": -0.02,
    },
    "asymmetric": {
        "b": 0.90,
        "a": 1.20,
        "d_sin2": 0.02,
        "d_sin3": 0.03,
        "d_asym": 0.05,
    },
    "labyrinth": {
        "b": 0.92,
        "a": 1.20,
        "d_sin2": 0.02,
        "d_sin3": -0.02,
        "d_asym": 0.03,
    },
    "corners": {
        "b": 0.90,
        "a": 1.20,
        "d_sin2": -0.02,
        "d_sin3": 0.02,
        "d_asym": -0.04,
    },
    "corridors": {
        "b": 0.86,
        "a": 1.22,
        "d_sin2": 0.00,
        "d_sin3": 0.02,
        "d_asym": 0.00,
    },
}

# Configuración de elementos internos (2 a 3 obstáculos tamaño borrador 14x6x12 cm + 3 canicas con margen amplio)
# La pared plana del fondo está en Y = -0.60 m y el vértice de salida en (0.0, +0.60 m).
MAP_CONFIGS = {
    "standard": {
        "marbles": [
            ("lobito_1", 0.28, 0.15),
            ("lobito_2", -0.35, -0.12),
            ("lobito_3", 0.25, -0.28),
        ],
        "boxes": [
            # (def_id, name, x, y, z, sx, sy, sz, yaw, color)
            ("OBSTACLE_BOX_1", "obstacle_eraser_1", -0.28, 0.18, 0.06, 0.14, 0.06, 0.12, 0.35, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_eraser_2", 0.32, -0.08, 0.06, 0.14, 0.06, 0.12, -0.50, "0.25 0.45 0.80"),
        ],
        "cylinders": [],
    },
    "labyrinth": {
        "marbles": [
            ("lobito_1", 0.34, 0.18),
            ("lobito_2", -0.36, 0.05),
            ("lobito_3", 0.15, -0.30),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_eraser_1", -0.15, 0.22, 0.06, 0.14, 0.06, 0.12, 0.20, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_eraser_2", -0.32, -0.22, 0.06, 0.14, 0.06, 0.12, 1.10, "0.25 0.45 0.80"),
            ("OBSTACLE_BOX_3", "obstacle_eraser_3", 0.28, -0.12, 0.06, 0.14, 0.06, 0.12, -0.75, "0.75 0.60 0.15"),
        ],
        "cylinders": [],
    },
    "corners": {
        "marbles": [
            ("lobito_1", -0.45, -0.25),
            ("lobito_2", 0.45, -0.25),
            ("lobito_3", -0.25, 0.22),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_eraser_1", -0.18, -0.05, 0.06, 0.14, 0.06, 0.12, 0.60, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_eraser_2", 0.22, 0.08, 0.06, 0.14, 0.06, 0.12, -0.40, "0.25 0.45 0.80"),
        ],
        "cylinders": [],
    },
    "corridors": {
        "marbles": [
            ("lobito_1", 0.00, 0.25),
            ("lobito_2", -0.32, -0.05),
            ("lobito_3", 0.32, -0.22),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_eraser_1", -0.22, 0.10, 0.06, 0.14, 0.06, 0.12, 1.57, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_eraser_2", 0.22, 0.10, 0.06, 0.14, 0.06, 0.12, 1.57, "0.25 0.45 0.80"),
            ("OBSTACLE_BOX_3", "obstacle_eraser_3", 0.00, -0.18, 0.06, 0.14, 0.06, 0.12, 0.00, "0.75 0.60 0.15"),
        ],
        "cylinders": [],
    },
}


def get_arena_geometry(variant="standard"):
    """
    Calcula los segmentos de muro de la semielipse (pared plana de fondo + arco curvo de cartón de 5mm)
    con un hueco central exacto de 30 cm en el vértice de salida.
    """
    prof = ARENA_PROFILES.get(variant, ARENA_PROFILES["standard"])
    b = prof["b"]
    a = prof["a"]
    d_sin2 = prof["d_sin2"]
    d_sin3 = prof["d_sin3"]
    d_asym = prof["d_asym"]

    y_back = -0.60
    wall_thickness = 0.005  # 5 mm fijo (cartón)
    wall_height = 0.18      # 18 cm fijo (rango 15-20 cm)
    exit_width = 0.30       # 30 cm fijo
    half_gap = exit_width / 2.0  # 0.15 m a cada lado de X = 0

    def point_on_arc(theta):
        # En theta=0, pi y theta=pi/2 el factor de deformación radial se anula en los bordes de la salida
        # y en los extremos de la pared para garantizar continuidad exacta.
        envelope = math.sin(theta) * abs(math.cos(theta))
        r_mod = 1.0 + envelope * (d_sin2 * math.sin(2.0 * theta) + d_sin3 * math.sin(3.0 * theta) + d_asym * math.cos(theta))
        x = b * r_mod * math.cos(theta)
        y = y_back + a * r_mod * math.sin(theta)
        return x, y

    # Encontrar theta_right (en [0, pi/2]) donde x(theta) = +half_gap (+0.15 m)
    lo, hi = 0.0, math.pi / 2.0
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        xm, _ = point_on_arc(mid)
        if xm > half_gap:
            lo = mid
        else:
            hi = mid
    theta_right = 0.5 * (lo + hi)

    # Encontrar theta_left (en [pi/2, pi]) donde x(theta) = -half_gap (-0.15 m)
    lo, hi = math.pi / 2.0, math.pi
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        xm, _ = point_on_arc(mid)
        if xm > -half_gap:
            lo = mid
        else:
            hi = mid
    theta_left = 0.5 * (lo + hi)

    n_half_segments = 16
    segments = []

    # Arco derecho: desde theta = 0 (pared en +b) hasta theta_right (borde derecho de la salida en X=+0.15)
    right_pts = [point_on_arc(theta_right * (i / n_half_segments)) for i in range(n_half_segments + 1)]
    for i in range(n_half_segments):
        x0, y0 = right_pts[i]
        x1, y1 = right_pts[i + 1]
        cx, cy = 0.5 * (x0 + x1), 0.5 * (y0 + y1)
        length = math.hypot(x1 - x0, y1 - y0) + 0.002  # solape de 2mm en uniones de cartón
        yaw = math.atan2(y1 - y0, x1 - x0)
        segments.append({
            "def": f"WALL_ARC_R{i+1:02d}",
            "name": f"wall_arc_r{i+1:02d}",
            "cx": cx,
            "cy": cy,
            "length": length,
            "yaw": yaw,
        })

    # Arco izquierdo: desde theta_left (borde izquierdo de la salida en X=-0.15) hasta theta = pi (pared en -b)
    left_pts = [point_on_arc(theta_left + (math.pi - theta_left) * (i / n_half_segments)) for i in range(n_half_segments + 1)]
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

    # Calcular área interior por fórmula del polígono (Shoelace)
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
    geom = get_arena_geometry(map_name)
    exit_rotation = EXIT_ROTATIONS.get(exit_wall, EXIT_ROTATIONS["north"])
    t = geom["wall_thickness"]
    h = geom["wall_height"]
    z_center = h / 2.0
    bw = geom["back_wall"]

    nodes = []
    # 1. Pared plana de fondo (la pared del aula donde apoya la base de ~1.80 m)
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

    # 2. Segmentos del arco semielíptico de cartón (5 mm de grosor, 18 cm de alto)
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
        size {seg['length']:.4f} {t:.4f} {h:.4f}
      }}
    }}""")

    # 3. Plataforma de piso exterior (solo suelo a nivel Z=-0.10, sin paredes ni marcos en la salida)
    ey = geom["exit_pos"][1]
    nodes.append(f"""    # Referencia de Piso Exterior de Salida (sin muros ni marcos que devuelvan eco)
    DEF EXIT_PLATFORM Solid {{
      translation 0 {ey + 0.35:.4f} -0.10
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.14 0.24 0.32
            roughness 0.6
            metalness 0.05
          }}
          geometry Box {{
            size 0.80 0.70 0.20
          }}
        }}
      ]
      name "exit_platform"
      contactMaterial "floor"
      boundingObject Box {{
        size 0.80 0.70 0.20
      }}
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

    # Renderizar obstáculos tipo borrador de pizarra (Boxes)
    for ob in cfg.get("boxes", []):
        if len(ob) == 10:
            def_id, name, x, y, z, sx, sy, sz, yaw, color = ob
        else:
            def_id, name, x, y, z, sx, sy, sz, color = ob
            yaw = 0.0
        lines.append(f"""# Obstaculo (Borrador de Pizarra): {name}
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

    # Renderizar las 3 canicas (Lobitos)
    for idx, (m_name, mx, my) in enumerate(cfg.get("marbles", []), start=1):
        def_name = f"LOBITO_{idx}"
        lines.append(f"""# Canica {idx} (Lobito): 16mm de diametro
DEF {def_name} Solid {{
  translation {mx} {my} 0.008
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.0 0.9 0.9
        roughness 0.1
        metalness 0.3
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


EXIT_ROTATIONS = {
    "north": "0 0 1 0",
    "east": "0 0 1 -1.5708",
    "south": "0 0 1 3.1416",
}

SENSOR_LOOKUP = "0.0 0.0 0.0, 0.02 2.0 0.02, 2.50 250.0 0.02"
SENSOR_APERTURE_RAD = 0.50

TRANSDUCERS = {
    "LOW": ("US_TRANSDUCER", "0.1 0.5 0.9"),
    "HIGH": ("US_TRANSDUCER_HIGH", "0.9 0.8 0.1"),
    "SIDE": ("US_TRANSDUCER_SIDE", "0.2 0.8 0.4"),
    "REAR": ("US_TRANSDUCER_REAR", "0.8 0.2 0.8"),
}

SENSOR_LAYOUTS = {
    "dual_plane_4": {"front": "dual_plane", "side": False, "rear": False},
    "dual_plane_6": {"front": "dual_plane", "side": True, "rear": False},
    "dual_plane_8": {"front": "dual_plane", "side": True, "rear": True},
    "trisector_10": {"front": "trisector", "side": True, "rear": True},
}

DUAL_PLANE_FRONT = [
    ("us_low_left", 0.130, 0.035, 0.025, 0.0873, "LOW"),
    ("us_low_right", 0.130, -0.035, 0.025, -0.0873, "LOW"),
    ("us_high_left", 0.120, 0.050, 0.095, 0.3491, "HIGH"),
    ("us_high_right", 0.120, -0.050, 0.095, -0.3491, "HIGH"),
]

TRISECTOR_FRONT = [
    ("us_low_left", 0.125, 0.055, 0.012, 0.4363, "LOW"),
    ("us_low_center", 0.140, 0.000, 0.012, 0.0, "LOW"),
    ("us_low_right", 0.125, -0.055, 0.012, -0.4363, "LOW"),
    ("us_high_left", 0.125, 0.055, 0.095, 0.4363, "HIGH"),
    ("us_high_center", 0.140, 0.000, 0.095, 0.0, "HIGH"),
    ("us_high_right", 0.125, -0.055, 0.095, -0.4363, "HIGH"),
]

SIDE_SENSORS = [
    ("us_side_left", 0.0, 0.075, 0.065, 1.5708, "SIDE"),
    ("us_side_right", 0.0, -0.075, 0.065, -1.5708, "SIDE"),
]

REAR_SENSORS = [
    ("us_rear_left", -0.125, 0.040, 0.065, 3.1416, "REAR"),
    ("us_rear_right", -0.125, -0.040, 0.065, 3.1416, "REAR"),
]


def render_sensor_rig(sensor_config="dual_plane_8"):
    layout = SENSOR_LAYOUTS.get(sensor_config, SENSOR_LAYOUTS["dual_plane_8"])
    sensors = list(TRISECTOR_FRONT if layout["front"] == "trisector" else DUAL_PLANE_FRONT)
    if layout["side"]:
        sensors += SIDE_SENSORS
    if layout["rear"]:
        sensors += REAR_SENSORS

    defined = set()
    nodes = []
    for name, x, y, z, yaw, kind in sensors:
        def_id, color = TRANSDUCERS[kind]
        if kind in defined:
            child = f"USE {def_id}"
        else:
            defined.add(kind)
            child = f"""DEF {def_id} Shape {{
        appearance PBRAppearance {{ baseColor {color} roughness 0.3 }}
        geometry Cylinder {{ height 0.012 radius 0.008 }}
      }}"""
        nodes.append(f"""    DistanceSensor {{
      translation {x} {y} {z}
      rotation 0 0 1 {yaw}
      name "{name}"
      type "sonar"
      numberOfRays 17
      aperture {SENSOR_APERTURE_RAD}
      lookupTable [ {SENSOR_LOOKUP} ]
      children [
        {child}
      ]
    }}""")

    hud_node = """    # -------------------------------------------------------------------------
    # DISPLAY HUD: RADAR SONAR 2D EN TIEMPO REAL (EN PANTALLA)
    # -------------------------------------------------------------------------
    Display {
      name "sensor_hud"
      width 340
      height 340
    }"""

    return "\n\n".join(nodes) + "\n\n" + hud_node


def generate_world(controller_name, map_name="standard", sensor_config="dual_plane_8", exit_wall="north", include_capote=None):
    if include_capote is None:
        include_capote = os.environ.get("ORQUITAS_CAPOTE", "0").strip() in ("1", "true", "TRUE", "yes")

    capote_visual_node = ""
    if include_capote:
        capote_visual_node = """    # Carcasa Exterior "Capote" (CAD Chassis.FCStd: frente en +X, aleta dorsal y cola en -X)
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

    return f"""#VRML_SIM R2025a utf8

WorldInfo {{
  info [
    "Bahia de Caza - Entorno de simulacion 3D realista para el Proyecto Orquitas (Keiko)",
    "Semielipse de ~1.80m x 1.20m (~1.70 m2) con muros de carton de 5mm, hueco de salida de 30cm y modelo CAD 3D."
  ]
  title "Bahia de Caza - Orquitas (Semielipse Realista)"
  basicTimeStep 32
  FPS 60
  contactProperties [
    ContactProperties {{
      material1 "wheel"
      material2 "floor"
      coulombFriction [
        0.82
      ]
      forceDependentSlip [
        0.010
      ]
      bounce 0.0
    }}
    ContactProperties {{
      material1 "wheel"
      material2 "wall"
      coulombFriction [
        0.05
      ]
      forceDependentSlip [
        0.05
      ]
      bounce 0.0
    }}
    ContactProperties {{
      material1 "marble"
      material2 "floor"
      coulombFriction [
        0.12
      ]
      bounce 0.04
    }}
    ContactProperties {{
      material1 "marble"
      material2 "wheel"
      coulombFriction [
        0.45
      ]
      bounce 0.0
    }}
    ContactProperties {{
      material1 "marble"
      material2 "chassis"
      coulombFriction [
        0.25
      ]
      bounce 0.02
    }}
    ContactProperties {{
      material1 "marble"
      material2 "wall"
      coulombFriction [
        0.15
      ]
      bounce 0.2
    }}
    ContactProperties {{
      material1 "chassis"
      material2 "floor"
      coulombFriction [
        0.18
      ]
      forceDependentSlip [
        0.015
      ]
      bounce 0.01
    }}
    ContactProperties {{
      material1 "chassis"
      material2 "wall"
      coulombFriction [
        0.05
      ]
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
  skyColor [
    0.35 0.65 0.90
  ]
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

# Suelo Principal de la Bahia de Caza (2.2m x 1.6m x 0.20m)
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
        size 2.2 1.6 0.20
      }}
    }}
  ]
  name "floor"
  contactMaterial "floor"
  boundingObject Box {{
    size 2.2 1.6 0.20
  }}
}}

# Suelo del Laboratorio Extendido (12m x 12m) - Permite que el ultrasonido mida >250cm al salir por el hueco de 30cm
DEF SAFETY_GROUND Solid {{
  translation 0 0 -0.20
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.08 0.12 0.18
        roughness 0.8
        metalness 0.0
      }}
      geometry Box {{
        size 12.0 12.0 0.20
      }}
    }}
  ]
  name "safety_ground"
  contactMaterial "floor"
  boundingObject Box {{
    size 12.0 12.0 0.20
  }}
}}

# Muros de Contencion Lejanos (a 6.0m del centro, fuera del alcance de 2.5m-3.0m del HC-SR04)
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

# Robot Autonomo: La Orquita 4WD (Modelo 3D Real de GreatXLabs/Keiko-Modelos-3D: 257mm x 150mm x 76.1mm)
DEF ORQUITA Robot {{
  translation 0 -0.32 0.001
  rotation 0 0 1 1.5708
  children [
    # 1. Malla 3D Real del Chasis 4WD (Bandejas, Separadores de Bronce, Soportes y 4 Motores TT)
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

    # 2. Sensor de Captura Bucal Frontal
    TouchSensor {{
      translation 0.085 0 0.016
      name "capture_sensor"
      boundingObject Box {{
        size 0.010 0.060 0.020
      }}
    }}

    # -------------------------------------------------------------------------
    # TREN DE TRACCION 4WD: 4 MOTORES DC TT Y RUEDAS CAD REALES (D = 67.2 mm)
    # Coordenadas exactas del CAD: X = +-0.0586 m, Y = +-0.0596 m, Z = 0.0336 m
    # -------------------------------------------------------------------------
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

    {render_sensor_rig(sensor_config)}
  ]
  name "orquita_robot"
  contactMaterial "chassis"
  boundingObject Group {{
    children [
      # Colisionador físico de las bandejas del chasis CAD (257mm x 150mm x 60mm, despejado del suelo)
      Pose {{
        translation 0 0 0.048
        children [
          Box {{ size 0.257 0.150 0.055 }}
        ]
      }}
    ]
  }}
  physics Physics {{
    density -1
    mass 0.78
    centerOfMass [ 0 0 0.035 ]
  }}
  controller "{controller_name}"
  supervisor TRUE
}}
"""


# -----------------------------------------------------------------------------
# GENERACION DE MUNDOS DE SIMULACION
# -----------------------------------------------------------------------------
WORLDS = [
    ("bahia_de_caza.wbt", "live_visualizer", "standard", "dual_plane_8", "north"),
    ("bahia_de_caza_client.wbt", "robot_client", "standard", "dual_plane_8", "north"),
    ("bahia_de_caza_dataset.wbt", "dataset_collector", "standard", "dual_plane_8", "north"),
    ("bahia_de_caza_dataset_dp4.wbt", "dataset_collector", "standard", "dual_plane_4", "north"),
    ("bahia_de_caza_dataset_dp6.wbt", "dataset_collector", "standard", "dual_plane_6", "north"),
    ("bahia_de_caza_dataset_trisector.wbt", "dataset_collector", "standard", "trisector_10", "north"),
    ("bahia_de_caza_labyrinth.wbt", "live_visualizer", "labyrinth", "dual_plane_8", "north"),
    ("bahia_de_caza_labyrinth_client.wbt", "robot_client", "labyrinth", "dual_plane_8", "north"),
    ("bahia_de_caza_corners.wbt", "live_visualizer", "corners", "dual_plane_8", "north"),
    ("bahia_de_caza_corners_client.wbt", "robot_client", "corners", "dual_plane_8", "north"),
    ("bahia_de_caza_corridors.wbt", "live_visualizer", "corridors", "dual_plane_8", "north"),
    ("bahia_de_caza_corridors_client.wbt", "robot_client", "corridors", "dual_plane_8", "north"),
    ("bahia_de_caza_dp4.wbt", "live_visualizer", "standard", "dual_plane_4", "north"),
    ("bahia_de_caza_dp6.wbt", "live_visualizer", "standard", "dual_plane_6", "north"),
    ("bahia_de_caza_trisector.wbt", "live_visualizer", "standard", "trisector_10", "north"),
    ("bahia_de_caza_trisector_client.wbt", "robot_client", "standard", "trisector_10", "north"),
    ("bahia_de_caza_east.wbt", "live_visualizer", "standard", "dual_plane_8", "east"),
    ("bahia_de_caza_south.wbt", "live_visualizer", "standard", "dual_plane_8", "south"),
]


def build_all_worlds(include_capote=None):
    worlds_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "worlds")
    os.makedirs(worlds_dir, exist_ok=True)
    for filename, controller, map_name, sensor_config, exit_wall in WORLDS:
        with open(os.path.join(worlds_dir, filename), "w") as f:
            f.write(generate_world(controller, map_name, sensor_config, exit_wall, include_capote=include_capote))
    print(f"Se generaron {len(WORLDS)} mundos .wbt exitosamente (semielipse realista + CAD 3D).")


if __name__ == "__main__":
    use_capote = ("--capote" in sys.argv) or (os.environ.get("ORQUITAS_CAPOTE", "0") in ("1", "true", "TRUE"))
    build_all_worlds(include_capote=use_capote)
