#!/usr/bin/env python3
"""
Hardware-in-the-Loop (HIL) Cross-Map Benchmark Suite - Proyecto Orquitas (Keiko)
Evalúa el Servidor Central Java (Cerebro + Neuroph 2.98 + Auditor de Estómago)
en comunicación en tiempo real con Webots 3D a través de los 3 mapas oficiales:
1. Bahia de Caza (Estándar)
2. Bahia de Caza (Laberinto - Pasillos estrechos)
3. Bahia de Caza (Esquinas Críticas - Lobitos a 90°)
"""

import sys
import os
import time
import subprocess
from datetime import datetime

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SIM_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
ROOT_DIR = os.path.abspath(os.path.join(SIM_DIR, ".."))
WORLDS_DIR = os.path.join(SIM_DIR, "worlds")
SERVER_JAR = os.path.join(ROOT_DIR, "keiko-server", "target", "keiko-server.jar")
ESTOMAGO_FILE = os.path.join(ROOT_DIR, "estomago.txt")
LOG_CLIENT = os.path.join(SIM_DIR, "logs", "simulation_client.log")

MAPS = [
    {
        "id": "MAP-STD",
        "name": "Bahía de Caza Estándar",
        "world": "bahia_de_caza_client.wbt",
        "desc": "Obstáculos moderados, salida Norte",
        "timeout": 120
    },
    {
        "id": "MAP-LAB",
        "name": "Bahía de Caza Laberinto",
        "world": "bahia_de_caza_labyrinth_client.wbt",
        "desc": "Pasillos estrechos 40-50 cm, alta densidad",
        "timeout": 140
    },
    {
        "id": "MAP-CNR",
        "name": "Bahía de Caza Esquinas Críticas",
        "world": "bahia_de_caza_corners_client.wbt",
        "desc": "Canicas encajadas en esquinas reflectivas a 90°",
        "timeout": 140
    }
]

def calculate_score(captures: int, escaped: bool, elapsed_sec: float, collisions: int) -> int:
    score = 0
    if captures >= 1: score += 5
    if captures >= 2: score += 10
    if captures >= 3: score += 15
    if captures == 3: score += 15  # Activación de Modo Escape auditada en disco
    if escaped:
        score += 30  # Abandono verificado
        if elapsed_sec <= 60.0: score += 15
        elif elapsed_sec <= 240.0: score += 10
        elif elapsed_sec <= 600.0: score += 5
    score -= (collisions * 5)
    return score

def run_map_test(m_info: dict, port: int = 8888) -> dict:
    print(f"\n{'='*75}")
    print(f"  🧪 EVALUANDO MAPA: [{m_info['id']}] {m_info['name']}")
    print(f"  Descripción: {m_info['desc']} | Timeout: {m_info['timeout']}s")
    print(f"{'='*75}")

    # Limpiar procesos previos y archivos
    subprocess.run(["pkill", "-9", "-f", "keiko-server"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "webots-bin"], stderr=subprocess.DEVNULL)
    time.sleep(1.0)

    if os.path.exists(ESTOMAGO_FILE):
        os.remove(ESTOMAGO_FILE)
    if os.path.exists(LOG_CLIENT):
        os.remove(LOG_CLIENT)

    # 1. Levantar Servidor Java
    srv_cmd = [
        "java", "-jar", SERVER_JAR,
        "--port", str(port),
        "--reset"
    ]
    srv_proc = subprocess.Popen(srv_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)

    # 2. Levantar Webots 3D en modo realtime headless (1s sim = 1s real para sincronización de socket)
    world_path = os.path.join(WORLDS_DIR, m_info["world"])
    env = os.environ.copy()
    env["KEIKO_SERVER_PORT"] = str(port)

    webots_cmd = [
        "webots", "--batch", "--mode=realtime", "--no-rendering",
        "--stdout", "--stderr", world_path
    ]
    webots_proc = subprocess.Popen(webots_cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    start_time = time.time()
    captures = 0
    escaped = False
    passed = False
    collisions = 0
    sim_time_recorded = 0.0

    while (time.time() - start_time) < m_info["timeout"]:
        time.sleep(2)

        # Monitorear estomago.txt (fuente física de verdad)
        if os.path.exists(ESTOMAGO_FILE):
            try:
                with open(ESTOMAGO_FILE, "r", encoding="utf-8") as ef:
                    lines = [l for l in ef.readlines() if "CAPTURA #" in l]
                    captures = len(lines)
            except Exception:
                pass

        # Monitorear log de cliente para verificar finalización y tiempo de simulación
        if os.path.exists(LOG_CLIENT):
            try:
                with open(LOG_CLIENT, "r", encoding="utf-8", errors="ignore") as lf:
                    for line in lf:
                        if "Tiempo total:" in line:
                            parts = line.split("Tiempo total:")[1].split("s")[0].strip()
                            sim_time_recorded = float(parts)
                        elif "[" in line and "s |" in line:
                            try:
                                t_str = line.split("[")[1].split("s |")[0].strip()
                                sim_time_recorded = max(sim_time_recorded, float(t_str))
                            except Exception:
                                pass
                        if "MISIÓN DISTRIBUIDA COMPLETADA CON ÉXITO" in line or "Posición de salida:" in line:
                            passed = True
                            escaped = True
            except Exception:
                pass

        if passed or webots_proc.poll() is not None:
            break

    # Detener procesos
    webots_proc.kill()
    srv_proc.kill()
    subprocess.run(["pkill", "-9", "webots-bin"], stderr=subprocess.DEVNULL)

    final_sim_time = sim_time_recorded if sim_time_recorded > 0 else (time.time() - start_time)
    score = calculate_score(captures, escaped, final_sim_time, collisions)

    return {
        "id": m_info["id"],
        "name": m_info["name"],
        "captures": captures,
        "escaped": escaped,
        "elapsed": final_sim_time,
        "collisions": collisions,
        "score": score,
        "verdict": "PASSED" if (captures == 3 and escaped) else "PARTIAL"
    }

def main():
    print("=" * 75)
    print("  🐋 SUITE DE BENCHMARK HIL END-TO-END - PROYECTO ORQUITAS (KEIKO)")
    print("  Servidor Java 21 + Neuroph 2.98 vs Simulación Webots 3D")
    print("=" * 75)

    results = []

    for m_info in MAPS:
        res = run_map_test(m_info, 8888)
        results.append(res)

    print("\n" + "=" * 80)
    print("  🏆 TABLA CONSOLIDADA DE RESULTADOS - BENCHMARK HIL MULTI-MAPA")
    print("=" * 80)
    print(f"| {'ID':<8} | {'Mapa':<28} | {'Capturas':<9} | {'Escape':<7} | {'Tiempo':<8} | {'Puntaje':<10} | {'Veredicto':<8} |")
    print(f"|{'-'*10}|{'-'*30}|{'-'*11}|{'-'*9}|{'-'*10}|{'-'*12}|{'-'*10}|")

    total_score = 0
    for r in results:
        total_score += r["score"]
        esc_str = "✅ Sí" if r["escaped"] else "❌ No"
        print(f"| {r['id']:<8} | {r['name']:<28} | {r['captures']}/3{'':<5} | {esc_str:<7} | {r['elapsed']:>5.1f}s   | {r['score']:>+3} pts    | {r['verdict']:<8} |")

    print("=" * 80)
    print(f"  Puntaje Acumulado Total: {total_score} puntos (Promedio: {total_score/len(results):.1f} pts/mapa)")
    print("=" * 80 + "\n")

if __name__ == "__main__":
    main()
