"""
Escenas completas con la matriz 3x2: canicas y obstáculos en posiciones
al azar, aplicando la misma lógica de detección que el sketch de Arduino.

Uso:
    python sim_escenas.py demo        # escenas puntuales, con detalle por sensor
    python sim_escenas.py montecarlo  # comparación de configuraciones
"""

import sys
from multiprocessing import Pool

import numpy as np
import acustica as ac

DB = ac.BINS[1] - ac.BINS[0]
CERO = np.zeros(len(ac.BINS))
UMBRAL_CM = 5.0
R_CANICA = 0.008
COLUMNAS = ["IZQUIERDA", "MEDIO", "DERECHA"]


def sensores(cfg):
    """Seis sensores: índices 0-2 abajo (izq, medio, der), 3-5 arriba."""
    s = cfg["separacion"]
    res = []
    for fila, z, pitch in (("abajo", cfg["h_abajo"], cfg["pitch_abajo"]),
                           ("arriba", cfg["h_arriba"], cfg["pitch_arriba"])):
        for col, (y, yaw) in enumerate(((s, cfg["abanico"]), (0.0, 0.0), (-s, -cfg["abanico"]))):
            res.append(ac.Sensor(0, y, z, yaw, pitch, f"{fila}-{COLUMNAS[col]}",
                                 vertical=cfg.get("vertical", False)))
    return res


# --- Objetos de la escena -----------------------------------------------------
def construir(obj):
    t = obj["tipo"]
    if t == "canica":
        return ac.esfera(obj["x"], obj["y"], R_CANICA)
    if t == "lata":
        return ac.cilindro(obj["x"], obj["y"], obj["r"], obj["alto"])
    return ac.caja(obj["x"], obj["y"], obj["ancho"], obj["prof"], obj["alto"], obj["yaw"])


def leer(sensor, objetos, g, rng=None, jitter_db=0.0):
    """Lectura del sensor con todos los objetos (cada uno con su variación de
    amplitud aleatoria, que representa diferencias de superficie y montaje)."""
    perfil = np.zeros(len(ac.BINS), complex)
    for obj in objetos:
        p = ac.perfil_coherente(sensor, construir(obj), ac.BINS)
        if rng is not None and jitter_db:
            p *= 10 ** (rng.normal(0, jitter_db) / 20)
        perfil += p
    return ac.lectura(ac.envolvente(perfil, CERO, DB, g), ac.BINS)


def decidir(lect, reposo):
    """Misma lógica que el sketch: por columna, el de abajo cae >= umbral
    respecto de su reposo y mide >= umbral menos que el de arriba."""
    cm = lambda d: d * 100
    flags = []
    for col in range(3):
        abajo, arriba = cm(lect[col]), cm(lect[col + 3])
        flags.append(cm(reposo[col]) - abajo >= UMBRAL_CM and arriba - abajo >= UMBRAL_CM)
    return flags


def decidir_mejorada(lect, reposo):
    """Variante propuesta: hay canica si el de abajo cae respecto de su reposo y
    NINGUNO de los tres de arriba ve algo a una distancia parecida o menor
    (los conos se pisan, así que comparar solo la misma columna no alcanza).
    Se informa una sola columna: la de menor lectura abajo."""
    cm = lambda d: d * 100
    arriba_min = min(cm(d) for d in lect[3:])
    flags = [cm(reposo[c]) - cm(lect[c]) >= UMBRAL_CM and arriba_min - cm(lect[c]) >= UMBRAL_CM
             for c in range(3)]
    return columna_mas_cercana(lect, flags)


def columna_mas_cercana(lect, flags):
    """Variante: de las columnas marcadas, informar solo la de menor lectura abajo."""
    cand = [c for c in range(3) if flags[c]]
    return min(cand, key=lambda c: lect[c]) if cand else None


def columna_real(cfg, x, y):
    """Columna 'correcta' para una canica: la del sensor de abajo cuyo eje
    apunta más hacia ella."""
    best, ang_best = None, 1e9
    for col, s in enumerate(sensores(cfg)[:3]):
        v = np.array([x, y]) - s.centro[:2]
        ang = abs(np.degrees(np.arctan2(v[1], v[0])) - np.degrees(np.arctan2(s.eje[1], s.eje[0])))
        if ang < ang_best:
            best, ang_best = col, ang
    return best


# --- Escenas puntuales ----------------------------------------------------------
def canica(x, y):
    return {"tipo": "canica", "x": x, "y": y}


def caja_o(x, y, alto, ancho=0.08, prof=0.08, yaw=0.0):
    return {"tipo": "caja", "x": x + prof / 2, "y": y, "ancho": ancho, "prof": prof,
            "alto": alto, "yaw": yaw}


def lata(x, y, alto=0.12, r=0.033):
    return {"tipo": "lata", "x": x + r, "y": y, "r": r, "alto": alto}


ESCENAS_DEMO = [
    ("Vacía (solo piso)", []),
    ("Canica al medio a 10 cm", [canica(0.10, 0)]),
    ("Canica al medio a 20 cm", [canica(0.20, 0)]),
    ("Canica al medio a 30 cm", [canica(0.30, 0)]),
    ("Canica al medio a 40 cm", [canica(0.40, 0)]),
    ("Canica a la izquierda a 15 cm", [canica(0.15, 0.10)]),
    ("Canica a la derecha a 25 cm", [canica(0.25, -0.12)]),
    ("Canica entre izq y medio a 20 cm", [canica(0.20, 0.05)]),
    ("Canica muy abierta (izq) a 20 cm", [canica(0.20, 0.25)]),
    ("Caja alta 20 cm, al medio a 20 cm", [caja_o(0.20, 0, 0.20)]),
    ("Caja alta girada 35°, a 25 cm", [caja_o(0.25, 0, 0.20, yaw=35)]),
    ("Caja baja 3 cm, al medio a 20 cm", [caja_o(0.20, 0, 0.03)]),
    ("Caja baja 5 cm, al medio a 10 cm", [caja_o(0.10, 0, 0.05)]),
    ("Objeto chico (cubo 3 cm) a 15 cm", [caja_o(0.15, 0, 0.03, 0.03, 0.03)]),
    ("Objeto chico girado 45° a 15 cm", [caja_o(0.15, 0, 0.03, 0.03, 0.03, yaw=45)]),
    ("Lata (12 cm) a la izquierda a 25 cm", [lata(0.25, 0.10)]),
    ("Tapita/cilindro bajo (r 2 cm, 1,5 cm) a 15 cm", [lata(0.15, 0, alto=0.015, r=0.02)]),
    ("Canica a 15 cm delante de caja alta a 35 cm", [canica(0.15, 0), caja_o(0.35, 0, 0.20)]),
    ("Canica a 30 cm detrás de caja baja a 15 cm", [canica(0.30, 0), caja_o(0.15, 0, 0.03)]),
    ("Dos canicas: izq 15 cm y der 20 cm", [canica(0.15, 0.10), canica(0.20, -0.10)]),
    ("Pared/borde de arena a 50 cm", [caja_o(0.50, 0, 0.10, ancho=1.0, prof=0.02)]),
]

CONFIG_BASE = {"separacion": 0.10, "abanico": 0.0, "h_abajo": 0.015, "pitch_abajo": 0.0,
               "h_arriba": 0.12, "pitch_arriba": 0.0}


def demo(cfg, rp):
    g = ac.ganancia(rp)
    ss = sensores(cfg)
    reposo = [leer(s, [], g) for s in ss]
    print(f"\nConfig: abajo {cfg['h_abajo']*100:.1f} cm, arriba {cfg['h_arriba']*100:.0f} cm, "
          f"columnas a {cfg['separacion']*100:.0f} cm, abanico ±{cfg['abanico']:.0f}°, "
          f"calibración pared {rp} m")
    print(f"{'escena':48s} | abajo I / M / D (cm)   | arriba I / M / D (cm)  | "
          f"{'lógica actual':28s} | lógica mejorada")
    for nombre, objs in ESCENAS_DEMO:
        lect = [leer(s, objs, g) for s in ss]
        flags = decidir(lect, reposo)
        f = lambda d: "  -- " if d >= ac.DIST_MAX else f"{d*100:5.1f}"
        res = ", ".join(COLUMNAS[c] for c in range(3) if flags[c]) or "nada"
        mej = decidir_mejorada(lect, reposo)
        mej = COLUMNAS[mej] if mej is not None else "nada"
        print(f"{nombre:48s} | {' '.join(f(d) for d in lect[:3])} | "
              f"{' '.join(f(d) for d in lect[3:])} | {res:28s} | {mej}")


# --- Montecarlo -------------------------------------------------------------------
def escena_al_azar(rng):
    objs, tiene_canica = [], rng.random() < 0.6
    if tiene_canica:
        objs.append(canica(rng.uniform(0.03, 0.50), rng.uniform(-0.25, 0.25)))
    for _ in range(rng.integers(0, 3)):
        tipo = rng.choice(["caja_alta", "caja_baja", "lata", "chico", "tapita"])
        for _intento in range(20):
            x, y = rng.uniform(0.08, 0.80), rng.uniform(-0.40, 0.40)
            if all(np.hypot(x - o["x"], y - o["y"]) > 0.10 for o in objs):
                break
        yaw = rng.uniform(0, 90)
        if tipo == "caja_alta":
            objs.append(caja_o(x, y, rng.uniform(0.12, 0.30), rng.uniform(0.05, 0.15),
                               rng.uniform(0.05, 0.15), yaw))
        elif tipo == "caja_baja":
            objs.append(caja_o(x, y, rng.uniform(0.02, 0.07), rng.uniform(0.05, 0.15),
                               rng.uniform(0.05, 0.15), yaw))
        elif tipo == "lata":
            objs.append(lata(x, y, rng.uniform(0.08, 0.20), rng.uniform(0.02, 0.05)))
        elif tipo == "chico":
            a = rng.uniform(0.02, 0.04)
            objs.append(caja_o(x, y, a, a, a, yaw))
        else:
            objs.append(lata(x, y, rng.uniform(0.01, 0.02), rng.uniform(0.015, 0.03)))
        objs[-1]["clase"] = tipo
    return objs, tiene_canica


def _trabajo(args):
    cfg, rp, semilla = args
    rng = np.random.default_rng(semilla)
    objs, tiene_canica = escena_al_azar(rng)
    g = ac.ganancia(rp)
    ss = sensores(cfg)
    # Ganancia propia de cada módulo (los clones varían) + variación por objeto
    lect = []
    for s in ss:
        gs = g * 10 ** (rng.normal(0, 1.5) / 20)
        lect.append(leer(s, objs, gs, rng, jitter_db=2.0))
    reposo = [ac.DIST_MAX] * 6  # el piso no devuelve eco (ver analisis_lateral)
    flags = decidir(lect, reposo)
    # Lógica actual: es correcta si marca exactamente la columna real
    actual = [c for c in range(3) if flags[c]]
    mejorada = decidir_mejorada(lect, reposo)
    real = None
    dist = None
    if tiene_canica:
        c = objs[0]
        real = columna_real(cfg, c["x"], c["y"])
        dist = np.hypot(c["x"], c["y"])
    clases = [o.get("clase", "canica") for o in objs]
    return tiene_canica, real, dist, actual, mejorada, clases, lect, objs


def montecarlo(configs, rp, n=500):
    tareas = [(cfg, rp, 1000 + i) for cfg in configs for i in range(n)]
    with Pool() as pool:
        res = pool.map(_trabajo, tareas, chunksize=8)
    salida = []
    bandas = [(0, 0.15), (0.15, 0.25), (0.25, 0.35), (0.35, 0.50)]
    for k, cfg in enumerate(configs):
        r = res[k * n:(k + 1) * n]
        con = [x for x in r if x[0]]
        sin = [x for x in r if not x[0]]
        fila = {}
        for nombre, ok_fn, falso_fn in (
                ("actual", lambda x: x[3] == [x[1]], lambda x: bool(x[3])),
                ("mejorada", lambda x: x[4] == x[1], lambda x: x[4] is not None)):
            det = []
            for a, b in bandas:
                sub = [x for x in con if a <= x[2] < b]
                det.append((sum(1 for x in sub if ok_fn(x)), len(sub)))
            falsos = sum(1 for x in sin if falso_fn(x))
            fila[nombre] = (det, falsos, len(sin))
        salida.append((cfg, fila))
    return salida


if __name__ == "__main__":
    modo = sys.argv[1] if len(sys.argv) > 1 else "demo"
    if modo == "demo":
        for rp in (2.0, 3.5):
            demo(CONFIG_BASE, rp)
    else:
        configs = []
        for h_ab in (0.015, 0.03):
            for h_ar in (0.08, 0.12, 0.15):
                for ab in (0.0, 20.0, 30.0):
                    configs.append(dict(CONFIG_BASE, h_abajo=h_ab, h_arriba=h_ar, abanico=ab))
        for rp in (2.0, 3.5):
            print(f"\n=== Montecarlo, calibración pared {rp} m (500 escenas por config) ===")
            print("abajo arriba abanico lógica   | canica en la columna correcta, por distancia      "
                  "| falsas canicas (escenas sin canica)")
            print("                              |  <15 cm  15-25cm  25-35cm  35-50cm |")
            for cfg, fila in montecarlo(configs, rp):
                for nombre, (det, falsos, n_sin) in fila.items():
                    celdas = "  ".join(f"{100*ok/max(t,1):5.0f}% " for ok, t in det)
                    print(f"{cfg['h_abajo']*100:4.1f}  {cfg['h_arriba']*100:4.0f}   {cfg['abanico']:4.0f}°  "
                          f"{nombre:8s} | {celdas}| {100*falsos/max(n_sin,1):5.1f}% ({falsos}/{n_sin})")
