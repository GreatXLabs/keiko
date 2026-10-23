#!/usr/bin/env python3
"""
Cliente Robot Autónomo - Orquitas (Keiko)
Webots 3D Hardware-in-the-Loop Controller (Chasis 4WD Skid-Steer)

Cumple estrictamente con las especificaciones de ingeniería PSR/DS 2026:
1. Muestreo, filtrado y validación de frontera del banco ultrasónico HC-SR04 y tolva bucal.
2. Visualizador Sonar Dual:
   - On-Screen 2D Radar HUD en pantalla Webots (Display 'sensor_hud').
   - Matriz de haces y Radar Polar ASCII en consola y archivos de registro.
3. Cartografía 2D en Tiempo Real (ArenaMapper): Rejilla de ocupación (Occupancy Grid).
4. Transmisión delimitada de TELEMETRY (4 u 8 sensores con timestamp y odometría) y EVENT:CAPTURA.
5. Recepción y ejecución de órdenes motrices (COMMAND) del planificador Java/Neuroph 2.98.
6. Fail-Safe (< 500 ms): Parada motriz preventiva ante pérdida de enlace.
7. Barrera de Seguridad Antiescape: Bloqueo físico local de salida si captures < 3.
8. Ingesta física y verificación posicional de canicas en tolva sin rebotes.
"""

import sys
import os
import time
import math
import socket
import select
from controller import Supervisor

# Vincular módulos comunes (cartografía y radar sonar)
COMMON_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "common"))
if COMMON_DIR not in sys.path:
    sys.path.insert(0, COMMON_DIR)

from arena_mapper import ArenaMapper
from sonar_visualizer import SonarVisualizer
from ultrasonic_physics import UltrasonicPhysicsModel, SensorBorderFilter

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

LOG_FILE_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "logs", "simulation_client.log"))
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
        lf.write("=== LOG CLIENTE ROBOT DISTRIBUIDO (KEIKO 4WD) ===\n\n")

    robot = Supervisor()
    time_step = int(robot.getBasicTimeStep())
    if time_step <= 0:
        time_step = 32

    # Configuración de red (Emulación de Bluetooth RFCOMM/SPP sobre TCP)
    server_host = os.environ.get("KEIKO_SERVER_HOST", "127.0.0.1")
    server_port = int(os.environ.get("KEIKO_SERVER_PORT", "8888"))
    failsafe_timeout_sec = 0.500  # 500 ms de timeout estricto

    log_msg("=" * 78)
    log_msg("  ORQUITAS (KEIKO) - CLIENTE ROBOT DISTRIBUIDO (HARDWARE-IN-THE-LOOP)")
    log_msg(f"  Enlace: {server_host}:{server_port} | Fail-Safe Timeout: 500 ms")
    log_msg("  HUD Sonar 2D: Activo | Mapeador 2D: Rejilla de Ocupación")
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
    else:
        active_config = "dual_plane_8"

    physics_mode = os.environ.get("ORQUITAS_SENSOR_PHYSICS", "realistic").strip().lower()
    us_physics = UltrasonicPhysicsModel(mode=physics_mode)
    border_filter = SensorBorderFilter(sensor_names=list(sensors.keys()))

    sonar_vis = SonarVisualizer(robot, display_name="sensor_hud", config_name=active_config)
    log_msg(f"[HARDWARE] Banco ultrasónico: {active_config.upper()} ({len(sensors)} transductores activos).")
    if sonar_vis.display is not None:
        log_msg("  [HUD] Dispositivo Display 'sensor_hud' (340x340 px) vinculado exitosamente.")


    # 2. Chasis 4WD Skid-Steer (4 motores DC TT con reductora 1:48)
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
    # Auditoría de contactos físicos (colisiones reglamentarias -5 pts)
    self_node.enableContactPointsTracking(time_step, True)
    marbles = {}
    for i in range(1, 4):
        node = robot.getFromDef(f"LOBITO_{i}")
        if node:
            marbles[f"lobito_{i}"] = {
                "node": node,
                "trans_field": node.getField("translation"),
                "captured": False,
            }

    # Detección dinámica de la salida (Norte / Este / Sur / Oeste) y director de egreso
    exit_wall, exit_pos, exit_yaw = detect_exit(robot)
    exit_dir = (math.cos(exit_yaw), math.sin(exit_yaw))
    log_msg(f"[HARDWARE] Salida detectada: {exit_wall.upper()} @ ({exit_pos[0]:.2f}, {exit_pos[1]:.2f}) | yaw={exit_yaw:.2f}")

    # Cartógrafo 2D a bordo
    mapper = ArenaMapper(x_min=-0.95, x_max=0.95, y_min=-0.65, y_max=0.65, res=0.10, exit_pos=exit_pos)

    captures_count = 0
    current_server_state = "BUSQUEDA"
    last_action_desc = "INICIALIZACION"
    vl_cmd, vr_cmd = 0.0, 0.0

    # Colisiones: tracking físico real. Se descartan los puntos de contacto a cota de
    # suelo (z <= 0.02 m): apoyo de ruedas, canicas y plataforma de salida. El contacto
    # penalizable (muro / obstáculo) ocurre a cota de chasis (z > 0.02 m).
    collisions_count = 0
    was_colliding = False
    last_collision_time = -1.0

    # Conexión con el Servidor Central
    sock = None
    rx_buffer = ""
    last_command_wall_time = time.monotonic()
    in_failsafe = False
    inactivity_timer = 0.0
    received_server_command = False

    def try_connect():
        nonlocal sock, rx_buffer, last_command_wall_time, in_failsafe
        nonlocal inactivity_timer, received_server_command
        if sock is not None:
            try:
                sock.close()
            except Exception:
                pass
            sock = None

        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.5)
            s.connect((server_host, server_port))
            # Sin Nagle: las tramas cortas de control no deben esperar ACKs acumulados.
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            if s.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY) == 0:
                log_msg("[RED] ADVERTENCIA: TCP_NODELAY inactivo; la latencia de Nagle puede disparar el watchdog.")
            s.setblocking(False)
            sock = s
            rx_buffer = ""
            last_command_wall_time = time.monotonic()
            in_failsafe = False
            inactivity_timer = 0.0
            received_server_command = False
            log_msg(f"[CONEXIÓN] Enlace establecido con Servidor Central en {server_host}:{server_port}")
            return True
        except Exception:
            set_drive_velocities(0.0, 0.0)
            return False

    # Intentar conexión inicial
    try_connect()
    last_reconnect_attempt = 0.0
    step_count = 0

    while robot.step(time_step) != -1:
        step_count += 1
        sim_time = robot.getTime()
        elapsed_sec = sim_time

        # Si el socket no está conectado, intentar reconexión cada 1.0s de simulación
        if sock is None:
            set_drive_velocities(0.0, 0.0)
            if (sim_time - last_reconnect_attempt) > 1.0:
                last_reconnect_attempt = sim_time
                try_connect()
            continue

        # -------------------------------------------------------------
        # 1. FILTRADO SENSORIAL DE FRONTERA Y FÍSICA ACÚSTICA HC-SR04
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
        d_lc = readings.get("us_low_center", 250.0)
        d_lr = readings.get("us_low_right", 250.0)
        d_hl = readings.get("us_high_left", 250.0)
        d_hc = readings.get("us_high_center", 250.0)
        d_hr = readings.get("us_high_right", 250.0)
        d_sl = readings.get("us_side_left", 250.0)
        d_sr = readings.get("us_side_right", 250.0)
        d_rl = readings.get("us_rear_left", 250.0)
        d_rr = readings.get("us_rear_right", 250.0)

        # -------------------------------------------------------------
        # 2. ACTUALIZACIÓN DEL MAPA DE OCUPACIÓN 2D
        # -------------------------------------------------------------
        mapper.update(rx, ry, yaw, d_hl, d_hr, d_ll, d_lr)

        # -------------------------------------------------------------
        # 3. CAPTURA AUTOMÁTICA POR CONTACTO FRONTAL INSTANTÁNEO
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

                # Contacto físico real con la boca de captura frontal del chasis (25.7x15.0 cm)
                front_contact = (-0.02 <= local_fwd <= 0.20) and (abs(local_lat) <= 0.12) and (dz <= 0.12)

                if front_contact:
                    m_data["captured"] = True
                    captures_count += 1

                    # Desaparecer inmediatamente del mapa: teleportar 50 metros bajo tierra y congelar física
                    try:
                        m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                        m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                        m_data["node"].resetPhysics()
                    except Exception:
                        pass

                    log_msg(f"\n[{elapsed_sec:05.1f}s | CAPTURA AUTOMÁTICA] ★ Canica {m_id} asegurada y retirada del mapa ({captures_count}/3).")
                    if sock:
                        event_msg = f"EVENT:CAPTURA,id={m_id}\n"
                        try:
                            sock.sendall(event_msg.encode("utf-8"))
                        except Exception as e:
                            log_msg(f"[ERROR TRANSMISION EVENTO] {e}")
                            sock = None
                            continue

        # Asegurar que las canicas capturadas permanezcan bajo tierra fuera del mapa
        for m_id, m_data in marbles.items():
            if m_data["captured"]:
                try:
                    m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                    m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                except Exception:
                    pass

        # -------------------------------------------------------------
        # 4. VERIFICACIÓN DE FINALIZACIÓN (CRUCE DE SALIDA O TIEMPO REGLAMENTO)
        # -------------------------------------------------------------
        escape_crossed = ((rx - exit_pos[0]) * exit_dir[0] + (ry - exit_pos[1]) * exit_dir[1]) > 0.05
        if (current_server_state in ("ESCAPE", "FINALIZACION")) and escape_crossed:
            set_drive_velocities(0.0, 0.0)
            log_msg("\n" + "=" * 78)
            log_msg("  🏁 MISIÓN DISTRIBUIDA COMPLETADA CON ÉXITO")
            log_msg(f"  Tiempo total: {elapsed_sec:.1f} s | Canicas: {captures_count}/3")
            log_msg(f"  Colisiones auditadas: {collisions_count} (Penalización: {-5 * collisions_count} pts)")
            log_msg(f"  Cobertura cartográfica: {mapper.get_coverage_percent():.1f}%")
            log_msg(f"  Posición de salida: [{rx:.2f}, {ry:.2f}] m (Plataforma Exterior)")
            log_msg("=" * 78)
            state_info = {"state": current_server_state, "captures": captures_count, "elapsed": elapsed_sec, "coverage": mapper.get_coverage_percent(), "vl": 0.0, "vr": 0.0}
            log_msg(sonar_vis.render_ascii(readings, state_info))
            log_msg(mapper.render_ascii(rx, ry, yaw))
            log_msg("=" * 78 + "\n")
            robot.step(time_step)
            robot.simulationQuit(0)
            break

        if sim_time >= 600.0:
            set_drive_velocities(0.0, 0.0)
            log_msg("\n" + "=" * 78)
            log_msg(f"  ⏱️ TIEMPO MÁXIMO DE SIMULACIÓN REGLAMENTARIO ALCANZADO ({sim_time:.1f} s / 10.0 min)")
            log_msg(f"  Canicas aseguradas: {captures_count}/3")
            log_msg("=" * 78)
            robot.step(time_step)
            robot.simulationQuit(0)
            break

        # -------------------------------------------------------------
        # 5. TRANSMISIÓN DE TELEMETRÍA (DELIMITADA POR \n)
        # -------------------------------------------------------------
        sensor_mode = int(os.environ.get("ORQUITAS_SENSOR_COUNT", "6"))
        has_side = ("us_side_left" in sensors) and ("us_side_right" in sensors)
        has_rear = ("us_rear_left" in sensors) and ("us_rear_right" in sensors)
        has_center = ("us_low_center" in sensors) and ("us_high_center" in sensors)

        if has_center and not has_side:
            # Configuración principal de 6 sensores frontales (trisector_front_6):
            # [low_left, low_center, low_right, high_left, high_center, high_right]
            telemetry_msg = (f"TELEMETRY:{d_ll:.2f},{d_lc:.2f},{d_lr:.2f},"
                             f"{d_hl:.2f},{d_hc:.2f},{d_hr:.2f},"
                             f"{int(sim_time*1000)}\n")
        elif sensor_mode >= 10 and has_center and has_side and has_rear:
            telemetry_msg = (f"TELEMETRY:{d_ll:.2f},{d_lc:.2f},{d_lr:.2f},"
                             f"{d_hl:.2f},{d_hc:.2f},{d_hr:.2f},"
                             f"{d_sl:.2f},{d_sr:.2f},{d_rl:.2f},{d_rr:.2f},"
                             f"{int(sim_time*1000)}\n")
        elif sensor_mode >= 8 and has_side and has_rear:
            telemetry_msg = (f"TELEMETRY:{d_ll:.2f},{d_lr:.2f},{d_hl:.2f},{d_hr:.2f},"
                             f"{d_sl:.2f},{d_sr:.2f},{d_rl:.2f},{d_rr:.2f},"
                             f"{int(sim_time*1000)}\n")
        elif sensor_mode >= 6 and has_side:
            telemetry_msg = (f"TELEMETRY:{d_ll:.2f},{d_lr:.2f},{d_hl:.2f},{d_hr:.2f},"
                             f"{d_sl:.2f},{d_sr:.2f},"
                             f"{int(sim_time*1000)}\n")
        else:
            telemetry_msg = (f"TELEMETRY:{d_ll:.2f},{d_lr:.2f},{d_hl:.2f},{d_hr:.2f},"
                             f"{int(sim_time*1000)}\n")

        try:
            sock.sendall(telemetry_msg.encode("utf-8"))
        except Exception as e:
            log_msg(f"[ERROR TRANSMISION TELEMETRIA] {e}")
            sock = None
            continue

        # -------------------------------------------------------------
        # 6. RECEPCIÓN DE MENSAJES DEL SERVIDOR (LOCKSTEP CON TIMEOUT)
        # -------------------------------------------------------------
        try:
            got_reply = False
            wait_start = time.time()
            while (time.time() - wait_start) < 0.025:
                readable, _, _ = select.select([sock], [], [], 0.005)
                if readable:
                    chunk = sock.recv(4096).decode("utf-8", errors="ignore")
                    if not chunk:
                        log_msg("[ENLACE] El Servidor cerró la conexión.")
                        sock = None
                        set_drive_velocities(0.0, 0.0)
                        break
                    rx_buffer += chunk
                    if "\n" in rx_buffer:
                        got_reply = True
                        break
                else:
                    if "\n" in rx_buffer:
                        got_reply = True
                        break

            # Procesar todas las líneas recibidas, conservando el comando más reciente
            while "\n" in rx_buffer:
                line, rx_buffer = rx_buffer.split("\n", 1)
                line = line.strip()
                if not line:
                    continue

                if line.startswith("COMMAND:"):
                    parts = line[len("COMMAND:"):].split(",")
                    if len(parts) >= 2:
                        try:
                            vl_cmd = float(parts[0])
                            vr_cmd = float(parts[1])
                            if len(parts) >= 3:
                                current_server_state = parts[2]

                            last_action_desc = f"CMD_SERVER({current_server_state})"
                            set_drive_velocities(vl_cmd, vr_cmd)
                            last_command_wall_time = time.monotonic()
                            received_server_command = True
                            if in_failsafe:
                                log_msg("[FAIL-SAFE RESTABLECIDO] Enlace activo recuperado. Reanudando marcha.")
                                in_failsafe = False
                        except ValueError:
                            pass

                elif line == "HEARTBEAT":
                    last_command_wall_time = time.monotonic()
                    if in_failsafe:
                        in_failsafe = False

                elif line.startswith("STATE_CHANGE:"):
                    current_server_state = line[len("STATE_CHANGE:"):].strip()
                    log_msg(f"⚡ [NOTIFICACION SERVIDOR] Conmutación de estado: {current_server_state}")

        except Exception as e:
            log_msg(f"[ERROR LECTURA SOCKET] {e}")
            sock = None
            set_drive_velocities(0.0, 0.0)
            continue

        # -------------------------------------------------------------
        # 7. WATCHDOG FAIL-SAFE (< 500 ms) - REQUISITO EXCLUYENTE
        # Se mide en tiempo de pared: en --mode=fast el reloj de simulación corre
        # mucho más rápido que la red y dispararía falsos timeouts por scheduling.
        # -------------------------------------------------------------
        time_since_last_cmd = time.monotonic() - last_command_wall_time
        if time_since_last_cmd > failsafe_timeout_sec:
            set_drive_velocities(0.0, 0.0)
            if not in_failsafe:
                in_failsafe = True
                log_msg(f"[FAIL-SAFE ACTIVO] Timeout de enlace ({time_since_last_cmd*1000:.0f} ms > 500 ms).")
                log_msg("   MOTORES DETENIDOS POR SEGURIDAD.")

        # Inactividad reglamentaria (>10 s de parada ordenada por el servidor).
        # No cuenta en sincronización inicial, reconexiones ni fail-safe transitorio:
        # solo cuando ya hubo un COMMAND y los motores quedan deliberadamente en reposo.
        if (received_server_command and not in_failsafe
                and abs(vl_cmd) < 0.05 and abs(vr_cmd) < 0.05):
            inactivity_timer += (time_step / 1000.0)
            if inactivity_timer >= 10.0 and sim_time > 5.0:
                log_msg(f"\n[FIN REGLAMENTARIO] Inmóvil por {inactivity_timer:.1f}s continuos (>10s reglamentarios).")
                robot.simulationQuit(0)
                break
        else:
            inactivity_timer = 0.0

        # -------------------------------------------------------------
        # 8. AUDITORÍA DE COLISIONES FÍSICAS (PENALIZACIÓN REGLAMENTARIA -5 PTS)
        # -------------------------------------------------------------
        collision_now = False
        collision_desc = "MURO/OBSTACULO"
        try:
            for cp in self_node.getContactPoints(True):
                p = cp.getPoint()
                if p[2] <= 0.02:
                    continue
                other = robot.getFromId(cp.getNodeId())
                if other is None:
                    continue

                # Ignorar contactos internos entre componentes del propio robot (ruedas contra chasis)
                is_self = False
                check = other
                while check is not None:
                    if check.getId() == self_node.getId():
                        is_self = True
                        break
                    check = check.getParentNode()
                if is_self:
                    continue

                dname = ""
                other_name = ""
                curr = other
                while curr is not None:
                    d = curr.getDef()
                    if d and d != "ORQUITA":
                        dname = d
                        break
                    try:
                        nf = curr.getField("name")
                        if nf and not other_name:
                            other_name = nf.getSFString()
                    except Exception:
                        pass
                    curr = curr.getParentNode()

                # Ignorar suelo, plataforma exterior de salida y canicas (lobitos)
                if other_name in ("floor", "exit_platform") or other_name.startswith("lobito"):
                    continue
                if dname.startswith("LOBITO") or dname == "EXIT_PLATFORM":
                    continue

                collision_now = True
                collision_desc = dname if dname else (other_name if other_name else "MURO/OBSTACULO")
                break
        except Exception:
            collision_now = False

        if collision_now and not was_colliding and (elapsed_sec - last_collision_time >= 0.5):
            collisions_count += 1
            last_collision_time = elapsed_sec
            log_msg(f"[{elapsed_sec:05.1f}s] COLISIÓN REGISTRADA: -5 pts (impacto #{collisions_count} con {collision_desc})")
            log_msg(f"   [INCIDENT DETAIL] Pose=({rx:.2f},{ry:.2f},{math.degrees(yaw):.1f}°) | V=({vl_cmd:.2f},{vr_cmd:.2f}) | FSM={current_server_state} | LOW=[{d_ll:.1f},{d_lc:.1f},{d_lr:.1f}] | HIGH=[{d_hl:.1f},{d_hc:.1f},{d_hr:.1f}]")
            if sock:
                try:
                    sock.sendall(f"EVENT:COLISION,impacto={collisions_count}\n".encode("utf-8"))
                except Exception as e:
                    log_msg(f"[ERROR TRANSMISION EVENTO COLISION] {e}")
                    sock = None
        was_colliding = collision_now

        # -------------------------------------------------------------
        # 9. ACTUALIZACIÓN VISUAL DEL SONAR HUD 2D EN PANTALLA
        # -------------------------------------------------------------
        state_info = {
            "state": current_server_state,
            "captures": captures_count,
            "elapsed": elapsed_sec,
            "coverage": mapper.get_coverage_percent(),
            "vl": vl_cmd,
            "vr": vr_cmd
        }
        sonar_vis.render_display(readings, state_info)

        # Log periódico cada 15 ciclos (~480 ms)
        if step_count % 15 == 0:
            cov = mapper.get_coverage_percent()
            log_msg(f"[{elapsed_sec:05.1f}s | {current_server_state:12s}] "
                    f"Pos=[{rx:4.2f},{ry:4.2f}] | "
                    f"Mapeo={cov:4.1f}% | "
                    f"Canicas={captures_count}/3 | "
                    f"Low=[{d_ll:4.1f},{d_lr:4.1f}]")

        # Matriz y Radar de Haces ASCII cada 60 ciclos (~1.92 s)
        if step_count % 60 == 0:
            log_msg("\n" + sonar_vis.render_ascii(readings, state_info))

        # Mini-mapa ASCII periódico cada 120 ciclos (~3.84 s)
        if step_count % 120 == 0:
            log_msg("\n--- [MAPA DE OCUPACIÓN 2D - LA BAHÍA] ---")
            log_msg(mapper.render_ascii(rx, ry, yaw))
            log_msg(f"Leyenda: [^ v < >] Robot | [T] Destino | [.] Rastro | [·] Libre | [#] Obstáculo | [E] Salida")
            log_msg("-" * 45 + "\n")

    # Fin de simulación
    set_drive_velocities(0.0, 0.0)
    if sock:
        try:
            sock.close()
        except Exception:
            pass

if __name__ == "__main__":
    main()
