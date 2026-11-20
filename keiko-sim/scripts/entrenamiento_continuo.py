#!/usr/bin/env python3
"""
=============================================================================
  🐋 KEIKO: SUPERVISIÓN Y ENTRENAMIENTO ITERATIVO CONTINUO (CON RENDERIZADO)
=============================================================================
Ejecuta iteraciones continuas de la simulación con visualización 3D en pantalla:
- Alterna proceduralmente entre las dos bahías: Semielíptica ('standard') y Redonda ('circular').
- Genera en cada intento una pista aleatoria con 2 obstáculos y 3 canicas,
  respetando todas las restricciones de separación físicas y reglamentarias.
- Mantiene activo el servidor central Java 21 (2 redes Neuroph 2.98 + Auditor estomago.txt).
- Abre la ventana gráfica 3D de Webots en tiempo real para supervisión visual en vivo.
- Registra métricas detalladas de cada intento en consola y en logs CSV.
- Cierre limpio con Ctrl+C mostrando el resumen consolidado de la sesión.
=============================================================================
"""

import sys
import os
import time
import math
import socket
import signal
import zlib
import argparse
import subprocess
import datetime

# Rutas del proyecto
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
SIM_DIR = os.path.join(ROOT_DIR, "keiko-sim")
WORLDS_DIR = os.path.join(SIM_DIR, "worlds")
LOGS_DIR = os.path.join(SIM_DIR, "logs")
SERVER_JAR = os.path.join(ROOT_DIR, "keiko-server", "target", "keiko-server.jar")
MODEL_FILE = os.path.join(SIM_DIR, "data", "models", "modelo_ultrasonico.nnet")
ESTOMAGO_FILE = os.path.join(ROOT_DIR, "estomago.txt")
LOG_CLIENT = os.path.join(LOGS_DIR, "simulation_client.log")
CSV_LOG = os.path.join(LOGS_DIR, "entrenamiento_continuo.csv")

sys.path.insert(0, SCRIPT_DIR)
from build_worlds import build_random_world

# Estado global para cierre limpio
RUNNING = True
CURRENT_PROCS = []


def signal_handler(sig, frame):
    global RUNNING
    print("\n\n🛑 [INTERRUPCIÓN DETECTADA] Finalizando sesión de entrenamiento continuo limpiamente...")
    RUNNING = False
    cleanup_processes()


signal.signal(signal.SIGINT, signal_handler)
signal.signal(signal.SIGTERM, signal_handler)


def cleanup_processes():
    for p in CURRENT_PROCS:
        try:
            p.terminate()
            p.wait(timeout=1.0)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
    subprocess.run(["pkill", "-9", "-f", "keiko-server.jar"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "-f", "webots-bin"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "-f", "robot_client.py"], stderr=subprocess.DEVNULL)


def verify_estomago_captures(path: str):
    if not os.path.exists(path):
        return 0, False
    valid_captures = 0
    all_crc_valid = True
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "CAPTURA #" in line and "CHECKSUM=" in line:
                    parts = line.split(" | CHECKSUM=")
                    if len(parts) == 2:
                        base = parts[0]
                        checksum_hex = parts[1].strip()
                        try:
                            expected_crc = int(checksum_hex, 16)
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
    if captures >= 3: score += 15  # Activación auditada en disco
    if escaped:
        score += 30  # Abandono verificado
        if elapsed_sec <= 60.0: score += 15
        elif elapsed_sec <= 240.0: score += 10
        elif elapsed_sec <= 600.0: score += 5
    score -= (collisions * 5)
    return score


def ensure_csv_header():
    os.makedirs(LOGS_DIR, exist_ok=True)
    if not os.path.exists(CSV_LOG) or os.path.getsize(CSV_LOG) == 0:
        with open(CSV_LOG, "w", encoding="utf-8") as f:
            f.write("timestamp,intento,variante,capturas,crc32_ok,escape,colisiones,tiempo_seg,puntaje,competencia_ok,sub_180s,veredicto\n")


def append_csv_result(intento, variante, caps, crc, esc, col, sim_time, score, comp_ok, sub180, verd):
    ensure_csv_header()
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(CSV_LOG, "a", encoding="utf-8") as f:
        f.write(f"{ts},{intento},{variante},{caps},{crc},{esc},{col},{sim_time:.2f},{score},{comp_ok},{sub180},{verd}\n")


def main():
    global RUNNING, CURRENT_PROCS
    parser = argparse.ArgumentParser(description="Iterador de Simulación con Supervisión Gráfica - Proyecto Orquitas")
    parser.add_argument("--bay", choices=["realistic", "alternate", "standard", "circular", "random"], default="realistic",
                        help="Modo de selección de bahía (default: realistic)")
    parser.add_argument("--mode", choices=["fast", "realtime", "run"], default="fast",
                        help="Velocidad de reproducción en Webots (default: fast)")
    parser.add_argument("--timeout", type=int, default=420,
                        help="Tiempo máximo simulado por intento en segundos (default: 420 [7 min])")
    parser.add_argument("--pause", type=float, default=2.5,
                        help="Pausa entre intentos en segundos (default: 2.5)")
    parser.add_argument("--max-iterations", type=int, default=0,
                        help="Cantidad de intentos a ejecutar (0 = infinito, default: 0)")
    parser.add_argument("--port", type=int, default=8888,
                        help="Puerto TCP para keiko-server (default: 8888)")
    parser.add_argument("--capote", action="store_true",
                        help="Incluir carcasa exterior 'Capote' en el robot")
    parser.add_argument("--obstacle-shape", choices=["alternate", "box", "cylinder"], default="alternate",
                        help="Forma de obstáculos: alternate (cubos/cilindros iteración de por medio), box o cylinder")
    args = parser.parse_args()

    print("\n" + "=" * 80)
    print("  🐋 PROYECTO ORQUITAS (KEIKO) - SUPERVISIÓN Y ENTRENAMIENTO ITERATIVO CONTINUO")
    print("  Renderizado Gráfico: ACTIVO (Webots 3D en pantalla)")
    print(f"  Modo Bahía: {args.bay.upper()} | Velocidad Webots: --mode={args.mode}")
    print(f"  Timeout por intento: {args.timeout}s | Pausa entre intentos: {args.pause}s")
    print(f"  Registro histórico: {CSV_LOG}")
    print("  Para detener en cualquier momento presiona Ctrl+C")
    print("=" * 80 + "\n")

    iteration = 0
    total_passed = 0
    total_competencias_exitosas = 0
    total_competencias_sub180 = 0
    total_captures_all = 0
    history = []

    # Configuración de pantalla X11/Wayland para GUI
    env_display = os.environ.get("DISPLAY") or ":0.0"
    if not env_display.startswith(":"):
        env_display = ":0.0"

    use_gpu_prime = False
    if subprocess.run(["which", "nvidia-smi"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        res = subprocess.run(["nvidia-smi"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        if "Disp.A" in res.stdout and "On" not in res.stdout:
            use_gpu_prime = True

    while RUNNING:
        iteration += 1
        if args.max_iterations > 0 and iteration > args.max_iterations:
            print(f"\n🏁 Meta de {args.max_iterations} intentos alcanzada.")
            break

        # 1. Determinar variante de bahía para esta iteración
        if args.bay == "realistic":
            current_variant = "realistic"
        elif args.bay == "alternate":
            current_variant = "realistic" if (iteration % 2 != 0) else "circular"
        elif args.bay == "random":
            import random
            current_variant = random.choice(["realistic", "circular", "standard"])
        else:
            current_variant = args.bay

        if current_variant == "realistic":
            variant_title = "REALISTA (Bahía Imperfecta de bahia-keiko.stl con Salida Desplazada)"
        elif current_variant == "circular":
            variant_title = "CIRCULAR (Bahía Redonda con Imperfecciones y Salida Desplazada)"
        else:
            variant_title = "STANDARD (Bahía Semielíptica Clásica 1.80x1.20m)"

        print(f"\n{'#'*80}")
        print(f"  🚀 [INTENTO #{iteration:03d}] BAHÍA: {variant_title}")
        print(f"  Fecha y hora: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"{'#'*80}")

        # 2. Generar mundo aleatorio con obstáculos (alternados) y canicas respetando distancias
        obs_param = None if args.obstacle_shape == "alternate" else args.obstacle_shape
        world_path = build_random_world(variant=current_variant, include_capote=args.capote,
                                        iteration=iteration, obstacle_shape=obs_param)

        # 3. Limpiar estado previo
        cleanup_processes()
        if os.path.exists(ESTOMAGO_FILE):
            try: os.remove(ESTOMAGO_FILE)
            except Exception: pass
        if os.path.exists(LOG_CLIENT):
            try: os.remove(LOG_CLIENT)
            except Exception: pass

        # 4. Iniciar Servidor Central Java 21
        srv_log_path = os.path.join(LOGS_DIR, "server_iterativo.log")
        srv_log = open(srv_log_path, "w", encoding="utf-8")
        srv_cmd = [
            "java", "-jar", SERVER_JAR,
            "--port", str(args.port),
            "--model", MODEL_FILE,
            "--estomago", ESTOMAGO_FILE,
            "--reset"
        ]
        srv_proc = subprocess.Popen(srv_cmd, cwd=os.path.join(ROOT_DIR, "keiko-server"),
                                    stdout=srv_log, stderr=subprocess.STDOUT)
        CURRENT_PROCS = [srv_proc]

        # Esperar conexión al puerto
        t_wait = time.time()
        server_ready = False
        while time.time() - t_wait < 8.0:
            try:
                with socket.create_connection(("127.0.0.1", args.port), timeout=0.2):
                    server_ready = True
                    break
            except Exception:
                time.sleep(0.1)

        if not server_ready:
            print("  ❌ [ERROR] El servidor Java no inició a tiempo en el puerto 8888.")
            cleanup_processes()
            time.sleep(2.0)
            continue

        # 5. Iniciar Webots CON RENDERIZADO VISIBLE EN PANTALLA
        webots_log_path = os.path.join(LOGS_DIR, "webots_iterativo.log")
        webots_log = open(webots_log_path, "w", encoding="utf-8")

        env = os.environ.copy()
        env["DISPLAY"] = env_display
        env["KEIKO_SERVER_PORT"] = str(args.port)
        env["ORQUITAS_SIM_TIMEOUT"] = str(args.timeout)
        env["ORQUITAS_AUTO_QUIT"] = "1"
        env["ORQUITAS_SWEEP_MAX_DEG"] = "35.0"
        env["ORQUITAS_SWEEP_TICKS"] = "2"
        env["ORQUITAS_RECORD_DATASET"] = "1"

        if use_gpu_prime:
            env["__NV_PRIME_RENDER_OFFLOAD"] = "1"
            env["__GLX_VENDOR_LIBRARY_NAME"] = "nvidia"

        webots_mode = "fast" if args.mode in ("fast", "run") else "realtime"
        webots_mode_flag = f"--mode={webots_mode}"
        webots_cmd = ["webots", webots_mode_flag, "--stdout", "--stderr", world_path]

        print(f"  🖥️  Abriendo Webots ({webots_mode_flag}) en pantalla [{env_display}]...")
        webots_proc = subprocess.Popen(webots_cmd, env=env, stdout=webots_log, stderr=subprocess.STDOUT, close_fds=True)
        CURRENT_PROCS.append(webots_proc)

        # 6. Monitoreo en vivo de la simulación
        start_real = time.time()
        caps = 0
        crc_ok = False
        escaped = False
        collisions = 0
        sim_time = 0.0
        last_status_print = 0

        while RUNNING:
            time.sleep(0.4)

            # Monitorear estomago físico
            caps, crc_ok = verify_estomago_captures(ESTOMAGO_FILE)

            # Monitorear log del cliente
            if os.path.exists(LOG_CLIENT):
                try:
                    with open(LOG_CLIENT, "r", encoding="utf-8", errors="ignore") as lf:
                        content = lf.read()
                        collisions = content.count("COLISIÓN -5 pts") + content.count("COLISIÓN REGISTRADA")
                        for line in content.splitlines():
                            if "Tiempo total:" in line and "s" in line:
                                try:
                                    sim_time = float(line.split("Tiempo total:")[1].split("s")[0].strip())
                                except Exception:
                                    pass
                            elif line.startswith("[") and "s]" in line:
                                try:
                                    val = float(line.split("]")[0].replace("[", "").replace("s", "").strip())
                                    if val > sim_time:
                                        sim_time = val
                                except Exception:
                                    pass
                            if "MISIÓN DISTRIBUIDA COMPLETADA CON ÉXITO" in line or "Posición de salida:" in line:
                                escaped = True
                except Exception:
                    pass

            # Mostrar estado en tiempo real cada 3 segundos
            if time.time() - last_status_print > 3.0:
                print(f"  ⏱️  [En Vivo] T={sim_time:5.1f}s | Canicas: {caps}/3 (CRC: {'OK' if crc_ok else '...'}) | "
                      f"Colisiones: {collisions} | Escape: {'SÍ' if escaped else 'No'}")
                last_status_print = time.time()

            # Condición de finalización del intento
            if escaped or (webots_proc.poll() is not None) or (time.time() - start_real > (args.timeout + 40)):
                break

        # 7. Finalizar procesos del intento
        cleanup_processes()
        try: srv_log.close()
        except Exception: pass
        try: webots_log.close()
        except Exception: pass
        CURRENT_PROCS = []

        if not RUNNING:
            break

        final_sim_time = sim_time if sim_time > 0 else float(args.timeout)
        score = calculate_score(caps, escaped, final_sim_time, collisions)
        verdict = "ÉXITO (PASSED)" if (caps == 3 and crc_ok and escaped and collisions == 0) else (
            "PARCIAL" if (caps > 0 or escaped) else "FALLO"
        )

        es_competencia_exitosa = (caps == 3 and crc_ok and escaped)
        es_sub180 = es_competencia_exitosa and (final_sim_time <= 180.0)

        if es_competencia_exitosa:
            total_competencias_exitosas += 1
            if es_sub180:
                total_competencias_sub180 += 1
        if verdict == "ÉXITO (PASSED)":
            total_passed += 1
        total_captures_all += caps

        history.append({
            "intento": iteration,
            "variante": current_variant,
            "capturas": caps,
            "crc_ok": crc_ok,
            "escape": escaped,
            "colisiones": collisions,
            "tiempo": final_sim_time,
            "puntaje": score,
            "competencia_exitosa": es_competencia_exitosa,
            "sub_180s": es_sub180,
            "veredicto": verdict
        })

        append_csv_result(iteration, current_variant, caps, crc_ok, escaped, collisions, final_sim_time, score,
                          "SI" if es_competencia_exitosa else "NO",
                          "SI" if es_sub180 else "NO", verdict)

        # 8. Banner de resultados del intento
        print("\n" + "-" * 80)
        print(f"  🏁 RESULTADO INTENTO #{iteration:03d} - BAHÍA: {current_variant.upper()}")
        print(f"     Puntaje   : {score:+d} pts | Veredicto: {verdict}")
        print(f"     Capturas  : {caps}/3 {'★'*caps}{'☆'*(3-caps)} | CRC32 Hardware: {'✅ OK' if crc_ok else '❌ Error'}")
        print(f"     Escape    : {'✅ Exitoso por puerta norte' if escaped else '❌ No alcanzado'}")
        print(f"     Colisiones: {collisions} {'(Limpio)' if collisions == 0 else '(Penalizadas)'}")
        print(f"     Tiempo    : {final_sim_time:.1f} s")
        if es_competencia_exitosa:
            if es_sub180:
                print(f"     ⚡ COMPETENCIA : 🏆 ¡ÉXITO SUB-180s! (Tiempo veloz: {final_sim_time:.1f}s <= 180.0s)")
            else:
                print(f"     🏅 COMPETENCIA : ✅ EXITOSA REGLAMENTARIA (Tiempo: {final_sim_time:.1f}s <= {args.timeout:.1f}s [7 min])")
        else:
            print(f"     ⚠️  COMPETENCIA : ❌ NO COMPLETADA ({caps}/3 canicas, Escape: {'Sí' if escaped else 'No'})")

        # Estadísticas de Dataset V2 en vivo
        ds_v2_dir = os.path.join(SIM_DIR, "data", "datasets", "v2_simulaciones")
        us_v2_f = os.path.join(ds_v2_dir, "dataset_ultrasonico_v2.csv")
        marbles_v2_f = os.path.join(ds_v2_dir, "dataset_canicas_v2.csv")
        us_count = max(0, sum(1 for _ in open(us_v2_f, "r", encoding="utf-8")) - 1) if os.path.exists(us_v2_f) else 0
        marbles_count = max(0, sum(1 for _ in open(marbles_v2_f, "r", encoding="utf-8")) - 1) if os.path.exists(marbles_v2_f) else 0

        print(f"  📈 ESTADÍSTICAS GLOBALES:")
        print(f"     • Intentos ejecutados          : {iteration}")
        print(f"     • Competencias exitosas (<= 7m): {total_competencias_exitosas}/{iteration} ({100.0 * total_competencias_exitosas / iteration:.1f}%)")
        print(f"     • Competencias veloces (<=180s): {total_competencias_sub180}/{iteration} ({100.0 * total_competencias_sub180 / iteration:.1f}%)")
        print(f"     • Corridas limpias (0 colision): {total_passed}/{iteration} ({100.0 * total_passed / iteration:.1f}%)")
        print(f"     • Canicas aseguradas totales   : {total_captures_all}")
        print(f"  📦 DATASET V2 ACUMULADO           : US={us_count} muestras | Canicas={marbles_count} muestras")
        print("-" * 80)

        if RUNNING and args.pause > 0:
            print(f"  ⏳ Preparando siguiente intento en {args.pause:.1f}s...\n")
            time.sleep(args.pause)

    # Resumen final de la sesión
    total_executed = len(history)
    print("\n" + "=" * 80)
    print("  📊 RESUMEN CONSOLIDADO DE LA SESIÓN DE ENTRENAMIENTO")
    print(f"  Intentos ejecutados            : {total_executed}")
    print(f"  Competencias exitosas (<= 7 min): {total_competencias_exitosas} ({100.0 * total_competencias_exitosas / max(1, total_executed):.1f}%)")
    print(f"  Competencias veloces (<= 180s) : {total_competencias_sub180} ({100.0 * total_competencias_sub180 / max(1, total_executed):.1f}%)")
    print(f"  Corridas limpias (0 colisiones): {total_passed} ({100.0 * total_passed / max(1, total_executed):.1f}%)")
    print(f"  Canicas aseguradas totales     : {total_captures_all} / {max(1, total_executed * 3)}")
    print(f"  Registro guardado en           : {CSV_LOG}")
    print("=" * 80 + "\n")


if __name__ == "__main__":
    main()
