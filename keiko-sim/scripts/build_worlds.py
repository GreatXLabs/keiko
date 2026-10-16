import os
import sys

MAP_CONFIGS = {
    "standard": {
        "marbles": [
            ("lobito_1", 0.30, 0.50),
            ("lobito_2", -0.60, -0.10),
            ("lobito_3", 0.40, -0.70)
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_box_1", -0.5, 0.4, 0.10, 0.20, 0.20, 0.20, "0.9 0.4 0.1"),
            ("OBSTACLE_BOX_2", "obstacle_box_2", -0.3, -0.6, 0.10, 0.25, 0.15, 0.20, "0.9 0.4 0.1")
        ],
        "cylinders": [
            ("OBSTACLE_CYLINDER_1", "obstacle_cylinder_1", 0.5, -0.3, 0.10, 0.20, 0.10, "0.7 0.3 0.8")
        ]
    },
    "labyrinth": {
        "marbles": [
            ("lobito_1", 0.50, 0.60),
            ("lobito_2", -0.45, 0.05),
            ("lobito_3", 0.20, -0.75)
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_box_1", -0.20, 0.40, 0.10, 0.20, 0.20, 0.20, "0.9 0.4 0.1"),
            ("OBSTACLE_BOX_2", "obstacle_box_2", -0.50, -0.40, 0.10, 0.25, 0.15, 0.20, "0.9 0.4 0.1")
        ],
        "cylinders": [
            ("OBSTACLE_CYLINDER_1", "obstacle_cylinder_1", 0.45, 0.10, 0.10, 0.20, 0.10, "0.7 0.3 0.8"),
            ("OBSTACLE_CYLINDER_2", "obstacle_cylinder_2", 0.30, -0.45, 0.10, 0.20, 0.09, "0.3 0.7 0.9")
        ]
    },
    "corners": {
        "marbles": [
            ("lobito_1", -0.70, 0.70),
            ("lobito_2", -0.75, -0.75),
            ("lobito_3", 0.75, -0.65)
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_box_1", -0.25, 0.35, 0.10, 0.30, 0.15, 0.20, "0.9 0.4 0.1"),
            ("OBSTACLE_BOX_2", "obstacle_box_2", 0.40, -0.15, 0.10, 0.20, 0.25, 0.20, "0.9 0.4 0.1")
        ],
        "cylinders": [
            ("OBSTACLE_CYLINDER_1", "obstacle_cylinder_1", -0.50, -0.20, 0.10, 0.20, 0.10, "0.7 0.3 0.8")
        ]
    },
    "corridors": {
        "marbles": [
            ("lobito_1", 0.00, 0.60),
            ("lobito_2", 0.00, -0.10),
            ("lobito_3", 0.00, -0.80)
        ],
        "boxes": [
            ("CORRIDOR_WALL_LEFT", "corridor_wall_left", -0.18, 0.00, 0.10, 0.04, 2.30, 0.20, "0.45 0.50 0.60"),
            ("CORRIDOR_WALL_RIGHT", "corridor_wall_right", 0.18, 0.00, 0.10, 0.04, 2.30, 0.20, "0.45 0.50 0.60")
        ],
        "cylinders": []
    }
}

def render_map_elements(map_name="standard"):
    cfg = MAP_CONFIGS.get(map_name, MAP_CONFIGS["standard"])
    lines = []

    # Renderizar obstáculos rectangulares (Boxes)
    for ob in cfg.get("boxes", []):
        def_id, name, x, y, z, sx, sy, sz, color = ob
        lines.append(f"""# Obstaculo Prismático: {name}
DEF {def_id} Solid {{
  translation {x} {y} {z}
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor {color}
        roughness 0.5
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

    # Renderizar obstáculos cilíndricos
    for ob in cfg.get("cylinders", []):
        def_id, name, x, y, z, h, r, color = ob
        lines.append(f"""# Obstaculo Cilíndrico: {name}
DEF {def_id} Solid {{
  translation {x} {y} {z}
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor {color}
        roughness 0.5
      }}
      geometry Cylinder {{
        height {h}
        radius {r}
      }}
    }}
  ]
  name "{name}"
  contactMaterial "wall"
  boundingObject Cylinder {{
    height {h}
    radius {r}
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


def generate_world(controller_name, map_name="standard", sensor_config="dual_plane_8", exit_wall="north"):
    exit_rotation = EXIT_ROTATIONS.get(exit_wall, EXIT_ROTATIONS["north"])
    return f"""#VRML_SIM R2025a utf8

WorldInfo {{
  info [
    "Bahia de Caza - Entorno de simulacion 3D para el Proyecto Orquitas (Keiko)",
    "Pista de 2.4m x 2.4m con muros de 20cm, salida de 30cm, plataforma de escape y presas."
  ]
  title "Bahia de Caza - Orquitas"
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
  orientation 0 1 0 0.70
  position -2.6 0 2.2
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

# Suelo Principal de la Bahia de Caza (2.4m x 2.4m x 0.20m - Solido impenetrable)
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
        size 2.4 2.4 0.20
      }}
    }}
  ]
  name "floor"
  contactMaterial "floor"
  boundingObject Box {{
    size 2.4 2.4 0.20
  }}
}}

# Suelo de Seguridad Perimetral Extendido (10m x 10m a Z=-0.20) - Evita cualquier caida al vacio
DEF SAFETY_GROUND Solid {{
  translation 0 0.5 -0.20
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.08 0.12 0.18
        roughness 0.8
        metalness 0.0
      }}
      geometry Box {{
        size 10.0 10.0 0.20
      }}
    }}
  ]
  name "safety_ground"
  contactMaterial "floor"
  boundingObject Box {{
    size 10.0 10.0 0.20
  }}
}}

# Muros de Contencion Perimetral Exterior (Seguridad Absoluta Anti-Vacio 10m x 10m)
DEF SAFETY_WALL_NORTH Solid {{
  translation 0 5.0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.1 0.15 0.2
        roughness 0.8
        transparency 0.5
      }}
      geometry Box {{
        size 10.0 0.08 0.60
      }}
    }}
  ]
  name "safety_wall_north"
  contactMaterial "wall"
  boundingObject Box {{
    size 10.0 0.08 0.60
  }}
}}

DEF SAFETY_WALL_SOUTH Solid {{
  translation 0 -5.0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.1 0.15 0.2
        roughness 0.8
        transparency 0.5
      }}
      geometry Box {{
        size 10.0 0.08 0.60
      }}
    }}
  ]
  name "safety_wall_south"
  contactMaterial "wall"
  boundingObject Box {{
    size 10.0 0.08 0.60
  }}
}}

DEF SAFETY_WALL_WEST Solid {{
  translation -5.0 0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.1 0.15 0.2
        roughness 0.8
        transparency 0.5
      }}
      geometry Box {{
        size 0.08 10.0 0.60
      }}
    }}
  ]
  name "safety_wall_west"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.08 10.0 0.60
  }}
}}

DEF SAFETY_WALL_EAST Solid {{
  translation 5.0 0 0.25
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.1 0.15 0.2
        roughness 0.8
        transparency 0.5
      }}
      geometry Box {{
        size 0.08 10.0 0.60
      }}
    }}
  ]
  name "safety_wall_east"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.08 10.0 0.60
  }}
}}

# Perimetro y Estrecho de Salida (orientacion de salida: {exit_wall})
Transform {{
  rotation {exit_rotation}
  children [
    # Plataforma de Escape a Aguas Abiertas (Evita caida al vacio al salir)
    DEF EXIT_PLATFORM Solid {{
  translation 0 1.6 -0.10
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.12 0.22 0.30
        roughness 0.5
        metalness 0.1
      }}
      geometry Box {{
        size 1.20 0.80 0.20
      }}
    }}
  ]
  name "exit_platform"
  contactMaterial "floor"
  boundingObject Box {{
    size 1.20 0.80 0.20
  }}
}}

# Pared Guia Salida Oeste
DEF EXIT_WALL_WEST Solid {{
  translation -0.60 1.6 0.10
  children [
    Shape {{
      appearance DEF WALL_APP PBRAppearance {{
        baseColor 0.8 0.85 0.9
        roughness 0.4
      }}
      geometry Box {{
        size 0.04 0.80 0.20
      }}
    }}
  ]
  name "exit_wall_west"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.04 0.80 0.20
  }}
}}

# Pared Guia Salida Este
DEF EXIT_WALL_EAST Solid {{
  translation 0.60 1.6 0.10
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 0.04 0.80 0.20
      }}
    }}
  ]
  name "exit_wall_east"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.04 0.80 0.20
  }}
}}

# Muro de Contencion Final de Escape (Norte)
DEF EXIT_WALL_NORTH Solid {{
  translation 0 2.0 0.10
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.2 0.7 0.3
        roughness 0.3
      }}
      geometry Box {{
        size 1.24 0.04 0.20
      }}
    }}
  ]
  name "exit_wall_north"
  contactMaterial "wall"
  boundingObject Box {{
    size 1.24 0.04 0.20
  }}
}}

# Muro Sur (2.44m x 0.04m x 0.20m)
DEF WALL_SOUTH Solid {{
  translation 0 -1.2 0.10
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 2.44 0.04 0.20
      }}
    }}
  ]
  name "wall_south"
  contactMaterial "wall"
  boundingObject Box {{
    size 2.44 0.04 0.20
  }}
}}

# Muro Oeste
DEF WALL_WEST Solid {{
  translation -1.2 0 0.10
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 0.04 2.40 0.20
      }}
    }}
  ]
  name "wall_west"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.04 2.40 0.20
  }}
}}

# Muro Este
DEF WALL_EAST Solid {{
  translation 1.2 0 0.10
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 0.04 2.40 0.20
      }}
    }}
  ]
  name "wall_east"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.04 2.40 0.20
  }}
}}

# Muro Norte - Lado Izquierdo de la Salida (1.05m de largo)
DEF WALL_NORTH_LEFT Solid {{
  translation -0.675 1.2 0.10
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 1.05 0.04 0.20
      }}
    }}
  ]
  name "wall_north_left"
  contactMaterial "wall"
  boundingObject Box {{
    size 1.05 0.04 0.20
  }}
}}

# Muro Norte - Lado Derecho de la Salida (1.05m de largo)
DEF WALL_NORTH_RIGHT Solid {{
  translation 0.675 1.2 0.10
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 1.05 0.04 0.20
      }}
    }}
  ]
  name "wall_north_right"
  contactMaterial "wall"
  boundingObject Box {{
    size 1.05 0.04 0.20
  }}
}}

# Chaflanes Guia de Entrada al Estrecho de Salida (Previene enganches laterales)
DEF EXIT_CHAMFER_WEST Solid {{
  translation -0.19 1.15 0.10
  rotation 0 0 1 -0.5236
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 0.10 0.04 0.20
      }}
    }}
  ]
  name "exit_chamfer_west"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.10 0.04 0.20
  }}
}}

DEF EXIT_CHAMFER_EAST Solid {{
  translation 0.19 1.15 0.10
  rotation 0 0 1 0.5236
  children [
    Shape {{
      appearance USE WALL_APP
      geometry Box {{
        size 0.10 0.04 0.20
      }}
    }}
  ]
  name "exit_chamfer_east"
  contactMaterial "wall"
  boundingObject Box {{
    size 0.10 0.04 0.20
  }}
}}

# Indicador visual del Estrecho de Salida (zona segura para escape)
DEF EXIT_STRAIT_MARKER Solid {{
  translation 0 1.25 0.001
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor 0.1 0.9 0.3
        roughness 0.2
        emissiveColor 0.05 0.4 0.15
      }}
      geometry Box {{
        size 0.30 0.10 0.002
      }}
    }}
  ]
  name "exit_strait_marker"
}}
  ]
}}

{render_map_elements(map_name)}

# Robot Autonomo: La Orquita 4WD (Kit Rover Dos Pisos Todomicro)
DEF ORQUITA Robot {{
  translation 0 -0.2 0.001
  rotation 0 0 1 1.5708
  children [
    # 1. Bandeja Inferior de Acrilico con Escotadura Frontal
    # Seccion Trasera (X in [-0.128, +0.048])
    Pose {{
      translation -0.04 0 0.025
      children [
        Shape {{
          appearance DEF ACRYLIC_APP PBRAppearance {{
            baseColor 0.85 0.85 0.90
            transparency 0.35
            roughness 0.1
            metalness 0.05
          }}
          geometry Box {{
            size 0.176 0.150 0.003
          }}
        }}
      ]
    }}
    # Ala Delantera Izquierda
    Pose {{
      translation 0.088 0.050 0.025
      children [
        Shape {{
          appearance USE ACRYLIC_APP
          geometry Box {{
            size 0.080 0.050 0.003
          }}
        }}
      ]
    }}
    # Ala Delantera Derecha
    Pose {{
      translation 0.088 -0.050 0.025
      children [
        Shape {{
          appearance USE ACRYLIC_APP
          geometry Box {{
            size 0.080 0.050 0.003
          }}
        }}
      ]
    }}

    # 2. Bandeja Superior de Acrilico (256mm x 150mm x 3mm)
    Pose {{
      translation 0 0 0.085
      children [
        Shape {{
          appearance USE ACRYLIC_APP
          geometry Box {{
            size 0.256 0.150 0.003
          }}
        }}
      ]
    }}

    # 3. Separadores Metalicos (Standoffs de 60mm entre bandejas)
    DEF STANDOFF_FL Pose {{
      translation 0.11 0.065 0.055
      children [
        DEF STANDOFF_SHAPE Shape {{
          appearance PBRAppearance {{
            baseColor 0.85 0.75 0.2
            metalness 0.9
            roughness 0.2
          }}
          geometry Cylinder {{
            height 0.060
            radius 0.003
          }}
        }}
      ]
    }}
    DEF STANDOFF_FR Pose {{
      translation 0.11 -0.065 0.055
      children [ USE STANDOFF_SHAPE ]
    }}
    DEF STANDOFF_RL Pose {{
      translation -0.11 0.065 0.055
      children [ USE STANDOFF_SHAPE ]
    }}
    DEF STANDOFF_RR Pose {{
      translation -0.11 -0.065 0.055
      children [ USE STANDOFF_SHAPE ]
    }}

    # 4. Modulo Driver L298N
    Pose {{
      translation -0.04 0 0.038
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.8 0.1 0.1
            roughness 0.4
          }}
          geometry Box {{
            size 0.045 0.045 0.022
          }}
        }}
      ]
    }}

    # 5. Pack de Baterias 2x 18650
    Pose {{
      translation 0.02 0 0.036
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.1 0.1 0.1
            roughness 0.5
          }}
          geometry Box {{
            size 0.075 0.042 0.020
          }}
        }}
      ]
    }}

    # 6. Placa Arduino UNO
    Pose {{
      translation -0.02 0 0.094
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.0 0.35 0.65
            roughness 0.3
          }}
          geometry Box {{
            size 0.070 0.055 0.015
          }}
        }}
      ]
    }}

    # 7. Modulo Bluetooth HC-05
    Pose {{
      translation -0.08 0 0.092
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.1 0.5 0.2
            roughness 0.4
          }}
          geometry Box {{
            size 0.028 0.016 0.008
          }}
        }}
      ]
    }}

    # 8. Tolva / V-Funnel Scoop Frontal para Recoleccion de Canicas
    # Guia izquierda ensanchada (Boca ancha X in [0.048, 0.125], Y in [0.022, 0.068])
    Pose {{
      translation 0.086 0.045 0.014
      rotation 0 0 1 -0.5404
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.25 0.25 0.30
            roughness 0.3
          }}
          geometry Box {{
            size 0.090 0.004 0.020
          }}
        }}
      ]
    }}
    # Guia derecha ensanchada
    Pose {{
      translation 0.086 -0.045 0.014
      rotation 0 0 1 0.5404
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.25 0.25 0.30
            roughness 0.3
          }}
          geometry Box {{
            size 0.090 0.004 0.020
          }}
        }}
      ]
    }}
    # Tope Trasero de la Tolva (Backstop a X=0.045)
    Pose {{
      translation 0.045 0 0.014
      children [
        Shape {{
          appearance PBRAppearance {{
            baseColor 0.15 0.15 0.20
            roughness 0.4
          }}
          geometry Box {{
            size 0.006 0.045 0.020
          }}
        }}
      ]
    }}

    # Sensor de Captura Bucal (Recesado a X=0.050, Z=0.014)
    TouchSensor {{
      translation 0.050 0 0.014
      name "capture_sensor"
      boundingObject Box {{
        size 0.008 0.042 0.016
      }}
    }}

    # -------------------------------------------------------------------------
    # TREN DE TRACCION 4WD: 4 MOTORES DC TT INDEPENDIENTES
    # -------------------------------------------------------------------------
    # Motor 1: Delantero Izquierdo
    HingeJoint {{
      jointParameters HingeJointParameters {{
        axis 0 1 0
        anchor 0.075 0.088 0.0325
      }}
      device [
        RotationalMotor {{
          name "front_left_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "front_left_encoder" }}
      ]
      endPoint Solid {{
        translation 0.075 0.088 0.0325
        rotation 1 0 0 1.5708
        children [
          DEF WHEEL_TT Shape {{
            appearance PBRAppearance {{
              baseColor 0.95 0.85 0.05
              roughness 0.6
            }}
            geometry Cylinder {{
              height 0.026
              radius 0.0325
            }}
          }}
        ]
        name "wheel_fl"
        contactMaterial "wheel"
        boundingObject Capsule {{
          height 0.002
          radius 0.0325
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
        anchor -0.075 0.088 0.0325
      }}
      device [
        RotationalMotor {{
          name "rear_left_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "rear_left_encoder" }}
      ]
      endPoint Solid {{
        translation -0.075 0.088 0.0325
        rotation 1 0 0 1.5708
        children [ USE WHEEL_TT ]
        name "wheel_rl"
        contactMaterial "wheel"
        boundingObject Capsule {{
          height 0.002
          radius 0.0325
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
        anchor 0.075 -0.088 0.0325
      }}
      device [
        RotationalMotor {{
          name "front_right_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "front_right_encoder" }}
      ]
      endPoint Solid {{
        translation 0.075 -0.088 0.0325
        rotation 1 0 0 1.5708
        children [ USE WHEEL_TT ]
        name "wheel_fr"
        contactMaterial "wheel"
        boundingObject Capsule {{
          height 0.002
          radius 0.0325
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
        anchor -0.075 -0.088 0.0325
      }}
      device [
        RotationalMotor {{
          name "rear_right_motor"
          maxVelocity 12
        }}
        PositionSensor {{ name "rear_right_encoder" }}
      ]
      endPoint Solid {{
        translation -0.075 -0.088 0.0325
        rotation 1 0 0 1.5708
        children [ USE WHEEL_TT ]
        name "wheel_rr"
        contactMaterial "wheel"
        boundingObject Capsule {{
          height 0.002
          radius 0.0325
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
      # Seccion Trasera Inferior
      Pose {{
        translation -0.04 0 0.025
        children [
          Box {{ size 0.176 0.150 0.003 }}
        ]
      }}
      # Ala Delantera Izquierda
      Pose {{
        translation 0.088 0.050 0.025
        children [
          Box {{ size 0.080 0.050 0.003 }}
        ]
      }}
      # Ala Delantera Derecha
      Pose {{
        translation 0.088 -0.050 0.025
        children [
          Box {{ size 0.080 0.050 0.003 }}
        ]
      }}
      # Bandeja Superior
      Pose {{
        translation 0 0 0.085
        children [
          Box {{ size 0.256 0.150 0.003 }}
        ]
      }}
      # Guia Tolva Izquierda
      Pose {{
        translation 0.086 0.045 0.014
        rotation 0 0 1 -0.5404
        children [
          Box {{ size 0.090 0.004 0.020 }}
        ]
      }}
      # Guia Tolva Derecha
      Pose {{
        translation 0.086 -0.045 0.014
        rotation 0 0 1 0.5404
        children [
          Box {{ size 0.090 0.004 0.020 }}
        ]
      }}
      # Tope Trasero Tolva
      Pose {{
        translation 0.045 0 0.014
        children [
          Box {{ size 0.006 0.045 0.020 }}
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

for filename, controller, map_name, sensor_config, exit_wall in WORLDS:
    with open(os.path.join("keiko-sim", "worlds", filename), "w") as f:
        f.write(generate_world(controller, map_name, sensor_config, exit_wall))

print(f"Se generaron {len(WORLDS)} mundos .wbt exitosamente.")
