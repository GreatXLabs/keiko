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
        "exit_x": 0.00,
        "y_back": -0.60,
    },
    "circular": {
        "type": "circle",
        "R": 0.90,         # Radio del círculo (diámetro 1.80 m)
        "yc": -0.10,        # Centro del círculo en Y (-0.10 m)
        "y_back": -0.60,    # Pared plana sur (cuerda que corta el círculo a y = -0.60 m)
        "exit_width": 0.30, # Hueco de salida norte de 30 cm en y = +0.80 m
        "exit_x": 0.00,
    },
    "realistic": {
        "type": "realistic",
        # Basado en la reconstrucción 3D física de la bahía de pruebas del profesor (bahia-keiko.stl)
        "b_left": 0.964,
        "b_right": 1.064,
        "a": 1.15,
        "y_back": -0.62,
        "tilt_deg": 1.2,
        "d_sin2": -0.035,
        "d_sin3": 0.025,
        "d_sin4": -0.015,
        "d_asym": 0.04,
        "exit_x": -0.156,
        "exit_width": 0.30,
    },
}


# Vértices de referencia extraídos directamente de la reconstrucción 3D real bahia-keiko.stl
# Flanco derecho (desde la esquina sur-este hasta el labio derecho de la salida)
STL_RIGHT_PTS = [
    ( 0.690, -0.755), # P0: Esquina trasera derecha (asimétrica a +69 cm)
    ( 0.850, -0.580), # P1: Expansión inicial
    ( 0.950, -0.414), # P2: Encardonado hacia afuera
    ( 1.024, -0.227), # P3: Flanco derecho ancho
    ( 1.058, -0.017), # P4: Punto de máxima holgura (+1.06 m)
    ( 1.008,  0.168), # P5: Curva superior
    ( 0.904,  0.360), # P6: Recorrido hacia puerta
    ( 0.785,  0.558), # P7: Segmento frontal
    ( 0.631,  0.716), # P8: Aproximación a umbral
    ( 0.396,  0.820), # P9: Borde de la salida
    ( 0.123,  0.886), # P10: Labio derecho de la puerta real
]

# Flanco izquierdo (desde el labio izquierdo de la salida hasta la esquina sur-oeste)
STL_LEFT_PTS = [
    (-0.436,  0.821), # Q0: Labio izquierdo de la puerta real
    (-0.584,  0.725), # Q1: Borde de aproximación
    (-0.758,  0.487), # Q2: Curva superior izquierda
    (-0.812,  0.366), # Q3: Segmento de transición
    (-0.870,  0.193), # Q4: Flanco superior
    (-0.896,  0.056), # Q5: Comienzo de la pared recta
    (-0.952, -0.255), # Q6: Tramo plano/recto típico del cartón tensionado
    (-0.956, -0.358), # Q7: Pared vertical plana a x ≈ -0.96m
    (-0.958, -0.488), # Q8: Pared vertical plana
    (-0.957, -0.751), # Q9: Esquina trasera izquierda a x = -0.96m
]

# Curva perimetral completa continua de bahia-keiko.stl (SE a SW pasando por el ápice)
STL_PERIMETER_PTS = list(STL_RIGHT_PTS) + [(-0.150, 0.865)] + list(STL_LEFT_PTS)


def dist_to_segment(px, py, x0, y0, x1, y1):
    """Calcula la distancia euclidiana mínima exacta desde (px, py) al segmento (x0, y0)-(x1, y1)."""
    dx, dy = x1 - x0, y1 - y0
    l2 = dx * dx + dy * dy
    if l2 == 0:
        return math.hypot(px - x0, py - y0)
    t = max(0.0, min(1.0, ((px - x0) * dx + (py - y0) * dy) / l2))
    return math.hypot(px - (x0 + t * dx), py - (y0 + t * dy))


def generate_random_arena_params(variant="realistic", rng=None):
    """
    Genera parámetros de bahía aleatorios con imperfecciones humanas e irregularidades orgánicas:
    - Morfología continua: desde elipse alargada (w_stl ~ 0.1) hasta la forma real del STL (w_stl ~ 0.95).
    - Arrugas y quiebres físicos del cartón corrugado (dientes/abolladuras de hasta +/- 7 cm).
    - Alargamiento real en profundidad (a = 1.15 a 1.45 m), evitando semicírculos matemáticos.
    - Salida desplazada del centro (exit_x en rango [-0.20, +0.20] m).
    - Asimetría entre flanco izquierdo y derecho e inclinación de pared de fondo.
    """
    if rng is None:
        rng = random.Random()
    if variant == "circular":
        R = round(rng.uniform(0.88, 0.95), 3)
        yc = round(rng.uniform(-0.12, -0.08), 3)
        y_back = round(rng.uniform(-0.64, -0.58), 3)
        exit_x = round(rng.uniform(-0.45, 0.45), 3)
        return {
            "type": "circle",
            "R": R,
            "yc": yc,
            "y_back": y_back,
            "exit_width": 0.30,
            "exit_x": exit_x,
            "tilt_deg": round(rng.uniform(-1.5, 1.5), 2),
        }
    elif variant == "standard":
        return dict(ARENA_PROFILES["standard"])
    else:
        # Variante 'realistic' orgánica pero más suave (menos irregularidades y sin dientes agudos):
        w_stl = round(rng.uniform(0.15, 0.80), 3)
        b_left = round(rng.uniform(0.92, 1.05), 3)
        b_right = round(rng.uniform(0.92, 1.05), 3)
        a = round(rng.uniform(1.15, 1.38), 3)  # Profundidad elíptica natural
        y_back = round(rng.uniform(-0.66, -0.58), 3)
        tilt_deg = round(rng.uniform(-1.8, 1.8), 2)
        # Salida en posiciones mucho más aleatorias del cartón (desde el flanco izquierdo al derecho):
        exit_x = round(rng.uniform(-0.52, 0.52), 3)
        
        # Deformaciones suaves del cartón (ondulaciones naturales en lugar de pliegues profundos):
        wrinkles = []
        for _ in range(rng.randint(1, 2)):
            wrinkles.append({
                "th": round(rng.uniform(0.30, math.pi - 0.30), 3),
                "amp": round(rng.uniform(-0.025, 0.025), 3),
                "sig": round(rng.uniform(0.35, 0.65), 3),
            })
            
        return {
            "type": "realistic",
            "w_stl": w_stl,
            "b_left": b_left,
            "b_right": b_right,
            "a": a,
            "y_back": y_back,
            "tilt_deg": tilt_deg,
            "exit_x": exit_x,
            "exit_width": 0.30,
            "wrinkles": wrinkles,
            "jitter": round(rng.uniform(0.008, 0.015), 3),
        }

# Configuraciones de escenarios de evaluación (14 escenarios fijos + circular + aleatorio reglamentario)
# La pared plana del fondo está en Y = -0.60 m y el vértice de salida en (0.0, +0.60 m) o (0.0, +0.80 m).
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
    "circular": {
        "variant": "circular",
        "marbles": [
            ("lobito_1", -0.62, 0.15),
            ("lobito_2", 0.15, 0.38),
            ("lobito_3", 0.20, -0.40),
        ],
        "boxes": [
            ("OBSTACLE_BOX_1", "obstacle_3d_1", -0.22, 0.14, 0.03, 0.06, 0.06, 0.06, 0.20, "0.85 0.35 0.15"),
            ("OBSTACLE_BOX_2", "obstacle_3d_2", 0.28, -0.10, 0.03, 0.06, 0.06, 0.06, -0.40, "0.25 0.45 0.80"),
        ],
        "spawn": (-0.62, -0.44, 1.5708),
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


def generate_random_scenario_config(variant="realistic", seed=None, obstacle_shape=None, iteration=None):
    """
    Genera una configuración aleatoria 100% reglamentaria y libre de atascos:
    - Sometida a la geometría seleccionada: 'realistic' (imperfecciones de bahia-keiko.stl),
      'circular' (redonda con imperfecciones) o 'standard'.
    - Variación aleatoria amplia de la posición de la salida en el cartón (exit_x en rango [-0.52, +0.52] m).
    - Canicas lejos de muros (margen >= 18 cm), lejos de obstáculos (>= 0.25 m),
      lejos entre sí (>= 0.28 m) y lejos del spawn (>= 0.35 m).
    - Obstáculos de 6x6 cm que alternan entre cubos y cilindros en cada iteración.
    """
    rng = random.Random(seed)

    if variant in ("standard", "circular") and seed is not None:
        arena_params = generate_random_arena_params(variant=variant, rng=rng)
    elif variant in ("realistic", "profesor") or variant not in ARENA_PROFILES:
        arena_params = generate_random_arena_params(variant="realistic", rng=rng)
    else:
        arena_params = generate_random_arena_params(variant=variant, rng=rng)

    geom = get_arena_geometry(arena_params)
    exit_x, exit_y = geom["exit_pos"]
    exit_normal = geom.get("exit_normal", (0.0, 1.0))
    bw = geom["back_wall"]

    # Extraer todos los segmentos de pared para comprobación euclidiana exacta
    wall_segs = []
    for s in geom["arc_segments"]:
        wall_segs.append((s["x0"], s["y0"], s["x1"], s["y1"]))
    wall_segs.append((bw["x0"], bw["y0"], bw["x1"], bw["y1"]))

    y_min_bound = min(bw["y0"], bw["y1"]) + 0.10
    y_max_bound = max(max(s["y0"], s["y1"]) for s in geom["arc_segments"]) + 0.05

    def inside_safe(px, py, min_clr=0.18):
        d = min(dist_to_segment(px, py, x0, y0, x1, y1) for x0, y0, x1, y1 in wall_segs)
        if d < min_clr:
            return False
        if py < y_min_bound or py > y_max_bound:
            return False
        return True

    # Spawn seguro en el cuadrante suroeste (South-West)
    if arena_params.get("type") == "circle":
        sx, sy, syaw = -0.62, -0.44, 1.5708
    else:
        b_left = arena_params.get("b_left", arena_params.get("b", 0.96))
        y_back = arena_params.get("y_back", -0.62)
        sx = round(-b_left + 0.24, 2)
        sy = round(y_back + 0.20, 2)
        syaw = 1.5708

    # Selección de forma de obstáculos: alternar cilindro / cubo iteración de por medio
    if obstacle_shape in ("cylinder", "cylindrical"):
        is_cylinder = True
    elif obstacle_shape in ("box", "cubo"):
        is_cylinder = False
    elif iteration is not None:
        # Iteración de por medio: impares cajas, pares cilindros
        is_cylinder = (iteration % 2 == 0)
    else:
        is_cylinder = (rng.random() < 0.5)

    # Rangos de muestreo
    box_x_range = (-0.50, 0.50)
    box_y_range = (-0.30, 0.35)
    marble_x_range = (-0.65, 0.65)
    marble_y_range = (-0.40, 0.45)

    boxes = []
    colors = ["0.85 0.35 0.15", "0.25 0.45 0.80"]
    for idx in range(2):
        for _ in range(1500):
            bx = round(rng.uniform(*box_x_range), 2)
            by = round(rng.uniform(*box_y_range), 2)
            if not inside_safe(bx, by, min_clr=0.20):
                continue
            # Despejar pasillo directo y embocadura a la salida (radio 32 cm de la puerta)
            if math.hypot(bx - exit_x, by - exit_y) < 0.32:
                continue
            if math.hypot(bx - sx, by - sy) < 0.35:
                continue
            if any(math.hypot(bx - ox, by - oy) < 0.28 for _, _, ox, oy, *_ in boxes):
                continue
            byaw = round(rng.uniform(-0.80, 0.80), 2)
            if is_cylinder:
                def_id = f"OBSTACLE_CYLINDER_{idx + 1}"
                name = f"obstacle_cylinder_{idx + 1}"
                shape_type = "cylinder"
            else:
                def_id = f"OBSTACLE_BOX_{idx + 1}"
                name = f"obstacle_3d_{idx + 1}"
                shape_type = "box"

            boxes.append((
                def_id,
                name,
                bx, by, 0.03, 0.06, 0.06, 0.06, byaw, colors[idx], shape_type
            ))
            break

    marbles = []
    for idx in range(3):
        for _ in range(2500):
            mx = round(rng.uniform(*marble_x_range), 2)
            my = round(rng.uniform(*marble_y_range), 2)
            if not inside_safe(mx, my, min_clr=0.18):
                continue
            # Lejos de la posición de inicio del auto (mínimo 35 cm)
            if math.hypot(mx - sx, my - sy) < 0.35:
                continue
            # Lejos del pasillo de escape y embocadura a la puerta (mínimo 30 cm)
            if math.hypot(mx - exit_x, my - exit_y) < 0.30:
                continue
            # Lejos de obstáculos (mínimo 25 cm al centro de la caja o cilindro)
            if any(math.hypot(mx - ox, my - oy) < 0.25 for _, _, ox, oy, *_ in boxes):
                continue
            # Lejos entre sí (mínimo 28 cm)
            if any(math.hypot(mx - omx, my - omy) < 0.28 for _, omx, omy in marbles):
                continue
            marbles.append((f"lobito_{idx + 1}", mx, my))
            break

    return {
        "variant": variant,
        "arena_params": arena_params,
        "marbles": marbles,
        "boxes": boxes,
        "spawn": (sx, sy, syaw),
        "exit_pos": (exit_x, exit_y),
        "exit_normal": exit_normal,
        "obstacle_shape": "cylinder" if is_cylinder else "box",
    }


def get_arena_geometry(variant="standard"):
    if isinstance(variant, dict):
        prof = variant
        var_name = prof.get("variant", prof.get("type", "realistic"))
    else:
        prof = ARENA_PROFILES.get(variant, ARENA_PROFILES["standard"])
        var_name = variant

    wall_thickness = 0.005  # 5 mm
    wall_height = 0.18      # 18 cm
    exit_width = prof.get("exit_width", 0.30)
    exit_x = prof.get("exit_x", 0.0)
    half_gap = exit_width / 2.0

    if prof.get("type") == "circle" or var_name == "circular":
        R = prof.get("R", 0.90)
        yc = prof.get("yc", -0.10)
        y_back = prof.get("y_back", -0.60)
        tilt_deg = prof.get("tilt_deg", 0.0)

        sin_cut = (y_back - yc) / R
        alpha = math.asin(max(-0.999, min(0.999, sin_cut)))
        theta_SE = alpha
        theta_SW = math.pi - alpha

        cos_gap_r = (exit_x + half_gap) / R
        cos_gap_l = (exit_x - half_gap) / R
        theta_exit_right = math.acos(min(0.999, max(-0.999, cos_gap_r)))
        theta_exit_left = math.acos(min(0.999, max(-0.999, cos_gap_l)))

        def point_on_circle(theta):
            return (R * math.cos(theta), yc + R * math.sin(theta))

        n_half_segments = 16
        right_pts = [point_on_circle(theta_SE + (theta_exit_right - theta_SE) * (i / n_half_segments)) for i in range(n_half_segments + 1)]
        left_pts = [point_on_circle(theta_exit_left + (theta_SW - theta_exit_left) * (i / n_half_segments)) for i in range(n_half_segments + 1)]

        segments = []
        for i in range(n_half_segments):
            x0, y0 = right_pts[i]
            x1, y1 = right_pts[i + 1]
            segments.append({
                "def": f"WALL_ARC_R{i+1:02d}",
                "name": f"wall_arc_r{i+1:02d}",
                "cx": 0.5 * (x0 + x1), "cy": 0.5 * (y0 + y1),
                "length": math.hypot(x1 - x0, y1 - y0) + 0.002,
                "yaw": math.atan2(y1 - y0, x1 - x0),
                "x0": x0, "y0": y0, "x1": x1, "y1": y1,
            })

        for i in range(n_half_segments):
            x0, y0 = left_pts[i]
            x1, y1 = left_pts[i + 1]
            segments.append({
                "def": f"WALL_ARC_L{i+1:02d}",
                "name": f"wall_arc_l{i+1:02d}",
                "cx": 0.5 * (x0 + x1), "cy": 0.5 * (y0 + y1),
                "length": math.hypot(x1 - x0, y1 - y0) + 0.002,
                "yaw": math.atan2(y1 - y0, x1 - x0),
                "x0": x0, "y0": y0, "x1": x1, "y1": y1,
            })

        chord_half_width = R * math.cos(alpha)
        back_wall_length = 2.0 * chord_half_width + 0.01

        gap_r = right_pts[-1]
        gap_l = left_pts[0]
        actual_gap = math.hypot(gap_r[0] - gap_l[0], gap_r[1] - gap_l[1])
        exit_pos = (0.5 * (gap_r[0] + gap_l[0]), 0.5 * (gap_r[1] + gap_l[1]))
        vx, vy = gap_l[0] - gap_r[0], gap_l[1] - gap_r[1]
        vn = math.hypot(vx, vy)
        exit_normal = (vy / vn, -vx / vn) if vn > 1e-4 else (0.0, 1.0)

        tilt_rad = math.radians(tilt_deg)
        return {
            "variant": var_name,
            "b": R,
            "a": R,
            "y_back": y_back,
            "exit_pos": exit_pos,
            "exit_normal": exit_normal,
            "wall_thickness": wall_thickness,
            "wall_height": wall_height,
            "exit_width": exit_width,
            "actual_exit_gap_m": actual_gap,
            "area_m2": 2.12,
            "back_wall": {
                "def": "WALL_SOUTH",
                "name": "wall_back",
                "cx": 0.0,
                "cy": y_back,
                "length": back_wall_length,
                "yaw": tilt_rad,
                "x0": -chord_half_width, "y0": y_back,
                "x1": chord_half_width, "y1": y_back,
            },
            "arc_segments": segments,
        }

    # Geometría Orgánica e Irregular (Interpolación Elipse <-> bahia-keiko.stl con arrugas de cartón)
    w_stl = prof.get("w_stl", 0.50)
    b_left = prof.get("b_left", prof.get("b", 0.96))
    b_right = prof.get("b_right", prof.get("b", 1.06))
    a = prof.get("a", 1.25)
    y_back = prof.get("y_back", -0.62)
    tilt_deg = prof.get("tilt_deg", 0.0)
    tilt_rad = math.radians(tilt_deg)
    y_apex = y_back + a
    wrinkles = prof.get("wrinkles", [])
    jitter = prof.get("jitter", 0.03)

    # Ancho reglamentario de la puerta (30 cm libres netos)
    # Usamos half_gap = 0.155 m (31.0 cm de eje a eje de pared) para que con el grosor
    # de 5 mm de cartón la luz libre interior sea exactamente >= 30.0 cm en cualquier ángulo.
    exit_width = prof.get("exit_width", 0.30)
    exit_x = prof.get("exit_x", 0.0)
    half_gap = max(0.155, exit_width / 2.0)

    rng_local = random.Random(int(abs(b_left * 1000 + b_right * 100 + a * 10)) % 100000)

    def apply_wrinkles(th):
        dr = 0.0
        for w in wrinkles:
            th_w, amp_w, sig_w = w["th"], w["amp"], w["sig"]
            dr += amp_w * math.exp(-((th - th_w) ** 2) / (2.0 * sig_w ** 2))
        return dr

    def get_stl_pt(u):
        u = max(0.0, min(1.0, u))
        s_idx = u * (len(STL_PERIMETER_PTS) - 1)
        i0 = int(s_idx)
        i1 = min(i0 + 1, len(STL_PERIMETER_PTS) - 1)
        f = s_idx - i0
        x_raw = (1.0 - f) * STL_PERIMETER_PTS[i0][0] + f * STL_PERIMETER_PTS[i1][0]
        y_raw = (1.0 - f) * STL_PERIMETER_PTS[i0][1] + f * STL_PERIMETER_PTS[i1][1]
        scale_x = (b_right / 1.058) if x_raw >= 0 else (b_left / 0.958)
        xs = x_raw * scale_x
        ys = y_back + (y_raw - (-0.755)) * (a / 1.64)
        return xs, ys

    def get_curve_pt(th):
        u = th / math.pi
        xs, ys = get_stl_pt(u)
        if th <= math.pi * 0.5:
            xe = b_right * math.cos(th)
            ye = y_back + a * math.sin(th)
        else:
            xe = -b_left * math.sin(th - math.pi * 0.5)
            ye = y_back + a * math.cos(th - math.pi * 0.5)

        x = (1.0 - w_stl) * xe + w_stl * xs
        y = (1.0 - w_stl) * ye + w_stl * ys

        # Atenuación gaussiana estricta en el corredor de salida:
        # En la boca de la puerta (|x - exit_x| < 0.22m) las arrugas y el jitter se anulan a 0.0
        # para que los marcos de la puerta sean limpios, perpendiculares y con luz libre exacta >= 30 cm.
        dist_to_door = abs(x - exit_x)
        if dist_to_door < 0.22:
            att = max(0.0, (dist_to_door - 0.08) / 0.14) ** 2
        else:
            att = 1.0

        if att > 1e-4:
            dr = (apply_wrinkles(th) + rng_local.uniform(-jitter, jitter)) * att
            ang = math.atan2(y - y_back, x)
            x += dr * math.cos(ang)
            y += dr * math.sin(ang)

        return x, y

    def find_theta_for_x(target_x):
        lo, hi = 0.0, math.pi
        for _ in range(40):
            mid = 0.5 * (lo + hi)
            x, _ = get_curve_pt(mid)
            if x > target_x:
                lo = mid
            else:
                hi = mid
        return 0.5 * (lo + hi)

    th_door_r = find_theta_for_x(exit_x + half_gap)
    th_door_l = find_theta_for_x(exit_x - half_gap)

    n_half_segments = 16
    right_pts = []
    for i in range(n_half_segments + 1):
        th = th_door_r * (i / float(n_half_segments))
        x, y = get_curve_pt(th)
        right_pts.append([x, y])

    left_pts = []
    for i in range(n_half_segments + 1):
        th = th_door_l + (math.pi - th_door_l) * (i / float(n_half_segments))
        x, y = get_curve_pt(th)
        left_pts.append([x, y])

    # Fijar con precisión absoluta los labios exteriores de la puerta
    right_pts[-1][0] = exit_x + half_gap
    left_pts[0][0] = exit_x - half_gap

    # Inclinación / desalineación de esquinas de la pared plana sur
    right_pts[0][1] += 0.5 * b_right * math.tan(tilt_rad)
    left_pts[-1][1] -= 0.5 * b_left * math.tan(tilt_rad)

    segments = []
    for i in range(n_half_segments):
        x0, y0 = right_pts[i]
        x1, y1 = right_pts[i + 1]
        segments.append({
            "def": f"WALL_ARC_R{i+1:02d}",
            "name": f"wall_arc_r{i+1:02d}",
            "cx": 0.5 * (x0 + x1),
            "cy": 0.5 * (y0 + y1),
            "length": math.hypot(x1 - x0, y1 - y0) + 0.002,
            "yaw": math.atan2(y1 - y0, x1 - x0),
            "x0": x0, "y0": y0, "x1": x1, "y1": y1,
        })

    for i in range(n_half_segments):
        x0, y0 = left_pts[i]
        x1, y1 = left_pts[i + 1]
        segments.append({
            "def": f"WALL_ARC_L{i+1:02d}",
            "name": f"wall_arc_l{i+1:02d}",
            "cx": 0.5 * (x0 + x1),
            "cy": 0.5 * (y0 + y1),
            "length": math.hypot(x1 - x0, y1 - y0) + 0.002,
            "yaw": math.atan2(y1 - y0, x1 - x0),
            "x0": x0, "y0": y0, "x1": x1, "y1": y1,
        })

    bw_x0, bw_y0 = left_pts[-1]
    bw_x1, bw_y1 = right_pts[0]
    bw_len = math.hypot(bw_x1 - bw_x0, bw_y1 - bw_y0) + 0.01
    bw_cx = 0.5 * (bw_x0 + bw_x1)
    bw_cy = 0.5 * (bw_y0 + bw_y1)
    bw_yaw = math.atan2(bw_y1 - bw_y0, bw_x1 - bw_x0)

    # Cálculo exacto de área por fórmula Shoelace
    poly_pts = right_pts + left_pts
    area = 0.0
    for i in range(len(poly_pts)):
        p0 = poly_pts[i]
        p1 = poly_pts[(i + 1) % len(poly_pts)]
        area += (p0[0] * p1[1] - p1[0] * p0[1])
    area = 0.5 * abs(area)

    gap_r = right_pts[-1]
    gap_l = left_pts[0]
    actual_gap = math.hypot(gap_r[0] - gap_l[0], gap_r[1] - gap_l[1])
    exit_pos = (0.5 * (gap_r[0] + gap_l[0]), 0.5 * (gap_r[1] + gap_l[1]))
    vx, vy = gap_l[0] - gap_r[0], gap_l[1] - gap_r[1]
    vn = math.hypot(vx, vy)
    exit_normal = (vy / vn, -vx / vn) if vn > 1e-4 else (0.0, 1.0)

    return {
        "variant": var_name,
        "w_stl": w_stl,
        "b": 0.5 * (b_left + b_right),
        "a": a,
        "y_back": y_back,
        "exit_pos": exit_pos,
        "exit_normal": exit_normal,
        "wall_thickness": wall_thickness,
        "wall_height": wall_height,
        "exit_width": exit_width,
        "actual_exit_gap_m": actual_gap,
        "area_m2": area,
        "back_wall": {
            "def": "WALL_SOUTH",
            "name": "wall_back",
            "cx": bw_cx,
            "cy": bw_cy,
            "length": bw_len,
            "yaw": bw_yaw,
            "x0": bw_x0, "y0": bw_y0,
            "x1": bw_x1, "y1": bw_y1,
        },
        "arc_segments": segments,
    }


def render_arena_walls(map_name="standard", exit_wall="north", variant=None):
    if variant is None:
        cfg = MAP_CONFIGS.get(map_name, {})
        variant = cfg.get("arena_params", cfg.get("variant", "circular" if "circular" in map_name else "standard"))
    geom = get_arena_geometry(variant)
    exit_rotation = "0 0 1 0" if exit_wall == "north" else ("0 0 1 -1.5708" if exit_wall == "east" else "0 0 1 3.1416")
    t = geom["wall_thickness"]
    h = geom["wall_height"]
    z_center = h / 2.0
    bw = geom["back_wall"]
    bw_yaw = bw.get("yaw", 0.0)
    bw_rot_str = f"rotation 0 0 1 {bw_yaw:.5f}\n      " if abs(bw_yaw) > 1e-4 else ""

    nodes = []
    nodes.append(f"""    # Pared Plana de Base (~1.80m sobre la pared del laboratorio)
    DEF {bw['def']} Solid {{
      translation {bw['cx']:.4f} {bw['cy']:.4f} {z_center:.4f}
      {bw_rot_str}children [
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

    ex, ey = geom["exit_pos"]
    nx, ny = geom.get("exit_normal", (0.0, 1.0))
    plat_x = ex + 0.35 * nx
    plat_y = ey + 0.35 * ny
    nodes.append(f"""    # Marcador de Referencia de Salida (30 cm)
    DEF EXIT_PLATFORM Solid {{
      translation {plat_x:.4f} {plat_y:.4f} -0.10
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

    # Renderizar exactamente 2 obstáculos 3D (cubos o cilindros de 6x6x6 cm)
    for ob in cfg.get("boxes", []):
        def_id = ob[0]
        name = ob[1]
        x, y, z, sx, sy, sz, yaw, color = ob[2:10]
        shape_type = ob[10] if len(ob) > 10 else ("cylinder" if "CYLINDER" in def_id.upper() else "box")
        if shape_type == "cylinder":
            radius = sx / 2.0
            lines.append(f"""# Obstaculo 3D Cilindrico (diametro {sx*100:.0f}cm, alto {sz*100:.0f}cm): {name}
DEF {def_id} Solid {{
  translation {x} {y} {z}
  rotation 0 0 1 {yaw}
  children [
    Shape {{
      appearance PBRAppearance {{
        baseColor {color}
        roughness 0.6
      }}
      geometry Cylinder {{
        height {sz}
        radius {radius:.4f}
      }}
    }}
  ]
  name "{name}"
  contactMaterial "wall"
  boundingObject Cylinder {{
    height {sz}
    radius {radius:.4f}
  }}
}}""")
        else:
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
    ("bahia_de_caza_circular.wbt", "live_visualizer", "circular", "north"),
    ("bahia_de_caza_circular_client.wbt", "robot_client", "circular", "north"),
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


def build_random_world(variant="realistic", include_capote=None, seed=None, iteration=None, obstacle_shape=None):
    worlds_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "worlds")
    os.makedirs(worlds_dir, exist_ok=True)
    cfg = generate_random_scenario_config(variant=variant, seed=seed, iteration=iteration, obstacle_shape=obstacle_shape)
    MAP_CONFIGS["scen_random"] = cfg
    out_path = os.path.join(worlds_dir, "bahia_de_caza_scen_random.wbt")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(generate_world("robot_client", "scen_random", "north", include_capote=include_capote))
    sx, sy, syaw = cfg["spawn"]
    ex, ey = cfg.get("exit_pos", (0.0, 0.60))
    ap = cfg.get("arena_params", {})
    tilt = ap.get("tilt_deg", 0.0)
    w_stl = ap.get("w_stl", 0.5)
    a_depth = ap.get("a", 1.25)
    obs_shape = cfg.get("obstacle_shape", "box")
    print(f"🎲 Escenario aleatorio generado [{variant.upper()}]: {out_path}")
    print(f"   Morfología   : {w_stl*100:.0f}% STL Profesor / {(1.0-w_stl)*100:.0f}% Elipse Alargada (Profundidad a={a_depth:.2f}m)")
    print(f"   Salida Puerta: ({ex:+.2f}, {ey:+.2f}) m (desplazamiento X: {ex:+.2f} m)")
    print(f"   Inclinación  : {tilt:+.1f}° | Asimetría: b_L={ap.get('b_left', 0.90):.2f}m, b_R={ap.get('b_right', 0.90):.2f}m")
    print(f"   Spawn Robot  : ({sx:+.2f}, {sy:+.2f}) m, yaw={math.degrees(syaw):.1f}°")
    print(f"   Obstáculos   : 2 unidades de 6x6 cm con forma [{obs_shape.upper()}]")
    for ob in cfg["boxes"]:
        name = ob[1]
        bx, by = ob[2], ob[3]
        print(f"   • {name} en ({bx:+.2f}, {by:+.2f}) m")
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
    target_variant = "circular" if ("--circular" in sys.argv or "--variant=circular" in sys.argv) else ("standard" if ("--standard" in sys.argv or "--variant=standard" in sys.argv) else "realistic")
    if "--random" in sys.argv:
        build_random_world(variant=target_variant, include_capote=use_capote)
    else:
        build_all_worlds(include_capote=use_capote)
        build_random_world(variant=target_variant, include_capote=use_capote)

