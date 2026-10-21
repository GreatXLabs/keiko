#!/usr/bin/env python3
"""
Controlador Autónomo y Visualizador 3D - Proyecto Orquitas (Keiko)
Webots 3D Supervisor Controller (Chasis Rover 4WD Skid-Steer)

Arquitectura de Navegación, Mapeo y Percepción Sensorial Avanzada:
1. Sonar Visualizer Dual:
   - On-Screen 2D Radar HUD en Webots (Dispositivo Display 'sensor_hud'):
     Trazado del chasis, conos acústicos (30°), haces ultrasónicos dinámicos y
     marcadores de presa biológica (Verde Neón) vs obstáculos (Rojo/Ámbar).
   - Radar Polar y Matriz de Haces ASCII en Consola y Archivos de Registro.
2. Captura Automática Frontal Instantánea (Default):
   - Con tan solo tocar o aproximar cualquier parte frontal del chasis (paragolpes,
     ruedas delanteras, tolva o sensores) a la canica (< 26 cm), la presa queda
     asegurada de inmediato a bordo en la bandeja superior.
   - Homing guiado hacia presas en un radio de 70 cm sin rebotes mecánicos.
3. Compatibilidad Modular con Configuraciones de Sensores (A, B, C, D).
4. Cartografía 2D en Tiempo Real (ArenaMapper): Rejilla de ocupación (Occupancy Grid).
5. Barrera Virtual Antiescape: Bloqueo estricto de salida antes de alcanzar 3 canicas.
"""

import sys
import os
import math
from controller import Supervisor

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

# Vincular módulos comunes (cartografía y visualizador de haces)
COMMON_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "common"))
if COMMON_DIR not in sys.path:
    sys.path.insert(0, COMMON_DIR)

from arena_mapper import ArenaMapper
from sonar_visualizer import SonarVisualizer
from ultrasonic_physics import UltrasonicPhysicsModel, SensorBorderFilter

LOG_FILE_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "logs", "simulation_live.log"))
os.makedirs(os.path.dirname(LOG_FILE_PATH), exist_ok=True)

def log_msg(msg):
    print(msg, flush=True)
    try:
        with open(LOG_FILE_PATH, "a") as lf:
            lf.write(msg + "\n")
            lf.flush()
    except Exception:
        pass

# Geometría canónica de salida por pared: (posición del estrecho, yaw de egreso)
EXIT_GEOM = {
    "north": ((0.0, 0.60), math.pi / 2.0),
    "east": ((0.60, 0.0), 0.0),
    "south": ((0.0, -0.60), -math.pi / 2.0),
    "west": ((-0.60, 0.0), math.pi),
}

def detect_exit(supervisor):
    """Detecta la pared de salida de forma dinámica (nodo EXIT_PLATFORM -> env -> nombre de mundo)."""
    ex = ey = None
    node = supervisor.getFromDef("EXIT_PLATFORM")
    if node is not None:
        try:
            pos = node.getPosition()
            if pos is not None and len(pos) >= 2:
                ex, ey = pos[0], pos[1]
        except Exception:
            pass

    if ex is not None and ey is not None:
        if ey > 0.45:
            wall = "north"
        elif ex > 0.45:
            wall = "east"
        elif ey < -0.45:
            wall = "south"
        else:
            wall = "west"
    else:
        wall = os.environ.get("EXIT_WALL", "").strip().lower()
        if wall not in EXIT_GEOM:
            try:
                world_name = os.path.basename(supervisor.getWorldPath()).lower()
            except Exception:
                world_name = ""
            wall = next((w for w in ("east", "south", "west", "north") if w in world_name), "north")

    exit_pos, exit_yaw = EXIT_GEOM[wall]
    return wall, exit_pos, exit_yaw

def main():
    with open(LOG_FILE_PATH, "w") as lf:
        lf.write("=== LOG DE SIMULACION EN VIVO (KEIKO 4WD) ===\n\n")

    robot = Supervisor()
    time_step = int(robot.getBasicTimeStep())
    if time_step <= 0:
        time_step = 32

    log_msg("=" * 78)
    log_msg("  ORQUITAS (KEIKO) - CONTROLADOR AUTÓNOMO 3D & CAPTURA AUTOMÁTICA")
    log_msg(f"  Paso: {time_step} ms | HUD Display 2D: Activo | Mapeador: Occupancy Grid")
    log_msg("  Modo: Captura Automática Frontal Instantánea | Meta: 3 Canicas y Escape")
    log_msg("=" * 78)

    # 1. Detección Dinámica de Sensores Ultrasónicos HC-SR04
    candidate_sensors = [
        "us_low_center", "us_low_left", "us_low_right",
        "us_high_center", "us_high_left", "us_high_right",
        "us_side_left", "us_side_right",
        "us_rear_left", "us_rear_right"
    ]
    sensors = {}
    filtered_distances = {}
    for name in candidate_sensors:
        s = robot.getDevice(name)
        if s is not None:
            s.enable(time_step)
            sensors[name] = s
            filtered_distances[name] = 250.0

    if "us_low_center" in sensors and "us_side_left" in sensors:
        active_config = "trisector_10"
    elif "us_low_center" in sensors:
        active_config = "trisector_front_6"
    elif "us_low_left" in sensors:
        active_config = "dual_plane_8"
    else:
        active_config = "trisector_front_6"

    physics_mode = os.environ.get("ORQUITAS_SENSOR_PHYSICS", "realistic").strip().lower()
    us_physics = UltrasonicPhysicsModel(mode=physics_mode)
    border_filter = SensorBorderFilter(sensor_names=list(sensors.keys()))

    sonar_vis = SonarVisualizer(robot, display_name="sensor_hud", config_name=active_config)
    log_msg(f"[HARDWARE] Configuración sensorial activa: {active_config.upper()} ({len(sensors)} transductores).")
    if sonar_vis.display is not None:
        log_msg("  [HUD] Dispositivo Display 'sensor_hud' (340x340 px) vinculado exitosamente.")


    # 2. Motores DC TT (4WD Skid-Steer)
    motor_names = ["front_left_motor", "rear_left_motor", "front_right_motor", "rear_right_motor"]
    motors = {}
    for m_name in motor_names:
        m = robot.getDevice(m_name)
        if m is not None:
            m.setPosition(float("inf"))
            m.setVelocity(0.0)
            motors[m_name] = m
        else:
            log_msg(f"[WARN] Motor no encontrado: {m_name}")

    def set_drive_velocities(v_left, v_right):
        vl = max(-8.0, min(8.0, v_left))
        vr = max(-8.0, min(8.0, v_right))
        if "front_left_motor" in motors: motors["front_left_motor"].setVelocity(vl)
        if "rear_left_motor" in motors: motors["rear_left_motor"].setVelocity(vl)
        if "front_right_motor" in motors: motors["front_right_motor"].setVelocity(vr)
        if "rear_right_motor" in motors: motors["rear_right_motor"].setVelocity(vr)

    # 3. Referencias del Supervisor para cinemática y canicas
    self_node = robot.getSelf()
    # includeDescendants=True: el chasis es mas angosto que las ruedas; sin los
    # descendientes, los roces laterales rueda-muro no generan contacto y las
    # colisiones nunca se contabilizan.
    self_node.enableContactPointsTracking(time_step, True)
    marbles = {}
    for i in range(1, 4):
        def_name = f"LOBITO_{i}"
        node = robot.getFromDef(def_name)
        if node:
            marbles[def_name] = {
                "node": node,
                "trans_field": node.getField("translation"),
                "captured": False
            }

    # Detección dinámica de la salida (Norte / Este / Sur / Oeste) y director de egreso
    exit_wall, exit_pos, exit_yaw = detect_exit(robot)
    exit_dir = (math.cos(exit_yaw), math.sin(exit_yaw))
    log_msg(f"[HARDWARE] Salida detectada: {exit_wall.upper()} @ ({exit_pos[0]:.2f}, {exit_pos[1]:.2f}) | yaw={exit_yaw:.2f}")

    # Pista de pasillos: activa el modo centrado (sin pivotes) en canales estrechos
    corridor_map = (robot.getFromDef("CORRIDOR_WALL_LEFT") is not None) or (robot.getFromDef("CORRIDOR_WALL_RIGHT") is not None)

    # -------------------------------------------------------------------------
    # Modelo estático de obstáculos (el Supervisor conoce la geometría del mundo).
    # Se fusiona con el sonar: cubre los puntos ciegos de las esquinas que los
    # transductores frontales no ven y que causan impactos laterales.
    # -------------------------------------------------------------------------
    OBSTACLE_DEFS = (
        ["WALL_SOUTH", "OBSTACLE_BOX_1", "OBSTACLE_BOX_2", "OBSTACLE_BOX_3",
         "OBSTACLE_CYLINDER_1", "OBSTACLE_CYLINDER_2",
         "CORRIDOR_WALL_LEFT", "CORRIDOR_WALL_RIGHT"]
        + [f"WALL_ARC_R{i:02d}" for i in range(1, 17)]
        + [f"WALL_ARC_L{i:02d}" for i in range(1, 17)]
    )
    obstacles = []  # (cx, cy, half_x, half_y, yaw)
    for ob_def in OBSTACLE_DEFS:
        node = robot.getFromDef(ob_def)
        if node is None:
            continue
        try:
            pos = node.getPosition()
            orient = node.getOrientation()
            ob_yaw = math.atan2(orient[3], orient[0])
            bo = node.getField("boundingObject").getSFNode()
            if bo is None:
                continue
            tname = bo.getTypeName()
            if tname == "Box":
                size = bo.getField("size").getSFVec3f()
                obstacles.append((pos[0], pos[1], size[0] / 2.0, size[1] / 2.0, ob_yaw))
            elif tname == "Cylinder":
                rad = bo.getField("radius").getSFFloat()
                obstacles.append((pos[0], pos[1], rad, rad, 0.0))
        except Exception:
            pass

    ROBOT_RADIUS = 0.15  # radio efectivo del chasis (incluye ruedas y bandeja)

    def nearest_obstacle(px, py):
        """Distancia firmada y punto más cercano del obstáculo estático más próximo."""
        best_d = 1e9
        bx = by = 0.0
        for (cx, cy, hx, hy, ang) in obstacles:
            dx = px - cx
            dy = py - cy
            ca = math.cos(ang)
            sa = math.sin(ang)
            lx = dx * ca + dy * sa
            ly = -dx * sa + dy * ca
            qx = max(abs(lx) - hx, 0.0)
            qy = max(abs(ly) - hy, 0.0)
            d = math.hypot(qx, qy)
            if d < best_d:
                best_d = d
                nlx = max(-hx, min(hx, lx))
                nly = max(-hy, min(hy, ly))
                bx = cx + nlx * ca - nly * sa
                by = cy + nlx * sa + nly * ca
        return best_d, bx, by

    def clearance_steer(desired_angle, px, py):
        """Desvía el rumbo deseado con repulsión suave del obstáculo más cercano.

        Usa la holgura efectiva (holgura - radio del chasis) para que el cuerpo
        completo, y no solo el centro, rodee el obstáculo.
        """
        d, bx, by = nearest_obstacle(px, py)
        d_eff = d - ROBOT_RADIUS
        if d_eff >= 0.18:
            return desired_angle, d
        rx_ = px - bx
        ry_ = py - by
        n = math.hypot(rx_, ry_)
        if n < 1e-6:
            return desired_angle, d
        rep_angle = math.atan2(ry_ / n, rx_ / n)
        w = max(0.0, min(1.0, (0.18 - d_eff) / 0.18))
        dx = math.cos(desired_angle) * (1.0 - w) + math.cos(rep_angle) * w
        dy = math.sin(desired_angle) * (1.0 - w) + math.sin(rep_angle) * w
        return math.atan2(dy, dx), d

    mapper = ArenaMapper(x_min=-0.95, x_max=0.95, y_min=-0.65, y_max=0.65, res=0.10, exit_pos=exit_pos)

    # Constantes cinemáticas Skid-Steer
    MAX_SPEED = 7.5
    CRUISE_SPEED = 5.5        # Crucero de bahía abierta (pasadas < 240 s)
    TURN_SPEED = 5.2          # Pivote ágil en el sitio
    APPROACH_SPEED = 5.2      # Aproximación veloz hacia la presa
    REVERSE_SPEED = -5.0
    CORRIDOR_SPEED = 6.0      # Traslación centrada en pasillos estrechos (sin pivotes)

    state = "BUSQUEDA"
    captures_count = 0
    collisions_count = 0
    was_colliding = False
    last_collision_time = -1.0
    step_count = 0

    # Control de evasión
    evasion_mode = "NONE"
    evasion_ticks = 0
    evasion_turn_dir = 1.0

    # Watchdog anti-atasco
    stall_check_x = 0.0
    stall_check_y = -0.32
    stall_timer = 0

    # Rotación de presas: deprioritiza temporalmente una canica inalcanzable
    # (deadlock en laberinto) y conmuta a la siguiente libre.
    deprioritized_until = {}
    pursued_id = None
    pursue_start_sec = 0.0

    def nearest_uncaptured_marble(px, py, now=0.0):
        """Canica no capturada mas cercana, ignorando las deprioritizadas vigentes.

        Si todas las libres estan en espera temporal, devuelve la mas cercana igual
        (evita quedarse sin objetivo). Retorna (id, (x, y)) o None.
        """
        best = None
        best_d = float("inf")
        fallback = None
        fallback_d = float("inf")
        for m_id, m_data in marbles.items():
            if m_data["captured"]:
                continue
            try:
                mp = m_data["node"].getPosition()
            except Exception:
                continue
            dd = math.hypot(mp[0] - px, mp[1] - py)
            if dd < fallback_d:
                fallback_d = dd
                fallback = (m_id, (mp[0], mp[1]))
            if deprioritized_until.get(m_id, 0.0) > now:
                continue
            if dd < best_d:
                best_d = dd
                best = (m_id, (mp[0], mp[1]))
        return best if best is not None else fallback

    _init_sel = nearest_uncaptured_marble(0.0, -0.32)
    current_target = _init_sel[1] if _init_sel else exit_pos

    log_msg("[INICIALIZACION] Captura automática frontal lista. Caza a la presa más cercana.\n")

    while robot.step(time_step) != -1:
        step_count += 1
        elapsed_sec = (step_count * time_step) / 1000.0

        # -------------------------------------------------------------
        # 1. LECTURA ACÚSTICA REALISTA HC-SR04 Y FILTRO DE FRONTERA
        # -------------------------------------------------------------
        r_pos = self_node.getPosition()
        rx, ry = r_pos[0], r_pos[1]
        rot = self_node.getOrientation()
        fwd_x = rot[0]
        fwd_y = rot[3]
        yaw = math.atan2(fwd_y, fwd_x)

        active_marbles_xy = []
        for m_data in marbles.values():
            if not m_data["captured"]:
                try:
                    mp = m_data["node"].getPosition()
                    active_marbles_xy.append((mp[0], mp[1]))
                except Exception:
                    pass

        raw_readings = {s_name: dev.getValue() for s_name, dev in sensors.items()}
        realistic_raw = us_physics.apply(raw_readings, robot_pose=(rx, ry, yaw), marble_positions=active_marbles_xy)
        readings = border_filter.update(realistic_raw)

        d_ll = readings.get("us_low_left", 250.0)
        d_lr = readings.get("us_low_right", 250.0)
        d_hl = readings.get("us_high_left", 250.0)
        d_hr = readings.get("us_high_right", 250.0)
        d_lc = readings.get("us_low_center", None)
        d_hc = readings.get("us_high_center", None)
        d_sl = readings.get("us_side_left", 250.0)
        d_sr = readings.get("us_side_right", 250.0)
        d_rl = readings.get("us_rear_left", 250.0)
        d_rr = readings.get("us_rear_right", 250.0)

        delta_l = d_hl - d_ll
        delta_r = d_hr - d_lr
        delta_c = (d_hc - d_lc) if (d_lc is not None and d_hc is not None) else 0.0

        if d_lc is not None:
            min_low = min(d_ll, d_lc, d_lr)
            min_high = min(d_hl, d_hc, d_hr)
        else:
            min_low = min(d_ll, d_lr)
            min_high = min(d_hl, d_hr)
        min_rear = min(d_rl, d_rr)

        # Holgura al obstáculo estático más cercano (modelo del Supervisor)
        clearance, _ob_x, _ob_y = nearest_obstacle(rx, ry)

        # -------------------------------------------------------------
        # 2. ACTUALIZACIÓN DEL MAPA DE OCUPACIÓN
        # -------------------------------------------------------------
        mapper.update(rx, ry, yaw, d_hl, d_hr, d_ll, d_lr)

        # -------------------------------------------------------------
        # 3. WATCHDOG ANTI-ATASCO TEMPORAL
        # -------------------------------------------------------------
        if state not in ("FINALIZACION", "ESCAPE"):
            stall_timer += 1
            if stall_timer >= 60:
                net_dist = math.hypot(rx - stall_check_x, ry - stall_check_y)
                if net_dist < 0.04:
                    evasion_mode = "REVERSE"
                    evasion_ticks = 18
                    evasion_turn_dir = 1.0 if rx < 0 else -1.0
                    log_msg(f"[{elapsed_sec:05.1f}s | DESENGANCHE] Maniobra anti-atasco activa ({net_dist*100:.1f} cm).")
                stall_check_x = rx
                stall_check_y = ry
                stall_timer = 0

        # Rotación forzada de presas: > 7 s persiguiendo la misma canica sin
        # capturarla => deprioritizar 12 s y conmutar a la siguiente libre.
        # Solo si existe otra presa disponible (con una sola, seguirla es lo óptimo).
        if pursued_id is not None:
            if marbles.get(pursued_id, {}).get("captured", False):
                pursued_id = None
            elif (elapsed_sec - pursue_start_sec) > 7.0:
                _alts = [mid for mid, md in marbles.items()
                         if not md["captured"] and mid != pursued_id
                         and deprioritized_until.get(mid, 0.0) <= elapsed_sec]
                if _alts:
                    deprioritized_until[pursued_id] = elapsed_sec + 12.0
                    log_msg(f"[{elapsed_sec:05.1f}s | ROTACION_PRESAS] {pursued_id} deprioritizada 12 s (>7 s sin captura).")
                    pursued_id = None
                    _sel = nearest_uncaptured_marble(rx, ry, elapsed_sec)
                    if _sel:
                        pursued_id = _sel[0]
                        pursue_start_sec = elapsed_sec
                        current_target = _sel[1]
                else:
                    pursue_start_sec = elapsed_sec

        # -------------------------------------------------------------
        # 4. CAPTURA AUTOMÁTICA POR CONTACTO FRONTAL INSTANTÁNEO
        # -------------------------------------------------------------
        for m_id, m_data in marbles.items():
            if not m_data["captured"]:
                try:
                    m_pos = m_data["node"].getPosition()
                except Exception:
                    continue
                rel_x = m_pos[0] - rx
                rel_y = m_pos[1] - ry
                local_fwd = rel_x * fwd_x + rel_y * fwd_y
                local_lat = -rel_x * fwd_y + rel_y * fwd_x
                dist_to_robot = math.hypot(rel_x, rel_y)
                dz = abs(m_pos[2] - r_pos[2])

                # Contacto frontal: tocar con cualquier parte del frente del chasis (paragolpes, ruedas delanteras)
                front_contact = (-0.02 <= local_fwd <= 0.28) and (abs(local_lat) <= 0.18) and (dz <= 0.12)
                dist_contact = (dist_to_robot < 0.26) and (local_fwd > -0.05) and (abs(local_lat) <= 0.16)

                if front_contact or dist_contact:
                    m_data["captured"] = True
                    captures_count += 1

                    # Desaparecer inmediatamente del mapa: teleportar 50 metros bajo tierra y congelar física
                    try:
                        m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                        m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                        m_data["node"].resetPhysics()
                    except Exception:
                        pass

                    log_msg(f"[{elapsed_sec:05.1f}s] CAPTURA VERIFICADA: #{captures_count}")
                    log_msg(f"[{elapsed_sec:05.1f}s | CAPTURA AUTOMÁTICA] ★ Canica {m_id} asegurada y retirada del mapa ({captures_count}/3).")
                    pursued_id = None
                    _sel = nearest_uncaptured_marble(rx, ry, elapsed_sec)
                    current_target = _sel[1] if _sel else exit_pos

                    if captures_count >= 3:
                        state = "ESCAPE"
                        evasion_mode = "NONE"
                        evasion_ticks = 0
                        current_target = exit_pos
                        log_msg(f"[{elapsed_sec:05.1f}s] ESTADO: MODO ESCAPE ACTIVADO")
                        log_msg(f"[{elapsed_sec:05.1f}s | TRANSICIÓN CONDUCTUAL] 3 canicas aseguradas.")
                        log_msg("⚡ CONMUTACIÓN INMEDIATA A MODO ESCAPE HACIA EL ESTRECHO DE SALIDA.\n")
                        state_info = {"state": state, "captures": captures_count, "elapsed": elapsed_sec, "coverage": mapper.get_coverage_percent(), "vl": 0.0, "vr": 0.0}
                        log_msg(sonar_vis.render_ascii(readings, state_info))
                        log_msg(mapper.render_ascii(rx, ry, yaw))

        # Asegurar que las canicas capturadas permanezcan bajo tierra fuera del mapa
        for m_id, m_data in marbles.items():
            if m_data["captured"]:
                try:
                    m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                    m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                except Exception:
                    pass

        # -------------------------------------------------------------
        # 4b. DETECCIÓN DE COLISIONES FÍSICAS (MURO / OBSTÁCULO)
        #     Webots reporta en node_id el cuerpo rastreado (no el ajeno), así
        #     que se filtra por altura del punto de contacto (patrón oficial
        #     obstacle_avoidance): suelo z~0 y canicas z<=0.016 quedan fuera;
        #     muros/obstáculos elevan el contacto por encima de 2 cm.
        # -------------------------------------------------------------
        collision_now = False
        collision_desc = ""
        try:
            for cp in self_node.getContactPoints(True):
                p = cp.getPoint()
                if p[2] <= 0.02:
                    continue
                other = robot.getFromId(cp.getNodeId())
                dname = other.getDef() if other is not None else ""
                collision_now = True
                collision_desc = dname if (dname and dname != "ORQUITA") else "MURO/OBSTACULO"
                break
        except Exception:
            pass

        # Debounce temporal de 0.5 s: las vibraciones de contacto elásticas de ODE
        # no deben duplicar la penalización de una misma colisión.
        if collision_now and not was_colliding and (elapsed_sec - last_collision_time >= 0.5):
            collisions_count += 1
            last_collision_time = elapsed_sec
            log_msg(f"[{elapsed_sec:05.1f}s] COLISIÓN REGISTRADA: -5 pts (impacto #{collisions_count} con {collision_desc})")
        was_colliding = collision_now

        # -------------------------------------------------------------
        # 5. CONDICIÓN TERMINAL DE MISIÓN (CRUCE DE SALIDA EN MODO ESCAPE)
        # -------------------------------------------------------------
        escape_crossed = ((rx - exit_pos[0]) * exit_dir[0] + (ry - exit_pos[1]) * exit_dir[1]) > 0.05
        if state == "ESCAPE" and escape_crossed:
            state = "FINALIZACION"
            set_drive_velocities(0.0, 0.0)
            log_msg("\n" + "=" * 78)
            log_msg("  MISIÓN COMPLETADA CON ÉXITO: BAHÍA ABANDONADA")
            log_msg(f"  Tiempo total: {elapsed_sec:.1f}s | Canicas aseguradas: {captures_count}/3")
            log_msg(f"  Colisiones registradas: {collisions_count} | Cobertura cartográfica: {mapper.get_coverage_percent():.1f}%")
            log_msg(f"  Posición de egreso: [{rx:.2f}, {ry:.2f}] m (Plataforma Exterior)")
            log_msg("=" * 78)
            state_info = {"state": state, "captures": captures_count, "elapsed": elapsed_sec, "coverage": mapper.get_coverage_percent(), "vl": 0.0, "vr": 0.0}
            log_msg(sonar_vis.render_ascii(readings, state_info))
            log_msg(mapper.render_ascii(rx, ry, yaw))
            log_msg("=" * 78 + "\n")
            robot.step(time_step)
            robot.simulationQuit(0)
            break

        # -------------------------------------------------------------
        # 6. DETECCIÓN SENSORIAL Y HOMING A CANICAS
        # -------------------------------------------------------------
        # Detección sensorial por ultrasonido HC-SR04
        if active_config == "trisector_10":
            sonar_marble = (captures_count < 3) and (
                (d_lc is not None and d_lc < 60.0 and delta_c > 5.0) or
                (d_ll < 55.0 and delta_l > 5.0) or
                (d_lr < 55.0 and delta_r > 5.0)
            )
        else:
            sonar_marble = (captures_count < 3) and (min_low < 55.0) and (min_high > (min_low + 5.0))

        # Localización de presa cercana en el campo frontal (< 75 cm)
        nearby_marble = None
        nearby_id = None
        if captures_count < 3:
            for m_id, m_data in marbles.items():
                if not m_data["captured"]:
                    if deprioritized_until.get(m_id, 0.0) > elapsed_sec:
                        continue
                    try:
                        mp = m_data["node"].getPosition()
                    except Exception:
                        continue
                    dx = mp[0] - rx
                    dy = mp[1] - ry
                    lf = dx * fwd_x + dy * fwd_y
                    ll = -dx * fwd_y + dy * fwd_x
                    dist_m = math.hypot(dx, dy)
                    if 0.03 < lf < 0.75 and abs(ll) < 0.45 and dist_m < 0.75:
                        nearby_marble = (dx, dy, lf, ll)
                        nearby_id = m_id
                        break

        marble_detected = (sonar_marble or (nearby_marble is not None)) and (captures_count < 3)

        # Peligro de colisión (margen acústico 16-20 cm + holgura geométrica):
        # inactivo si aproximamos presa, salvo obstáculo frontal a <14 cm.
        emergency_danger = (state != "APROXIMACION") and (not marble_detected) and ((min_high < 18.0) or (min_low < 16.0 and min_high <= (min_low + 6.0)))
        front_too_close = (min_high < 14.0) or (min_low < 12.0 and min_high <= (min_low + 6.0))
        if front_too_close or ((clearance - ROBOT_RADIUS) < 0.02 and state != "ESCAPE"):
            emergency_danger = True
        # En escape, el propio modo ESCAPE gestiona el estrecho: evita bucles de evasión
        if state == "ESCAPE":
            emergency_danger = False
        # Callejón sin salida en esquinas de 90°: gradiente frontal saturado en ambos planos
        corner_dead_end = (not marble_detected) and (min_high < 18.0) and (min_low < 18.0)

        # Barrera antiescape en embudo: bloquea solo el corredor directo de la apertura,
        # dejando libres las presas pegadas a las paredes (sin repulsión infinita).
        ddx_exit = rx - exit_pos[0]
        ddy_exit = ry - exit_pos[1]
        along_exit = ddx_exit * exit_dir[0] + ddy_exit * exit_dir[1]
        cross_exit = -ddx_exit * exit_dir[1] + ddy_exit * exit_dir[0]
        near_exit_barrier = (captures_count < 3) and (along_exit > -0.35) and (abs(cross_exit) < 0.22)

        # -------------------------------------------------------------
        # 7. PRIORIDAD MOTRIZ Y MÁQUINA CONDUCTUAL (FSM JERÁRQUICA)
        # -------------------------------------------------------------
        # 0. MODO PASILLO ESTRECHO: sin pivotes. Con muros a ambos flancos
        #    (< 15 cm) el chasis avanza/retrocede centrado por el canal
        #    (vL = base + balance, vR = base - balance) hasta la salida.
        in_corridor = corridor_map and (d_sl < 15.0) and (d_sr < 15.0)
        if in_corridor:
            # Punto de egreso proyectado más allá del estrecho (evita oscilar en el umbral)
            ex_far = exit_pos[0] + exit_dir[0] * 0.5
            ey_far = exit_pos[1] + exit_dir[1] * 0.5
            if captures_count < 3:
                # Objetivo: presa sin capturar más cercana (aunque quede detrás)
                gx, gy = ex_far - rx, ey_far - ry
                best_d = float("inf")
                for m_data in marbles.values():
                    if m_data["captured"]:
                        continue
                    try:
                        mp = m_data["node"].getPosition()
                    except Exception:
                        continue
                    dd = math.hypot(mp[0] - rx, mp[1] - ry)
                    if dd < best_d:
                        best_d = dd
                        gx, gy = mp[0] - rx, mp[1] - ry
            else:
                gx, gy = ex_far - rx, ey_far - ry

            align = gx * fwd_x + gy * fwd_y
            base = CORRIDOR_SPEED if align >= 0.0 else -CORRIDOR_SPEED
            balance = max(-1.6, min(1.6, (d_sr - d_sl) * 0.05))
            v_l = base + balance
            v_r = base - balance
            action_desc = "PASILLO_CENTRADO_AVANCE" if base > 0 else "PASILLO_CENTRADO_RETROCESO"
            evasion_mode = "NONE"
            evasion_ticks = 0

        # 1. MANIOBRA DE EVASIÓN ACTIVA: RETROCESO (CORTE POR SENSOR TRASERO)
        elif evasion_mode == "REVERSE":
            evasion_ticks -= 1
            action_desc = f"EVASION_RETROCESO ({evasion_ticks})"
            v_l = REVERSE_SPEED
            v_r = REVERSE_SPEED
            if evasion_ticks <= 0 or min_rear < 15.0:
                evasion_mode = "TURN"
                evasion_ticks = 24

        # 2. MANIOBRA DE EVASIÓN ACTIVA: GIRO ENÉRGICO (ININTERRUMPIBLE)
        elif evasion_mode == "TURN":
            evasion_ticks -= 1
            action_desc = f"EVASION_GIRO ({evasion_ticks})"
            if evasion_turn_dir > 0:
                v_l = TURN_SPEED
                v_r = -TURN_SPEED
            else:
                v_l = -TURN_SPEED
                v_r = TURN_SPEED

            if evasion_ticks <= 0:
                # Despeje frontal comprometido: alejarse del vértice antes de retomar
                evasion_mode = "DISENGAGE"
                evasion_ticks = 32

        # 2b. DESPEJE FRONTAL POST-GIRO: avance recto para romper el mínimo local.
        #     Si el frente está bloqueado, sigue rotando en el mismo sentido en vez
        #     de embestir la pared (evita roces al reingresar al obstáculo).
        elif evasion_mode == "DISENGAGE":
            evasion_ticks -= 1
            if min_low < 13.0 or min_high < 15.0 or (clearance - ROBOT_RADIUS) < 0.0:
                action_desc = f"EVASION_DESPEJE_GIRO ({evasion_ticks})"
                if evasion_turn_dir > 0:
                    v_l = TURN_SPEED
                    v_r = -TURN_SPEED
                else:
                    v_l = -TURN_SPEED
                    v_r = TURN_SPEED
            else:
                action_desc = f"EVASION_DESPEJE ({evasion_ticks})"
                v_l = 3.5
                v_r = 3.5
            if evasion_ticks <= 0:
                evasion_mode = "NONE"

        # 3. DISPARO DE EVASIÓN ANTE PELIGRO INMINENTE DE COLISIÓN (< 15 cm)
        elif emergency_danger and evasion_mode == "NONE":
            evasion_mode = "REVERSE"
            evasion_ticks = 20 if corner_dead_end else 14
            evasion_turn_dir = 1.0 if d_sl > d_sr else -1.0
            action_desc = "INICIO_EVASION_RETROCESO"
            v_l = REVERSE_SPEED
            v_r = REVERSE_SPEED

        # 4. BARRERA VIRTUAL DINÁMICA: Mientras captures < 3, repulsión hacia el centro
        #    desde la salida detectada (funciona en cualquier pared).
        elif near_exit_barrier:
            # Dirección de repulsión: opuesta a la salida (hacia el centro de la pista)
            inward_yaw = math.atan2(-exit_dir[1], -exit_dir[0])
            diff_in = (inward_yaw - yaw + math.pi) % (2 * math.pi) - math.pi
            left_dist = min(d_hl, d_ll)
            right_dist = min(d_hr, d_lr)
            obstacle_near = min(min_high, min_low) < 28.0

            if abs(diff_in) > 0.25:
                v_l = -TURN_SPEED if diff_in > 0 else TURN_SPEED
                v_r = TURN_SPEED if diff_in > 0 else -TURN_SPEED
                action_desc = "BARRERA_SALIDA_GIRO_CENTRO"
            elif obstacle_near:
                if left_dist < right_dist:
                    v_l = 3.6
                    v_r = 1.6
                    action_desc = "BARRERA_SALIDA_DESVIO_DER"
                else:
                    v_l = 1.6
                    v_r = 3.6
                    action_desc = "BARRERA_SALIDA_DESVIO_IZQ"
            else:
                v_l = 3.8
                v_r = 3.8
                action_desc = "BARRERA_SALIDA_DESPEJE_CENTRO"
            current_target = (exit_pos[0] - exit_dir[0] * 0.9, exit_pos[1] - exit_dir[1] * 0.9)

        # -------------------------------------------------------------
        # 8. MODO ESCAPE: MÁQUINA DE 3 ETAPAS (APROXIMACIÓN, PREALINEACIÓN, CRUCE)
        #    El estrecho mide 30 cm; el chasis 20 cm. La alineación al eje se
        #    resuelve antes de entrar: giros bruscos dentro del canal barren las
        #    esquinas del chasis contra las paredes.
        # -------------------------------------------------------------
        elif state == "ESCAPE":
            # Coordenadas locales respecto al eje de salida (along<0 = dentro de la bahía)
            ddx = rx - exit_pos[0]
            ddy = ry - exit_pos[1]
            along_exit = ddx * exit_dir[0] + ddy * exit_dir[1]
            cross = -ddx * exit_dir[1] + ddy * exit_dir[0]

            if along_exit < -0.55:
                # (a) APROXIMACIÓN LEJANA: seguimiento de la línea de salida con
                #     lookahead largo y velocidad amortiguada por el error de rumbo.
                #     A velocidad plena el chasis no alcanza a rotar y sobrepasa el
                #     eje; escalar la base por el error evita el sobrepaso.
                desired = exit_yaw - math.atan2(cross, 0.45)
                desired, _ = clearance_steer(desired, rx, ry)
                diff = (desired - yaw + math.pi) % (2 * math.pi) - math.pi

                if (min_low < 18.0 or min_high < 18.0) and evasion_mode == "NONE":
                    evasion_mode = "REVERSE"
                    evasion_ticks = 14
                    evasion_turn_dir = 1.0 if d_sl > d_sr else -1.0
                    v_l = REVERSE_SPEED
                    v_r = REVERSE_SPEED
                    action_desc = "ESCAPE_EVASION_RETROCESO"
                elif abs(diff) > 0.55:
                    if diff > 0:
                        v_l = -TURN_SPEED
                        v_r = TURN_SPEED
                    else:
                        v_l = TURN_SPEED
                        v_r = -TURN_SPEED
                    action_desc = "ESCAPE_ORIENTACION"
                else:
                    base = max(1.5, MAX_SPEED / (1.0 + 2.0 * abs(diff)))
                    v_l = base - diff * 3.0
                    v_r = base + diff * 3.0
                    action_desc = "ESCAPE_APROXIMACION"

            elif along_exit < -0.20:
                # (b) PRE-ALINEACIÓN ANTEPUERTA: centro el chasis sobre el eje ANTES
                #     de encarar el vano. Si el offset lateral es grande se avanza a
                #     velocidad mínima con corrección fuerte (el chaflán sobresale).
                desired = exit_yaw - math.atan2(cross, 0.30)
                err = (desired - yaw + math.pi) % (2 * math.pi) - math.pi
                if abs(cross) < 0.025 and abs(err) < 0.20:
                    steer = max(-2.5, min(2.5, -err * 4.0))
                    base = 2.4
                    action_desc = "ESCAPE_PREALINEACION"
                else:
                    desired = exit_yaw - math.atan2(cross, 0.20)
                    err = (desired - yaw + math.pi) % (2 * math.pi) - math.pi
                    steer = max(-1.6, min(1.6, cross * 6.0 - err * 2.5))
                    base = 1.2
                    action_desc = "ESCAPE_CENTRADO_PUERTA"
                v_l = base + steer
                v_r = base - steer
                evasion_mode = "NONE"

            else:
                # (c) PUERTA DEL ESTRECHO (30 cm de ancho vs 20 cm de chasis):
                #     solo se cruza perfectamente centrado (|cross|<=3.5 cm y
                #     |err|<0.20 rad); de lo contrario se centra a baja velocidad
                #     para no rozar el chaflán lateral.
                desired = exit_yaw - math.atan2(cross, 0.30)
                err = (desired - yaw + math.pi) % (2 * math.pi) - math.pi
                if abs(cross) <= 0.02 and abs(err) < 0.12:
                    # Centrado en el canal: avance recto con micro-corrección
                    # Signo de retroalimentación de offset: con cross>0 (robot a la
                    # izquierda del eje) hay que girar a la derecha (steer>0), por eso
                    # +cross; -err alinea el rumbo con el eje.
                    micro_steer = max(-1.0, min(1.0, cross * 8.0 - err * 2.0))
                    v_l = 2.4 + micro_steer
                    v_r = 2.4 - micro_steer
                    action_desc = "ESCAPE_CRUCE_ESTRECHO"
                    evasion_mode = "NONE"
                else:
                    # Desalineado en el umbral: centrado en el lugar a baja velocidad
                    steer = max(-1.5, min(1.5, cross * 6.0 - err * 2.5))
                    v_l = 1.2 + steer
                    v_r = 1.2 - steer
                    action_desc = "ESCAPE_CENTRADO_PUERTA"

        # -------------------------------------------------------------
        # 9. MODO APROXIMACIÓN DIRECTA A CANICA CON HOMING ACTIVO
        # -------------------------------------------------------------
        elif marble_detected:
            state = "APROXIMACION"
            action_desc = "HOMING_CANICA"

            if nearby_id is not None and nearby_id != pursued_id:
                pursued_id = nearby_id
                pursue_start_sec = elapsed_sec

            if nearby_marble is not None:
                dx, dy, lf, ll = nearby_marble
                target_ang = math.atan2(dy, dx)
                target_ang, _ = clearance_steer(target_ang, rx, ry)
                diff_m = (target_ang - yaw + math.pi) % (2 * math.pi) - math.pi
                steer = max(-1.6, min(1.6, diff_m * 3.8))
            elif active_config == "trisector_10" and d_lc is not None:
                if d_lc < 55.0 and delta_c > 5.0:
                    steer = 0.0
                elif d_ll < 55.0 and delta_l > 5.0:
                    steer = -0.9
                else:
                    steer = 0.9
            else:
                err = d_ll - d_lr
                k_p = 0.040
                steer = max(-1.2, min(1.2, err * k_p))

            v_l = APPROACH_SPEED + steer
            v_r = APPROACH_SPEED - steer

        # -------------------------------------------------------------
        # 10. MODO BÚSQUEDA ACTIVA: CAZA A LA CANICA NO CAPTURADA MÁS CERCANA
        # -------------------------------------------------------------
        else:
            state = "BUSQUEDA"
            action_desc = "CAZA_CANICA_CERCANA"

            _sel = nearest_uncaptured_marble(rx, ry, elapsed_sec)
            if _sel:
                if _sel[0] != pursued_id:
                    pursued_id = _sel[0]
                    pursue_start_sec = elapsed_sec
                current_target = _sel[1]
            else:
                current_target = exit_pos
            tx, ty = current_target
            angle_to_target = math.atan2(ty - ry, tx - rx)
            angle_to_target, _ = clearance_steer(angle_to_target, rx, ry)
            diff = (angle_to_target - yaw + math.pi) % (2 * math.pi) - math.pi

            # Deflexión reactiva suave ante obstáculos a distancia media (15-30 cm)
            obstacle_proximity = not marble_detected and (min_high < 32.0 or (min_low < 24.0 and min_high <= (min_low + 10.0)))
            side_proximity = (d_sl < 18.0) or (d_sr < 18.0)

            if obstacle_proximity:
                left_dist = min(d_hl, d_ll)
                right_dist = min(d_hr, d_lr)
                if left_dist < right_dist:
                    v_l = 3.6
                    v_r = 1.6
                    action_desc = "DESVIO_OBSTACULO_DER"
                else:
                    v_l = 1.6
                    v_r = 3.6
                    action_desc = "DESVIO_OBSTACULO_IZQ"
            elif side_proximity:
                if d_sl < d_sr:
                    v_l = 3.6
                    v_r = 2.2
                    action_desc = "DESVIO_FLANCO_IZQ"
                else:
                    v_l = 2.2
                    v_r = 3.6
                    action_desc = "DESVIO_FLANCO_DER"
            elif abs(diff) > 0.28:
                if diff > 0:
                    v_l = -TURN_SPEED * 0.85
                    v_r = TURN_SPEED * 0.85
                else:
                    v_l = TURN_SPEED * 0.85
                    v_r = -TURN_SPEED * 0.85
            else:
                v_l = CRUISE_SPEED - diff * 3.5
                v_r = CRUISE_SPEED + diff * 3.5

        set_drive_velocities(v_l, v_r)

        # -------------------------------------------------------------
        # 11. ACTUALIZACIÓN GRÁFICA DEL SONAR HUD Y TELEMETRÍA
        # -------------------------------------------------------------
        state_info = {
            "state": state,
            "captures": captures_count,
            "elapsed": elapsed_sec,
            "coverage": mapper.get_coverage_percent(),
            "vl": v_l,
            "vr": v_r
        }

        # Actualización continua del Display HUD 2D en pantalla
        sonar_vis.render_display(readings, state_info)

        # Telemetría cada 15 ciclos (~480 ms)
        if step_count % 15 == 0:
            cov = mapper.get_coverage_percent()
            tx_str = f"[{current_target[0]:.2f},{current_target[1]:.2f}]" if current_target else "N/A"
            log_msg(f"[{elapsed_sec:05.1f}s | {state:12s}] "
                    f"Pos=[{rx:4.2f},{ry:4.2f}] | Target={tx_str} | "
                    f"Mapeo={cov:4.1f}% | "
                    f"Canicas={captures_count}/3 | "
                    f"Low=[{d_ll:4.1f},{d_lr:4.1f}] | "
                    f"Acción={action_desc}")

        # Matriz y Radar de Haces ASCII cada 60 ciclos (~1.92 s)
        if step_count % 60 == 0:
            log_msg("\n" + sonar_vis.render_ascii(readings, state_info))

        # Mini-mapa de Ocupación 2D cada 120 ciclos (~3.84 s)
        if step_count % 120 == 0:
            tx = current_target[0] if current_target else None
            ty = current_target[1] if current_target else None
            log_msg("\n--- [MAPA DE OCUPACIÓN 2D - LA BAHÍA] ---")
            log_msg(mapper.render_ascii(rx, ry, yaw, target_x=tx, target_y=ty))
            log_msg(f"Leyenda: [^ v < >] Robot | [T] Destino | [.] Rastro | [·] Libre | [#] Obstáculo | [E] Salida")
            log_msg("-" * 45 + "\n")

    set_drive_velocities(0.0, 0.0)
    log_msg(f"[{elapsed_sec:05.1f}s | SIMULACION TERMINADA]")

if __name__ == "__main__":
    main()
