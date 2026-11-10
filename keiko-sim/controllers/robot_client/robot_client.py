#!/usr/bin/env python3
"""
Cliente Robot Autónomo - Orquitas (Keiko)
Webots 3D Hardware-in-the-Loop Controller (Chasis 4WD Skid-Steer)
Nueva configuración de hardware:
- Fila superior: 3 sensores ultrasónicos HC-SR04 fijos a 5.5 cm de altura (yaw=0°, pitch=0°).
- Fila inferior: 2 sensores láser ToF VL53L0X sobre 2 servos SG90 barriendo en espejo.
- Hueco bucal del picker central de exactamente 7.0 cm libres (|Y| <= 0.035 m).
"""

import sys
import os
import time
import math
import socket
import select
from controller import Supervisor

COMMON_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "common"))
if COMMON_DIR not in sys.path:
    sys.path.insert(0, COMMON_DIR)

from arena_mapper import ArenaMapper
from collision_audit import find_penalizable_contact, is_new_impact
from sonar_visualizer import SonarVisualizer
from ultrasonic_physics import UltrasonicPhysicsModel, SensorBorderFilter
from laser_physics import LaserPhysicsModel
from dataset_recorder import SimulationDatasetRecorder

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

EXIT_GEOM = {
    "north": ((0.0, 0.60), math.pi / 2.0),
    "east": ((0.60, 0.0), 0.0),
    "south": ((0.0, -0.60), -math.pi / 2.0),
    "west": ((-0.60, 0.0), math.pi),
}

def detect_exit(supervisor):
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
        # Recuperar vector normal y posición de la puerta (a 0.35 m hacia adentro desde la plataforma)
        dx = ex - 0.0
        dy = ey - (-0.60)
        dist = math.hypot(dx, dy)
        if dist > 1e-4:
            nx = dx / dist
            ny = dy / dist
        else:
            nx, ny = 0.0, 1.0
        door_x = ex - 0.35 * nx
        door_y = ey - 0.35 * ny
        exit_pos = (float(door_x), float(door_y))
        exit_yaw = math.atan2(ny, nx)
        wall = "north" if (exit_yaw > 0.78 and exit_yaw < 2.35) else ("east" if abs(exit_yaw) <= 0.78 else ("south" if exit_yaw <= -0.78 and exit_yaw >= -2.35 else "west"))
        return wall, exit_pos, exit_yaw
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
        lf.write("=== LOG CLIENTE ROBOT DISTRIBUIDO (KEIKO 3US + 2LASER) ===\n\n")

    robot = Supervisor()
    time_step = int(robot.getBasicTimeStep())
    if time_step <= 0:
        time_step = 32

    server_host = os.environ.get("KEIKO_SERVER_HOST", "127.0.0.1")
    server_port = int(os.environ.get("KEIKO_SERVER_PORT", "8888"))
    failsafe_timeout_sec = 0.500  # 500 ms watchdog estricto

    log_msg("=" * 78)
    log_msg("  ORQUITAS (KEIKO) - CLIENTE ROBOT DISTRIBUIDO (3 US + 2 LASER/SERVO)")
    log_msg(f"  Enlace: {server_host}:{server_port} | Fail-Safe Timeout: 500 ms")
    log_msg("  Picker: 7.0 cm libres | Sensores: 3 HC-SR04 fijos + 2 VL53L0X en servos SG90")
    log_msg("=" * 78)

    # 1. Sensores Ultrasónicos HC-SR04 (Fila superior: Izquierda, Centro, Derecha)
    us_device_names = ["us_left", "us_center", "us_right"]
    us_sensors = {}
    for name in us_device_names:
        dev = robot.getDevice(name)
        if dev is not None:
            dev.enable(time_step)
            us_sensors[name] = dev
        else:
            log_msg(f"[WARN] Sensor ultrasónico no encontrado: {name}")

    # 2. Sensores Láser VL53L0X y Servos SG90 (Fila inferior)
    laser_sensors = {}
    for name in ["laser_left", "laser_right"]:
        dev = robot.getDevice(name)
        if dev is not None:
            dev.enable(time_step)
            laser_sensors[name] = dev
        else:
            log_msg(f"[WARN] Sensor láser no encontrado: {name}")

    servo_motors = {}
    servo_sensors = {}
    for name in ["servo_left", "servo_right"]:
        m = robot.getDevice(name)
        if m is not None:
            m.setVelocity(18.0)
            servo_motors[name] = m
        s = robot.getDevice(f"{name}_sensor")
        if s is not None:
            s.enable(time_step)
            servo_sensors[name] = s

    # 3. Modelos Físicos de Percepción
    physics_mode = os.environ.get("ORQUITAS_SENSOR_PHYSICS", "realistic").strip().lower()
    us_physics = UltrasonicPhysicsModel(mode=physics_mode)
    laser_physics = LaserPhysicsModel(mode=physics_mode)
    border_filter = SensorBorderFilter(sensor_names=list(us_sensors.keys()))
    record_ds_env = os.environ.get("ORQUITAS_RECORD_DATASET", "1").strip().lower()
    dataset_recorder = SimulationDatasetRecorder(enabled=(record_ds_env not in ("0", "false", "no")))

    sonar_vis = SonarVisualizer(robot, display_name="sensor_hud", config_name="sim_3us_2laser")
    log_msg(f"[HARDWARE] Sensores activos: {len(us_sensors)} ultrasonidos, {len(laser_sensors)} láseres.")

    # 4. Chasis 4WD Skid-Steer (4 motores DC TT)
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

    # 5. Entidades de Simulación
    self_node = robot.getSelf()
    self_node.enableContactPointsTracking(time_step, False)
    marbles = {}
    for i in range(1, 4):
        node = robot.getFromDef(f"LOBITO_{i}")
        if node:
            marbles[f"lobito_{i}"] = {
                "idx": i,
                "node": node,
                "trans_field": node.getField("translation"),
                "captured": False,
            }

    obstacle_nodes = []
    for i in (1, 2):
        node = robot.getFromDef(f"OBSTACLE_BOX_{i}") or robot.getFromDef(f"OBSTACLE_CYLINDER_{i}")
        if node:
            obstacle_nodes.append(node)

    exit_wall, exit_pos, exit_yaw = detect_exit(robot)
    exit_dir = (math.cos(exit_yaw), math.sin(exit_yaw))
    log_msg(f"[HARDWARE] Salida detectada: {exit_wall.upper()} @ ({exit_pos[0]:.2f}, {exit_pos[1]:.2f}) | yaw={exit_yaw:.2f}")

    mapper = ArenaMapper(x_min=-1.15, x_max=1.15, y_min=-0.80, y_max=max(0.70, exit_pos[1] + 0.20), res=0.10, exit_pos=exit_pos)

    # 6. Máquina de Barrido de Radar por Tics en Espejo
    # Barrido fino tic a tic (5.0° por unidad) desde -20° (boca central despejada) hasta +35° (flancos laterales)
    sweep_step_ticks = int(os.environ.get("ORQUITAS_RADAR_TICKS", os.environ.get("ORQUITAS_SWEEP_TICKS", "1")))
    sweep_deg_step = float(os.environ.get("ORQUITAS_RADAR_STEP_DEG", os.environ.get("ORQUITAS_SWEEP_STEP_DEG", "5.0")))
    sweep_min_deg = float(os.environ.get("ORQUITAS_RADAR_MIN_DEG", os.environ.get("ORQUITAS_SWEEP_MIN_DEG", "-15.0")))
    sweep_max_deg = float(os.environ.get("ORQUITAS_RADAR_MAX_DEG", os.environ.get("ORQUITAS_SWEEP_MAX_DEG", "35.0")))

    radar_full_angles = []
    a = sweep_min_deg
    while a <= sweep_max_deg + 1e-5:
        radar_full_angles.append(round(a, 1))
        a += sweep_deg_step
    if radar_full_angles[-1] < sweep_max_deg - 1e-5:
        radar_full_angles.append(round(sweep_max_deg, 1))

    approach_binocular_angles = [-12.0, -6.0, 0.0, 6.0, 12.0]
    escape_guard_angles = [-10.0, 0.0, 10.0, 20.0]

    sweep_angles = list(radar_full_angles)
    sweep_idx = 0
    sweep_dir = 1  # +1 aumentando, -1 disminuyendo
    current_servo_deg = sweep_angles[0]
    ticks_in_current_step = 0

    captures_count = 0
    current_server_state = "BUSQUEDA"
    vl_cmd, vr_cmd = 0.0, 0.0
    collisions_count = 0
    was_colliding = False

    sock = None
    rx_buffer = ""
    last_command_wall_time = time.monotonic()
    in_failsafe = False
    inactivity_timer = 0.0

    def try_connect():
        nonlocal sock, rx_buffer, last_command_wall_time, in_failsafe
        if sock is not None:
            try: sock.close()
            except Exception: pass
            sock = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.5)
            s.connect((server_host, server_port))
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.setblocking(False)
            sock = s
            rx_buffer = ""
            last_command_wall_time = time.monotonic()
            in_failsafe = False
            log_msg(f"[CONEXIÓN] Enlace establecido con Servidor Central en {server_host}:{server_port}")
            try:
                init_event = f"EVENT:SALIDA_META:{exit_pos[0]:.2f},{exit_pos[1]:.2f}\n"
                s.sendall(init_event.encode("utf-8"))
            except Exception:
                pass
            return True
        except Exception:
            set_drive_velocities(0.0, 0.0)
            return False

    try_connect()
    last_reconnect_attempt = 0.0
    step_count = 0

    while robot.step(time_step) != -1:
        step_count += 1
        sim_time = robot.getTime()
        elapsed_sec = sim_time

        if sock is None:
            set_drive_velocities(0.0, 0.0)
            if (sim_time - last_reconnect_attempt) > 1.0:
                last_reconnect_attempt = sim_time
                try_connect()
            continue

        # -----------------------------------------------------------------
        # 1. CONTROL DE BARRIDO DE SERVOS POR TICS (RADAR + BINOCULAR + LATERAL)
        # -----------------------------------------------------------------
        ticks_in_current_step += 1
        if ticks_in_current_step >= sweep_step_ticks:
            ticks_in_current_step = 0
            if current_server_state == "APROXIMACION":
                target_table = approach_binocular_angles
            elif current_server_state == "ESCAPE":
                target_table = escape_guard_angles
            else:
                target_table = radar_full_angles

            if sweep_angles != target_table:
                # Reenganchar en el tic más cercano al ángulo actual para no saltar brusco
                closest_i = min(range(len(target_table)), key=lambda i: abs(target_table[i] - current_servo_deg))
                sweep_angles = list(target_table)
                sweep_idx = closest_i

            max_idx = len(sweep_angles) - 1
            sweep_idx += sweep_dir
            if sweep_idx >= max_idx:
                sweep_idx = max_idx
                sweep_dir = -1
            elif sweep_idx <= 0:
                sweep_idx = 0
                sweep_dir = 1
            current_servo_deg = sweep_angles[sweep_idx]

            # Comandar servos en espejo
            target_rad = math.radians(current_servo_deg)
            if "servo_left" in servo_motors:
                servo_motors["servo_left"].setPosition(target_rad)
            if "servo_right" in servo_motors:
                # Espejado: positivo para afuera, negativo hacia el centro
                servo_motors["servo_right"].setPosition(-target_rad)

        # -----------------------------------------------------------------
        # 2. LECTURA Y MODELADO FÍSICO DE SENSORES
        # -----------------------------------------------------------------
        r_pos = self_node.getPosition()
        rx, ry = r_pos[0], r_pos[1]
        rot = self_node.getOrientation()
        fwd_x = rot[0]
        fwd_y = rot[3]
        if math.isnan(rx) or math.isnan(ry) or math.isnan(fwd_x) or math.isnan(fwd_y):
            continue
        yaw = math.atan2(fwd_y, fwd_x)

        active_marbles_xy = []
        for m_data in marbles.values():
            if not m_data["captured"]:
                try:
                    mp = m_data["node"].getPosition()
                    active_marbles_xy.append((mp[0], mp[1]))
                except Exception:
                    pass

        active_obstacles_xy = []
        for onode in obstacle_nodes:
            try:
                op = onode.getPosition()
                active_obstacles_xy.append((op[0], op[1]))
            except Exception:
                pass

        # 2a. Ultrasonidos (3 HC-SR04 fijos a 5.5 cm con física acústica y cono 30°)
        raw_us = {name: dev.getValue() for name, dev in us_sensors.items()}
        realistic_us = us_physics.apply(raw_us, (rx, ry, yaw), active_obstacles_xy)
        filtered_us = border_filter.update(realistic_us)
        d_us_izq = filtered_us.get("us_left", 250.0)
        d_us_cen = filtered_us.get("us_center", 250.0)
        d_us_der = filtered_us.get("us_right", 250.0)

        # 2b. Láseres ToF VL53L0X sobre servos
        servo_rad = math.radians(current_servo_deg)
        # Chip izquierdo en coordenadas del mundo
        lx_l, ly_l, lyaw_l = laser_physics.get_chip_pose_local("left", servo_rad)
        chip_l_world = (
            rx + lx_l * math.cos(yaw) - ly_l * math.sin(yaw),
            ry + lx_l * math.sin(yaw) + ly_l * math.cos(yaw),
            yaw + lyaw_l
        )
        # Chip derecho en coordenadas del mundo
        lx_r, ly_r, lyaw_r = laser_physics.get_chip_pose_local("right", servo_rad)
        chip_r_world = (
            rx + lx_r * math.cos(yaw) - ly_r * math.sin(yaw),
            ry + lx_r * math.sin(yaw) + ly_r * math.cos(yaw),
            yaw + lyaw_r
        )

        raw_laser_l = laser_sensors["laser_left"].getValue() if "laser_left" in laser_sensors else 200.0
        raw_laser_r = laser_sensors["laser_right"].getValue() if "laser_right" in laser_sensors else 200.0

        d_laser_izq = laser_physics.compute_reading(raw_laser_l, chip_l_world, active_marbles_xy)
        d_laser_der = laser_physics.compute_reading(raw_laser_r, chip_r_world, active_marbles_xy)

        # Actualizar mapa de ocupación
        mapper.update(rx, ry, yaw, d_us_izq, d_us_der, d_us_cen, d_us_cen)

        # Grabar muestras para Dataset V2 (Simulación Dinámica)
        if dataset_recorder is not None:
            dataset_recorder.record_step(
                us_readings=filtered_us,
                laser_left=d_laser_izq,
                laser_right=d_laser_der,
                servo_deg=current_servo_deg,
                robot_pose=(rx, ry, yaw),
                active_marbles=active_marbles_xy,
                active_obstacles=active_obstacles_xy,
                exit_pos=exit_pos,
                exit_yaw=exit_yaw,
            )

        # -----------------------------------------------------------------
        # 3. CAPTURA FÍSICA ESTRICTA POR HUECO DE 7.0 CM (|Y| <= 0.035 m)
        # -----------------------------------------------------------------
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
                dist_m = math.hypot(rel_x, rel_y)

                if dist_m < 0.35 and (int(elapsed_sec * 100) % 20 < 4):
                    log_msg(f"[{elapsed_sec:05.1f}s | PROX CANICA] {m_id}: d={dist_m*100:.1f}cm, fwd={local_fwd*100:.1f}cm, lat={local_lat*100:.1f}cm, mZ={m_pos[2]:.3f}, rZ={r_pos[2]:.3f}")

                # Condición de entrada al picker (7.0 cm libres):
                # Frente del paragolpes en X = +0.1325 m. Radio de canica r = 0.008 m.
                # Al cruzar el plano de la boca: local_fwd <= 0.144 m y |local_lat| <= 0.038 m.
                if (0.040 <= local_fwd <= 0.144) and (abs(local_lat) <= 0.038) and (m_pos[2] <= 0.05):
                    m_data["captured"] = True
                    captures_count += 1
                    idx = m_data["idx"]

                    # Desaparecer inmediatamente la canica de la arena
                    try:
                        m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                        m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                        m_data["node"].resetPhysics()
                    except Exception:
                        pass

                    log_msg(f"\n[{elapsed_sec:05.1f}s | CAPTURA PICKER 7.0cm] ★ Canica {m_id} asegurada ({captures_count}/3).")
                    if sock:
                        event_msg = f"EVENT:CAPTURA_LOBITO_{idx}\n"
                        try:
                            sock.sendall(event_msg.encode("utf-8"))
                        except Exception as e:
                            log_msg(f"[ERROR EVENTO] {e}")
                            sock = None
                            continue

        # Asegurar congelamiento bajo tierra
        for m_data in marbles.values():
            if m_data["captured"]:
                try:
                    m_data["trans_field"].setSFVec3f([0.0, 0.0, -50.0])
                    m_data["node"].setVelocity([0.0, 0.0, 0.0, 0.0, 0.0, 0.0])
                except Exception:
                    pass

        dist_to_door = math.hypot(rx - exit_pos[0], ry - exit_pos[1])
        in_doorway = dist_to_door <= 0.38
        escape_crossed = in_doorway and (((rx - exit_pos[0]) * exit_dir[0] + (ry - exit_pos[1]) * exit_dir[1]) > 0.04)
        if (current_server_state in ("ESCAPE", "FINALIZACION")) and escape_crossed:
            set_drive_velocities(0.0, 0.0)
            log_msg("\n" + "=" * 78)
            log_msg("  🏁 MISIÓN DISTRIBUIDA COMPLETADA CON ÉXITO")
            log_msg(f"  Tiempo total: {elapsed_sec:.1f} s | Canicas: {captures_count}/3")
            log_msg(f"  Colisiones auditadas: {collisions_count}")
            log_msg(f"  Posición de salida: [{rx:.2f}, {ry:.2f}] m")
            log_msg("=" * 78)
            if os.environ.get("ORQUITAS_AUTO_QUIT", "0") == "1":
                robot.step(time_step)
                robot.simulationQuit(0)
                break
        elif escape_crossed and current_server_state in ("BUSQUEDA", "APROXIMACION"):
            set_drive_velocities(0.0, 0.0)
            log_msg("\n" + "=" * 78)
            log_msg(f"  ❌ [ERROR CRÍTICO] El robot salió de la bahía en estado {current_server_state} sin Panza Llena ({captures_count}/3 canicas)")
            log_msg(f"  Posición de cruce indebido: [{rx:.2f}, {ry:.2f}] m")
            log_msg("=" * 78)
            if os.environ.get("ORQUITAS_AUTO_QUIT", "0") == "1":
                robot.step(time_step)
                robot.simulationQuit(1)
                break

        sim_timeout = float(os.environ.get("ORQUITAS_SIM_TIMEOUT", "420.0"))
        if sim_time >= sim_timeout:
            set_drive_velocities(0.0, 0.0)
            log_msg(f"\n⏱️ TIEMPO MÁXIMO ALCANZADO ({sim_time:.1f} s)")
            log_msg(f"  Tiempo total: {sim_time:.1f} s | Canicas: {captures_count}/3")
            if os.environ.get("ORQUITAS_AUTO_QUIT", "0") == "1":
                robot.step(time_step)
                robot.simulationQuit(0)
                break

        # -----------------------------------------------------------------
        # 5. TRANSMISIÓN DE TELEMETRÍA (6 SENSORES + TS + 3 POSE = 10 CAMPOS)
        # -----------------------------------------------------------------
        if sim_time < 0.15:
            robot.step(time_step)
            continue

        telemetry_msg = (
            f"TELEMETRY:{d_us_izq:.2f},{d_us_cen:.2f},{d_us_der:.2f},"
            f"{d_laser_izq:.2f},{d_laser_der:.2f},{current_servo_deg:.1f},"
            f"{int(sim_time*1000)},{rx:.3f},{ry:.3f},{yaw:.3f}\n"
        )
        try:
            sock.sendall(telemetry_msg.encode("utf-8"))
        except Exception as e:
            log_msg(f"[ERROR TELEMETRIA] {e}")
            sock = None
            continue

        # -----------------------------------------------------------------
        # 6. RECEPCIÓN DE COMANDOS DEL SERVIDOR (LOCKSTEP CON TIMEOUT)
        # -----------------------------------------------------------------
        try:
            wait_start = time.time()
            while (time.time() - wait_start) < 0.025:
                readable, _, _ = select.select([sock], [], [], 0.005)
                if readable:
                    chunk = sock.recv(4096).decode("utf-8", errors="ignore")
                    if not chunk:
                        log_msg("[ENLACE] Servidor cerró conexión.")
                        sock = None
                        set_drive_velocities(0.0, 0.0)
                        break
                    rx_buffer += chunk
                    if "\n" in rx_buffer:
                        break
                else:
                    if "\n" in rx_buffer:
                        break

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
                                current_server_state = parts[2].strip()
                            last_command_wall_time = time.monotonic()
                            in_failsafe = False
                        except ValueError:
                            pass
                elif line.startswith("STATE_CHANGE:"):
                    new_state = line[len("STATE_CHANGE:"):].strip()
                    current_server_state = new_state
                    log_msg(f"[{elapsed_sec:05.1f}s | EVENTO DE RED] Transición a estado: {new_state}")
                elif line == "HEARTBEAT":
                    last_command_wall_time = time.monotonic()
                    in_failsafe = False

        except Exception as e:
            log_msg(f"[ERROR RECEPCIÓN] {e}")
            sock = None
            set_drive_velocities(0.0, 0.0)
            continue

        # -----------------------------------------------------------------
        # 7. FAIL-SAFE (< 500 ms) Y APLICACIÓN MOTRIZ
        # -----------------------------------------------------------------
        time_since_last_cmd = time.monotonic() - last_command_wall_time
        if time_since_last_cmd > failsafe_timeout_sec:
            if not in_failsafe:
                in_failsafe = True
                log_msg(f"[{elapsed_sec:05.1f}s | FAIL-SAFE] Timeout de enlace ({time_since_last_cmd*1000:.0f} ms > 500 ms). Deteniendo motores.")
            set_drive_velocities(0.0, 0.0)
        else:
            set_drive_velocities(vl_cmd, vr_cmd)

        if step_count % 47 == 0:
            log_msg(f"[{elapsed_sec:05.1f}s] pos=({rx:.2f}, {ry:.2f}, yaw={yaw:.2f}), state={current_server_state}, caps={captures_count}/3, cmd=({vl_cmd:.1f}, {vr_cmd:.1f}), US=[{d_us_izq:.1f}, {d_us_cen:.1f}, {d_us_der:.1f}], L=[{d_laser_izq:.1f}, {d_laser_der:.1f}, {current_servo_deg:.0f}°]")

        # -----------------------------------------------------------------
        # 8. AUDITORÍA DE COLISIONES FÍSICAS (PENALIZACIÓN REGLAMENTARIA -5 PTS)
        # -----------------------------------------------------------------
        collision_point = find_penalizable_contact(
            (cp.getPoint() for cp in self_node.getContactPoints(False)),
            active_marbles_xy,
            (rx, ry),
            exit_dir,
        )
        collision_now = collision_point is not None
        if is_new_impact(collision_point, was_colliding):
            collisions_count += 1
            rel_x = collision_point[0] - rx
            rel_y = collision_point[1] - ry
            local_fwd = rel_x * fwd_x + rel_y * fwd_y
            local_lat = -rel_x * fwd_y + rel_y * fwd_x
            lado = "front_left" if (local_fwd >= 0 and local_lat >= 0) else (
                   "front_right" if (local_fwd >= 0 and local_lat < 0) else (
                   "rear_left" if (local_fwd < 0 and local_lat >= 0) else "rear_right"
            ))
            log_msg(f"[{elapsed_sec:05.1f}s | COLISIÓN -5 pts] Impacto #{collisions_count} ({lado}) en pos=({collision_point[0]:.2f}, {collision_point[1]:.2f}, {collision_point[2]:.2f})")
            if sock:
                try:
                    sock.sendall(f"EVENT:COLISION,impacto={collisions_count},lado={lado}\n".encode("utf-8"))
                except Exception:
                    pass
        was_colliding = collision_now

    if dataset_recorder is not None:
        summary = dataset_recorder.get_session_summary()
        log_msg("\n" + "-" * 70)
        log_msg("  📦 [DATASET V2 - RESUMEN DE SESIÓN REGISTRADO]")
        log_msg(f"  Ultrasonido (nuevas / total): {summary['session_us']} / {summary['total_us']}")
        log_msg(f"  Canicas     (nuevas / total): {summary['session_marbles']} / {summary['total_marbles']}")
        log_msg(f"  Guardado en: {summary['us_path']} y {summary['marbles_path']}")
        log_msg("-" * 70)

    if sock:
        try: sock.close()
        except Exception: pass


if __name__ == "__main__":
    main()
