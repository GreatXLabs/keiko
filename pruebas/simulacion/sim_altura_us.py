"""
¿A qué altura van los 3 HC-SR04 del frente?

Configuración actual (SIMULACION-UNITY.md): 3 ultrasonidos a x=0, separados
5,4 cm, los de los costados abiertos 30°. Coordenadas de acustica.py:
x adelante, y izquierda, z arriba.

Pruebas por altura H:
  A) Obstáculo de reglamento (6x6 cm, alto 6 cm = peor caso, y 10 cm):
     ¿el ultrasonido "que mejor apunta" lo confirma (lectura a <= 6 cm de la
     distancia real)? Si no lo confirma, el láser lo ve y el Arduino lo
     clasifica como CANICA.  ¿Salta la alarma de obstáculo (<25 cm)?
  B) Canica (16 mm): ¿algún ultrasonido la ve? Si el que mejor apunta la lee
     a distancia parecida -> "obstáculo alto" (canica perdida). Si cualquiera
     lee <25 cm -> falsa alarma de obstáculo (el auto la esquiva).
  C) Piso difuso: lectura con la escena vacía.
"""
import sys
from multiprocessing import Pool

import numpy as np

import acustica as ac

DB = ac.BINS[1] - ac.BINS[0]
CERO = np.zeros(len(ac.BINS))
SEP = 0.054
ABANICO = 0.0   # los 3 mirando derecho al frente
MARGEN = 0.06       # 6 cm, como el sketch
ALARMA = 0.25       # obstáculo si < 25 cm
ALCANCE_LASER = 0.18  # el láser marca "algo bajo" si lee <= 18 cm
CALIB = {"conservadora": 2.0, "optimista": 3.5}
ALTURAS = [0.040, 0.045, 0.050, 0.055, 0.060, 0.070, 0.085]


def sensores(H, pitch=0.0):
    return [ac.Sensor(0, SEP, H, ABANICO, pitch, "izq"),
            ac.Sensor(0, 0.0, H, 0.0, pitch, "medio"),
            ac.Sensor(0, -SEP, H, -ABANICO, pitch, "der")]


def mejor_apunta(ss, px, py):
    best, amin = 0, 1e9
    for i, s in enumerate(ss):
        v = np.array([px, py]) - s.centro[:2]
        ang = abs(np.degrees(np.arctan2(v[1], v[0]) - np.arctan2(s.eje[1], s.eje[0])))
        if ang < amin:
            best, amin = i, ang
    return best


def lecturas(ss, facetas):
    """Lectura de cada sensor para cada calibración."""
    out = {}
    perf = [ac.perfil_coherente(s, facetas, ac.BINS) for s in ss]
    for nom, rp in CALIB.items():
        g = ac.ganancia(rp)
        out[nom] = [ac.lectura(ac.envolvente(p, CERO, DB, g), ac.BINS) for p in perf]
    return out


def caso_obstaculo(args):
    H, pitch, alto, yaw, d, y = args
    ss = sensores(H, pitch)
    lado = 0.06
    # d = distancia del frente del auto a la cara más cercana del obstáculo
    f = ac.caja(d + lado / 2, y, lado, lado, alto, yaw)
    i = mejor_apunta(ss, d, y)
    real = np.hypot(d - ss[i].centro[0], y - ss[i].centro[1])
    res = {}
    for nom, lec in lecturas(ss, f).items():
        conf = abs(lec[i] - real) <= MARGEN
        alarma = min(lec) < ALARMA
        res[nom] = (conf, alarma)
    return args, res


def caso_canica(args):
    H, pitch, d, y = args
    ss = sensores(H, pitch)
    f = ac.esfera(d, y, 0.008)
    i = mejor_apunta(ss, d, y)
    real = np.hypot(d - ss[i].centro[0], y - ss[i].centro[1])
    res = {}
    for nom, lec in lecturas(ss, f).items():
        tapa = abs(lec[i] - real) <= MARGEN       # se clasifica como obstáculo alto
        alarma = min(lec) < ALARMA                 # falsa alarma de obstáculo
        res[nom] = (tapa, alarma)
    return args, res


def caso_piso(args):
    H, pitch, mu = args
    out = {}
    for nom, rp in CALIB.items():
        g = ac.ganancia(rp)
        lec = []
        for s in sensores(H, pitch):
            env = ac.envolvente(np.zeros(len(ac.BINS)), ac.potencia_piso(s, ac.BINS, mu), DB, g)
            lec.append(ac.lectura(env, ac.BINS))
        out[nom] = min(lec)
    return args, out


def pct(xs):
    return f"{100 * np.mean(xs):3.0f}%" if len(xs) else "  - "


def main(pitch=0.0, alturas=ALTURAS):
    DIST = np.round(np.arange(0.04, 0.305, 0.02), 3)
    YS = np.round(np.arange(-0.10, 0.101, 0.025), 3)
    with Pool() as pool:
        obs = pool.map(caso_obstaculo, [(H, pitch, alto, yaw, d, y)
                                        for H in alturas for alto in (0.06, 0.10)
                                        for yaw in (0.0, 30.0) for d in DIST for y in YS])
        can = pool.map(caso_canica, [(H, pitch, d, y) for H in alturas
                                     for d in np.round(np.arange(0.03, 0.305, 0.01), 3) for y in YS])
        piso = pool.map(caso_piso, [(H, pitch, mu) for H in alturas for mu in (1e-3, 1e-2, 3e-2)])

    print(f"\n################ Inclinación de los ultrasonidos: {pitch:+.0f}° ################")
    for nom in CALIB:
        print(f"\n=== Calibración {nom} (pared detectable hasta {CALIB[nom]} m) ===")
        print(" altura | OBSTÁCULO 6 cm alto        | OBSTÁCULO 10 cm alto       | CANICA                          | PISO vacío (mu 1e-3/1e-2/3e-2)")
        print("        | confirma <=18cm  alarma<=20 | confirma <=18cm  alarma<=20 | 'obst. alto' falsa alarma  máx | ")
        for H in alturas:
            fila = f" {H*100:4.1f}cm |"
            for alto in (0.06, 0.10):
                c = [r[nom][0] for a, r in obs if a[0] == H and a[2] == alto and a[4] <= ALCANCE_LASER]
                al = [r[nom][1] for a, r in obs if a[0] == H and a[2] == alto and a[4] <= 0.20]
                fila += f"    {pct(c)}         {pct(al)}     |"
            ct = [r[nom][0] for a, r in can if a[0] == H and a[2] <= ALCANCE_LASER]
            ca = [r[nom][1] for a, r in can if a[0] == H]
            vis = [a[2] for a, r in can if a[0] == H and (r[nom][0] or r[nom][1])]
            maxd = f"{max(vis)*100:3.0f}cm" if vis else "  no "
            fila += f"    {pct(ct)}        {pct(ca)}     {maxd} |"
            fp = [r[nom] for a, r in piso if a[0] == H]
            fila += "  " + " / ".join("nada" if v >= ac.DIST_MAX else f"{v*100:.0f}cm" for v in fp)
            print(fila)

    # Detalle: obstáculo 6 cm, de frente, al medio: hasta qué distancia lo confirma
    print("\nDetalle obstáculo 6x6x6 de frente (yaw 0): distancias (cm) donde el ultrasonido NO lo confirma, por y")
    for nom in CALIB:
        print(f"  [{nom}]")
        for H in alturas:
            malos = {}
            for a, r in obs:
                if a[0] == H and a[2] == 0.06 and a[3] == 0.0 and not r[nom][0]:
                    malos.setdefault(a[5], []).append(int(round(a[4] * 100)))
            txt = "; ".join(f"y={y*100:+.0f}: {v}" for y, v in sorted(malos.items())) or "lo confirma siempre (4-30 cm)"
            print(f"    {H*100:4.1f} cm -> {txt}")

    print("\nDetalle canica: posiciones donde algún ultrasonido la 've' (d cm, y cm)")
    for nom in CALIB:
        print(f"  [{nom}]")
        for H in alturas:
            v = sorted({(int(round(a[2]*100)), int(round(a[3]*100))) for a, r in can
                        if a[0] == H and (r[nom][0] or r[nom][1])})
            print(f"    {H*100:4.1f} cm -> {len(v)} posiciones; d máx {max((p[0] for p in v), default=0)} cm"
                  + (f"; ej. {v[:8]}" if v else ""))


if __name__ == "__main__":
    p = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0
    main(p)
