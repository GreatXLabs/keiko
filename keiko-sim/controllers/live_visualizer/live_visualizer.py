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
from coaxial_neural_planner import CoaxialNeuralClassifier, CoaxialNavigationPlanner

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

    mapper = ArenaMapper(x_min=-0.95, x_max=0.95, y_min=-0.65, y_max=0.65, res=0.10, exit_pos=exit_pos)

    neural_classifier = CoaxialNeuralClassifier()
    neural_planner = CoaxialNavigationPlanner()

    state = "BUSQUEDA"
    captures_count = 0
    collisions_count = 0
    was_colliding = False
    last_collision_time = -1.0
    step_count = 0

    log_msg("[INICIALIZACION] Red Neuronal por Par Coaxial (2->16->4) y Planificador 3-Pares activos (0% GPS).\n")

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
        d_lc = readings.get("us_low_center", 0.5 * (d_ll + d_lr))
        d_hc = readings.get("us_high_center", 0.5 * (d_hl + d_hr))

        # -------------------------------------------------------------
        # 2. ACTUALIZACIÓN DEL MAPA DE OCUPACIÓN (SOLO VISUALIZACIÓN)
        # -------------------------------------------------------------
        mapper.update(rx, ry, yaw, d_hl, d_hr, d_ll, d_lr)

        # -------------------------------------------------------------
        # 3. CAPTURA AUTOMÁTICA POR CONTACTO FÍSICO EN EL FRENTE DEL CHASIS
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
                dz = abs(m_pos[2] - r_pos[2])

                # Contacto físico real con el frente/pala del robot (~20 cm de avance local y +-12 cm lateral)
                front_contact = (-0.02 <= local_fwd <= 0.20) and (abs(local_lat) <= 0.12) and (dz <= 0.12)

                if front_contact:
                    m_data["captured"] = True
                    captures_count += 1

                    try:
                        m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                        m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                        m_data["node"].resetPhysics()
                    except Exception:
                        pass

                    log_msg(f"[{elapsed_sec:05.1f}s] CAPTURA VERIFICADA: #{captures_count}")
                    log_msg(f"[{elapsed_sec:05.1f}s | CAPTURA AUTOMÁTICA] ★ Canica {m_id} asegurada y retirada del mapa ({captures_count}/3).")

                    if captures_count >= 3:
                        neural_planner.activate_escape_mode()
                        state = "ESCAPE"
                        log_msg(f"[{elapsed_sec:05.1f}s] ESTADO: MODO ESCAPE ACTIVADO")
                        log_msg(f"[{elapsed_sec:05.1f}s | TRANSICIÓN CONDUCTUAL] 3 canicas aseguradas.")
                        log_msg("⚡ CONMUTACIÓN INMEDIATA A MODO ESCAPE HACIA EL ESTRECHO DE SALIDA.\n")
                        state_info = {"state": state, "captures": captures_count, "elapsed": elapsed_sec, "coverage": mapper.get_coverage_percent(), "vl": 0.0, "vr": 0.0}
                        log_msg(sonar_vis.render_ascii(readings, state_info))
                        log_msg(mapper.render_ascii(rx, ry, yaw))

        for m_id, m_data in marbles.items():
            if m_data["captured"]:
                try:
                    m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                    m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                except Exception:
                    pass

        # -------------------------------------------------------------
        # 4. DETECCIÓN DE COLISIONES FÍSICAS (MURO / OBSTÁCULO)
        # -------------------------------------------------------------
        collision_now = False
        collision_desc = ""
        try:
            for cp in self_node.getContactPoints(True):
                p = cp.getPoint()
                if p[2] <= 0.02:
                    continue
                other = robot.getFromId(cp.getNodeId())
                dname = ""
                curr = other
                while curr is not None:
                    d = curr.getDef()
                    if d and d != "ORQUITA":
                        dname = d
                        break
                    curr = curr.getParentNode()
                collision_now = True
                collision_desc = dname if dname else "MURO/OBSTACULO"
                break
        except Exception:
            pass

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

        if elapsed_sec >= 600.0:
            set_drive_velocities(0.0, 0.0)
            log_msg("\n" + "=" * 78)
            log_msg(f"  ⏱️ TIEMPO MÁXIMO DE SIMULACIÓN REGLAMENTARIO ALCANZADO ({elapsed_sec:.1f} s / 10.0 min)")
            log_msg(f"  Canicas aseguradas: {captures_count}/3 | Colisiones: {collisions_count}")
            log_msg("=" * 78)
            robot.step(time_step)
            robot.simulationQuit(0)
            break

        # -------------------------------------------------------------
        # 6. INFERENCIA NEURONAL POR PAR COAXIAL [IZQ, CENTRO, DER] Y DECISIÓN
        # -------------------------------------------------------------
        pairs = neural_classifier.classify_3_pairs(readings)
        v_l, v_r, state, action_desc = neural_planner.evaluate(pairs, captures_count=captures_count)
        set_drive_velocities(v_l, v_r)

        # -------------------------------------------------------------
        # 7. ACTUALIZACIÓN GRÁFICA DEL SONAR HUD Y TELEMETRÍA
        # -------------------------------------------------------------
        state_info = {
            "state": state,
            "captures": captures_count,
            "elapsed": elapsed_sec,
            "coverage": mapper.get_coverage_percent(),
            "vl": v_l,
            "vr": v_r
        }

        sonar_vis.render_display(readings, state_info)

        if step_count % 15 == 0:
            cov = mapper.get_coverage_percent()
            log_msg(f"[{elapsed_sec:05.1f}s | {state:12s}] "
                    f"Pos=[{rx:4.2f},{ry:4.2f}] | "
                    f"Pares={pairs} | "
                    f"Mapeo={cov:4.1f}% | "
                    f"Canicas={captures_count}/3 | "
                    f"Low=[{d_ll:4.1f},{d_lc:4.1f},{d_lr:4.1f}] | "
                    f"Acción={action_desc}")

        if step_count % 60 == 0:
            log_msg("\n" + sonar_vis.render_ascii(readings, state_info))

        if step_count % 120 == 0:
            log_msg("\n--- [MAPA DE OCUPACIÓN 2D - LA BAHÍA] ---")
            log_msg(mapper.render_ascii(rx, ry, yaw))
            log_msg("Leyenda: [^ v < >] Robot | [.] Rastro | [·] Libre | [#] Obstáculo | [E] Salida")
            log_msg("-" * 45 + "\n")

    set_drive_velocities(0.0, 0.0)
    log_msg(f"[{elapsed_sec:05.1f}s | SIMULACION TERMINADA]")

if __name__ == "__main__":
    main()
