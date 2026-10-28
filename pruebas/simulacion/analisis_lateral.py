"""
Vista lateral: ¿hasta dónde ve la canica el sensor de abajo según su altura
e inclinación?, ¿cuándo empieza a ver el piso?, ¿a qué altura tiene que ir
el de arriba para no ver la canica pero sí los obstáculos?
"""

import numpy as np
import acustica as ac

DB = ac.BINS[1] - ac.BINS[0]
CALIBRACIONES = {"conservadora": 2.0, "optimista": 3.5}
R_CANICA = 0.008
XS = np.arange(0.03, 0.61, 0.01)


def amplitud_max(sensor, facetas, g, mu=0.0, piso_pot=None):
    perf = ac.perfil_coherente(sensor, facetas, ac.BINS)
    pot = piso_pot if piso_pot is not None else np.zeros(len(ac.BINS))
    env = ac.envolvente(perf, pot, DB, g)
    return env


def alcance_canica(h, pitch, g, y=0.0):
    """Amplitud del eco de la canica (re umbral) vs distancia, en el eje."""
    s = ac.Sensor(0, 0, h, pitch_deg=pitch)
    amps = []
    for x in XS:
        perf = ac.perfil_coherente(s, ac.esfera(x, y, R_CANICA), ac.BINS)
        amps.append(ac.envolvente(perf, np.zeros(len(ac.BINS)), DB, g).max())
    return np.array(amps)


def tramos(amps, margen=1.0):
    """Tramos de x con eco por encima de umbral*margen (puede haber huecos por
    interferencia entre el eco directo y el rebotado en el piso)."""
    ok = amps > margen
    res, i = [], 0
    while i < len(ok):
        if ok[i]:
            j = i
            while j + 1 < len(ok) and ok[j + 1]:
                j += 1
            res.append((XS[i], XS[j]))
            i = j + 1
        else:
            i += 1
    return res


def fmt_tramos(t):
    if not t:
        return "no la ve"
    return ", ".join(f"{a*100:.0f}-{b*100:.0f}" for a, b in t) + " cm"


def lectura_piso(h, pitch, mu, g):
    s = ac.Sensor(0, 0, h, pitch_deg=pitch)
    env = ac.envolvente(np.zeros(len(ac.BINS)), ac.potencia_piso(s, ac.BINS, mu), DB, g)
    return ac.lectura(env, ac.BINS)


if __name__ == "__main__":
    np.set_printoptions(precision=2, suppress=True)
    alturas = [0.010, 0.015, 0.020, 0.030, 0.040, 0.050]
    print("=== 1. Sensor de abajo: zona donde ve la canica (en el eje) ===")
    print("    (margen 'confiable' = eco 3 dB por encima del umbral)")
    for nombre, rp in CALIBRACIONES.items():
        g = ac.ganancia(rp)
        print(f"\n  Calibración {nombre} (pared detectable hasta {rp} m):")
        for pitch in (0, 5):
            for h in alturas:
                a = alcance_canica(h, pitch, g)
                print(f"    eje a {h*100:4.1f} cm, incl. {pitch:2d}°: detecta {fmt_tramos(tramos(a))}"
                      f" | confiable {fmt_tramos(tramos(a, np.sqrt(2)))}")

    print("\n=== 2. Piso: distancia a la que el sensor empieza a 'verlo' ===")
    g = ac.ganancia(2.0)
    for mu in (1e-4, 1e-3, 3e-3, 1e-2, 3e-2):
        fila = []
        for h in alturas + [0.10]:
            d = lectura_piso(h, 0, mu, g)
            fila.append("  nada " if d >= ac.DIST_MAX else f"{d*100:5.0f}cm")
        print(f"  mu={mu:6.0e}: " + " ".join(fila))
    print("            alturas: " + " ".join(f"{h*100:5.1f}cm" for h in alturas + [0.10]))
