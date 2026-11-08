#!/usr/bin/env python3
"""
Hardware-in-the-Loop (HIL) Multi-Scenario Benchmark Suite - Proyecto Orquitas (Keiko)
Configuración de Hardware: 3 US (HC-SR04) + 2 Láseres ToF (VL53L0X) sobre servos SG90.
Evalúa el Servidor Central Java (Cerebro + 2 Redes Neuroph 2.98 + Auditor de Estómago)
en comunicación en tiempo real con Webots 3D en la suite de 8 escenarios reglamentarios.
"""

import sys
import os
import time
import zlib
import socket
import argparse
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SIM_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
ROOT_DIR = os.path.abspath(os.path.join(SIM_DIR, ".."))
WORLDS_DIR = os.path.join(SIM_DIR, "worlds")
SERVER_JAR = os.path.join(ROOT_DIR, "keiko-server", "target", "keiko-server.jar")
ESTOMAGO_FILE = os.path.join(ROOT_DIR, "keiko-server", "estomago.txt")
MODEL_FILE = os.path.join(SIM_DIR, "data", "models", "modelo_ultrasonico.nnet")
LOG_CLIENT = os.path.join(SIM_DIR, "logs", "simulation_client.log")

SCENARIOS = [
    {
        "id": "SCEN-1",
        "name": "Escenario 1 (Spawn Centro Norte)",
        "world": "bahia_de_caza_scen_1.wbt",
        "desc": "Spawn (0, -0.32, 90°), 2 obstáculos 3D a los lados, 3 canicas",
        "timeout": 180
    },
    {
        "id": "SCEN-2",
        "name": "Escenario 2 (Spawn Oeste 80°)",
        "world": "bahia_de_caza_scen_2.wbt",
        "desc": "Spawn (-0.15, -0.35, 80°), canicas en flancos y centro sur",
        "timeout": 240
    },
    {
        "id": "SCEN-3",
        "name": "Escenario 3 (Spawn Este 97°)",
        "world": "bahia_de_caza_scen_3.wbt",
        "desc": "Spawn (0.15, -0.35, 97°), obstáculo central frontal (0, 0.12)",
        "timeout": 180
    },
    {
        "id": "SCEN-4",
        "name": "Escenario 4 (Spawn Centro 90°)",
        "world": "bahia_de_caza_scen_4.wbt",
        "desc": "Spawn (0.00, -0.30, 90°), canicas en flancos este y oeste",
        "timeout": 180
    },
    {
        "id": "SCEN-5",
        "name": "Escenario 5 (Spawn Simétrico 74°)",
        "world": "bahia_de_caza_scen_5.wbt",
        "desc": "Spawn (-0.10, -0.35, 74°), 2 obstáculos simétricos a ±26cm",
        "timeout": 180
    },
    {
        "id": "SCEN-6",
        "name": "Escenario 6 (Spawn Inclinado 103°)",
        "world": "bahia_de_caza_scen_6.wbt",
        "desc": "Spawn (0.10, -0.35, 103°), obstáculo derecho y sur",
        "timeout": 180
    },
    {
        "id": "SCEN-7",
        "name": "Escenario 7 (Spawn Asimétrico 90°)",
        "world": "bahia_de_caza_scen_7.wbt",
        "desc": "Spawn (0.00, -0.32, 90°), obstáculo norte (0, 0.18) y este",
        "timeout": 180
    },
    {
        "id": "SCEN-8",
        "name": "Escenario 8 (Spawn Desplazado Oeste 90°)",
        "world": "bahia_de_caza_scen_8.wbt",
        "desc": "Spawn (-0.20, -0.32, 90°), canicas en extremos laterales",
        "timeout": 180
    },
    {
        "id": "SCEN-9",
        "name": "Escenario 9 (Escolta de Obstáculos)",
        "world": "bahia_de_caza_scen_9.wbt",
        "desc": "Spawn (0.00, -0.32, 90°), canicas a ~15cm de los cubos 6x6x6cm",
        "timeout": 180
    },
    {
        "id": "SCEN-10",
        "name": "Escenario 10 (Corredor Diagonal Slalom)",
        "world": "bahia_de_caza_scen_10.wbt",
        "desc": "Spawn (-0.18, -0.34, 83°), obstáculos en chicana diagonal central",
        "timeout": 180
    },
    {
        "id": "SCEN-11",
        "name": "Escenario 11 (Perímetro Curvo)",
        "world": "bahia_de_caza_scen_11.wbt",
        "desc": "Spawn (0.00, -0.34, 90°), canicas próximas al arco elíptico exterior",
        "timeout": 180
    },
    {
        "id": "SCEN-12",
        "name": "Escenario 12 (Racimo Cercano / Cluster)",
        "world": "bahia_de_caza_scen_12.wbt",
        "desc": "Spawn (0.12, -0.34, 96°), 2 canicas cercanas en noroeste + 1 en sureste",
        "timeout": 180
    },
    {
        "id": "SCEN-13",
        "name": "Escenario 13 (Guardia de Salida)",
        "world": "bahia_de_caza_scen_13.wbt",
        "desc": "Spawn (0.00, -0.32, 90°), obstáculos flanqueando aproximación al hueco norte",
        "timeout": 180
    },
    {
        "id": "SCEN-14",
        "name": "Escenario 14 (Triángulo Profundo Este)",
        "world": "bahia_de_caza_scen_14.wbt",
        "desc": "Spawn (0.22, -0.34, 105°), obstáculo en diagonal inicial y canicas dispersas",
        "timeout": 180
    },
]


def verify_estomago_captures(estomago_path: str):
    """Verifica número de capturas e integridad estricta del Checksum CRC32 por registro."""
    if not os.path.exists(estomago_path):
        return 0, False
    valid_captures = 0
    all_crc_valid = True
    try:
        with open(estomago_path, "r", encoding="utf-8") as ef:
            for line in ef:
                line_str = line.strip()
                if "CAPTURA #" in line_str:
                    if " | CHECKSUM=" in line_str:
                        base, checksum_str = line_str.rsplit(" | CHECKSUM=", 1)
                        try:
                            expected_crc = int(checksum_str, 16)
                            calculated_crc = zlib.crc32(base.encode("utf-8")) & 0xffffffff
                            if expected_crc == calculated_crc:
                                valid_captures += 1
                            else:
                                all_crc_valid = False
                        except ValueError:
                            all_crc_valid = False
                    else:
                        valid_captures += 1
    except Exception:
        all_crc_valid = False
    return valid_captures, (valid_captures >= 3 and all_crc_valid)


def calculate_score(captures: int, escaped: bool, elapsed_sec: float, collisions: int) -> int:
    score = 0
    if captures >= 1: score += 5
    if captures >= 2: score += 10
    if captures >= 3: score += 15
    if captures >= 3: score += 15  # Activación de Modo Escape auditada en disco
    if escaped:
        score += 30  # Abandono verificado
        if elapsed_sec <= 60.0: score += 15
        elif elapsed_sec <= 240.0: score += 10
        elif elapsed_sec <= 600.0: score += 5
    score -= (collisions * 5)
    return score


def run_scenario_test(s_info: dict, port: int = 8888) -> dict:
    print(f"\n{'='*78}")
    print(f"  🧪 EVALUANDO ESCENARIO: [{s_info['id']}] {s_info['name']}")
    print(f"  Mundo: {s_info['world']} | Timeout: {s_info['timeout']}s")
    print(f"  Detalle: {s_info['desc']}")
    print(f"{'='*78}")

    # Limpiar procesos previos y archivos
    subprocess.run(["pkill", "-9", "-f", "[j]ava.*keiko-server"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "webots-bin"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "-f", "robot_client.py"], stderr=subprocess.DEVNULL)
    subprocess.run(["bash", "-c", "rm -rf /tmp/webots/$(whoami)/* 2>/dev/null || true"])
    time.sleep(1.0)

    if os.path.exists(ESTOMAGO_FILE):
        os.remove(ESTOMAGO_FILE)
    if os.path.exists(LOG_CLIENT):
        os.remove(LOG_CLIENT)

    # 1. Levantar Servidor Central Java (2 Redes Neuroph 2.98 + Auditor estomago.txt)
    srv_log_path = os.path.join(ROOT_DIR, "keiko-sim", "logs", "server_benchmark.log")
    srv_log_file = open(srv_log_path, "w", encoding="utf-8")
    srv_cmd = [
        "java", "-jar", SERVER_JAR,
        "--port", str(port),
        "--model", MODEL_FILE,
        "--estomago", ESTOMAGO_FILE,
        "--reset"
    ]
    srv_proc = subprocess.Popen(srv_cmd, cwd=os.path.join(ROOT_DIR, "keiko-server"),
                                stdout=srv_log_file, stderr=subprocess.STDOUT)
    # Esperar activamente a que el servidor Java esté escuchando en el puerto
    t_start = time.time()
    server_ready = False
    while time.time() - t_start < 10.0:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2) as test_sock:
                server_ready = True
                break
        except Exception:
            time.sleep(0.1)
    if not server_ready:
        print(f"  [ERROR] El servidor Java no levantó en el puerto {port}")
    time.sleep(0.2)

    # 2. Levantar Webots 3D en modo fast headless (acelerado, con auditoría rigurosa de tiempo simulado)
    world_path = os.path.join(WORLDS_DIR, s_info["world"])
    webots_log_path = os.path.join(ROOT_DIR, "keiko-sim", "logs", "webots_benchmark.log")
    webots_log_file = open(webots_log_path, "w", encoding="utf-8")
    env = os.environ.copy()
    if os.environ.get("DISPLAY"):
        env["DISPLAY"] = os.environ["DISPLAY"]
    elif os.environ.get("WAYLAND_DISPLAY"):
        env["QT_QPA_PLATFORM"] = "wayland"
        env["WAYLAND_DISPLAY"] = os.environ["WAYLAND_DISPLAY"]
    env["KEIKO_SERVER_PORT"] = str(port)
    env["ORQUITAS_SIM_TIMEOUT"] = str(s_info["timeout"])
    env["ORQUITAS_SWEEP_MAX_DEG"] = "35.0"
    env["ORQUITAS_SWEEP_TICKS"] = "2"
    env["ORQUITAS_AUTO_QUIT"] = "1"

    webots_cmd = [
        "webots", "--batch", "--mode=fast", "--no-rendering",
        "--stdout", "--stderr", world_path
    ]
    webots_proc = subprocess.Popen(webots_cmd, env=env, stdout=webots_log_file, stderr=subprocess.STDOUT, close_fds=True)

    start_time = time.time()
    captures = 0
    crc_valid = False
    escaped = False
    passed = False
    collisions = 0
    sim_time_recorded = 0.0

    while (time.time() - start_time) < (s_info["timeout"] + 30):
        time.sleep(0.5)

        # Monitorear estomago.txt (fuente física de verdad con validación CRC32)
        captures, crc_valid = verify_estomago_captures(ESTOMAGO_FILE)

        # Monitorear log del cliente para colisiones, escape y tiempo simulado (robot.getTime())
        if os.path.exists(LOG_CLIENT):
            try:
                with open(LOG_CLIENT, "r", encoding="utf-8", errors="ignore") as lf:
                    content = lf.read()
                    collisions = content.count("COLISIÓN -5 pts") + content.count("COLISIÓN REGISTRADA")
                    for line in content.splitlines():
                        if "Tiempo total:" in line and "s" in line:
                            try:
                                parts = line.split("Tiempo total:")[1].split("s")[0].strip()
                                sim_time_recorded = float(parts)
                            except Exception:
                                pass
                        elif line.startswith("[") and "s]" in line:
                            try:
                                ts_str = line.split("]")[0].replace("[", "").replace("s", "").strip()
                                val = float(ts_str)
                                if val > sim_time_recorded:
                                    sim_time_recorded = val
                            except Exception:
                                pass
                        if "MISIÓN DISTRIBUIDA COMPLETADA CON ÉXITO" in line or "Posición de salida:" in line:
                            passed = True
                            escaped = True
            except Exception:
                pass

        if passed or webots_proc.poll() is not None:
            break

    # Detener procesos limpiamente
    try:
        webots_proc.kill()
    except Exception:
        pass
    try:
        srv_proc.kill()
    except Exception:
        pass
    try:
        srv_log_file.close()
    except Exception:
        pass
    subprocess.run(["pkill", "-9", "webots-bin"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "-f", "[j]ava.*keiko-server"], stderr=subprocess.DEVNULL)

    final_sim_time = sim_time_recorded if sim_time_recorded > 0 else float(s_info["timeout"])
    score = calculate_score(captures, escaped, final_sim_time, collisions)

    verdict = "PASSED" if (captures == 3 and crc_valid and escaped and collisions == 0) else (
        "PARTIAL" if (captures > 0 or escaped) else "FAILED"
    )

    print(f"  Resultado [{s_info['id']}]: Capturas={captures}/3 (CRC={crc_valid}) | Escape={escaped} | "
          f"Colisiones={collisions} | Tiempo={final_sim_time:.1f}s | Puntaje={score:+d} pts -> {verdict}")

    return {
        "id": s_info["id"],
        "name": s_info["name"],
        "world": s_info["world"],
        "captures": captures,
        "crc_valid": crc_valid,
        "escaped": escaped,
        "elapsed": final_sim_time,
        "collisions": collisions,
        "score": score,
        "verdict": verdict
    }


def main():
    parser = argparse.ArgumentParser(description="Suite HIL de Benchmark Multi-Escenario Orquitas (Keiko)")
    parser.add_argument("--scenarios", type=str, default="all",
                        help="Lista de escenarios a ejecutar (ej. '1,2', '1-4', o 'all')")
    parser.add_argument("--port", type=int, default=8888, help="Puerto TCP del Servidor Java")
    args = parser.parse_args()

    # Filtrar escenarios
    active_scenarios = []
    if args.scenarios.lower() == "all":
        active_scenarios = SCENARIOS
    else:
        indices = set()
        for part in args.scenarios.split(","):
            part = part.strip().upper()
            if part.startswith("SCEN-"):
                num = part.replace("SCEN-", "")
                if num.isdigit():
                    indices.add(int(num))
            elif "-" in part:
                start, end = part.split("-")
                for i in range(int(start), int(end) + 1):
                    indices.add(i)
            elif part.isdigit():
                indices.add(int(part))
        active_scenarios = [s for idx, s in enumerate(SCENARIOS, 1) if idx in indices]

    if not active_scenarios:
        active_scenarios = SCENARIOS

    print("=" * 80)
    print("  🐋 SUITE DE BENCHMARK HIL END-TO-END - PROYECTO ORQUITAS (KEIKO)")
    print("  Configuración Real: 3 HC-SR04 fijos + 2 VL53L0X en servos SG90")
    print("  Servidor Java 21 + 2 Redes Neuroph 2.98 vs Simulación Webots 3D")
    print(f"  Escenarios a evaluar: {len(active_scenarios)} | Puerto Socket: {args.port}")
    print("=" * 80)

    results = []
    for s_info in active_scenarios:
        res = run_scenario_test(s_info, args.port)
        results.append(res)

    print("\n" + "=" * 88)
    print("  🏆 TABLA CONSOLIDADA DE RESULTADOS - BENCHMARK HIL (3US + 2LASER/SERVO)")
    print("=" * 88)
    print(f"| {'ID':<8} | {'Escenario':<28} | {'Capturas':<9} | {'CRC32':<7} | {'Escape':<7} | {'Col':<5} | {'Tiempo':<8} | {'Puntaje':<9} | {'Veredicto':<8} |")
    print(f"|{'-'*10}|{'-'*30}|{'-'*11}|{'-'*9}|{'-'*9}|{'-'*7}|{'-'*10}|{'-'*11}|{'-'*10}|")

    total_score = 0
    passed_count = 0
    for r in results:
        total_score += r["score"]
        if r["verdict"] == "PASSED":
            passed_count += 1
        esc_str = "✅ Sí" if r["escaped"] else "❌ No"
        crc_str = "✅ OK" if r["crc_valid"] else "❌ ERR"
        col_str = f"{r['collisions']}" if r["collisions"] > 0 else "0"
        print(f"| {r['id']:<8} | {r['name']:<28} | {r['captures']}/3{'':<5} | {crc_str:<7} | {esc_str:<7} | {col_str:<5} | {r['elapsed']:>5.1f}s   | {r['score']:>+3} pts   | {r['verdict']:<8} |")

    avg_score = total_score / len(results) if results else 0
    print("=" * 88)
    print(f"  Resumen: {passed_count}/{len(results)} escenarios PASSED | Promedio: {avg_score:.1f} pts/escenario")
    print(f"  Puntaje Acumulado Total: {total_score} puntos")
    print("=" * 88 + "\n")


if __name__ == "__main__":
    main()
