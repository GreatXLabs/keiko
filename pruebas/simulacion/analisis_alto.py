"""
Sensor de arriba: para cada altura, ¿ve la canica? (no debería) y ¿desde qué
altura de obstáculo lo ve? (los obstáculos más bajos que eso se confunden
con una canica).
"""

import numpy as np
import acustica as ac

DB = ac.BINS[1] - ac.BINS[0]
CERO = np.zeros(len(ac.BINS))
XS = np.arange(0.03, 0.61, 0.01)


def env_obj(sensor, facetas, g):
    return ac.envolvente(ac.perfil_coherente(sensor, facetas, ac.BINS), CERO, DB, g)


def max_canica(H, g, pitch=0.0):
    """Máxima amplitud (re umbral) del eco de la canica para el sensor de arriba,
    barriendo distancia y desplazamiento lateral."""
    s = ac.Sensor(0, 0, H, pitch_deg=pitch)
    peor, donde = 0.0, None
    for x in XS:
        for y in (0.0, 0.05, 0.10):
            a = env_obj(s, ac.esfera(x, y, 0.008), g).max()
            if a > peor:
                peor, donde = a, (x, y)
    return peor, donde


def altura_min_obstaculo(H, g, x, forma="caja", pitch=0.0):
    """Altura mínima de obstáculo que el sensor de arriba detecta a distancia x
    (y además lo lee cerca de su distancia real, como el de abajo)."""
    s = ac.Sensor(0, 0, H, pitch_deg=pitch)
    for alto in np.arange(0.01, 0.31, 0.01):
        if forma == "caja":
            f = ac.caja(x + 0.04, 0, 0.08, 0.08, alto)
            frente = x
        else:
            f = ac.cilindro(x + 0.033, 0, 0.033, alto)
            frente = x
        lec = ac.lectura(env_obj(s, f, g), ac.BINS)
        if abs(lec - frente) < 0.05:
            return alto
    return None


if __name__ == "__main__":
    for nombre, rp in {"conservadora": 2.0, "optimista": 3.5}.items():
        g = ac.ganancia(rp)
        print(f"\n=== Calibración {nombre} (pared hasta {rp} m) ===")
        print("  eje arriba | eco máx. canica (re umbral)   | altura mín. de obstáculo que ve (caja 8x8 / lata)")
        print("             |                               |   a 10 cm    a 20 cm    a 30 cm    a 40 cm")
        for H in (0.04, 0.05, 0.06, 0.08, 0.10, 0.12, 0.15, 0.20):
            a, d = max_canica(H, g)
            ve = "LA VE" if a > 1 else ("al límite" if a > 0.7 else "no la ve")
            celdas = []
            for x in (0.10, 0.20, 0.30, 0.40):
                hc = altura_min_obstaculo(H, g, x, "caja")
                hl = altura_min_obstaculo(H, g, x, "lata")
                f = lambda v: " -- " if v is None else f"{v*100:2.0f}cm"
                celdas.append(f"{f(hc)}/{f(hl)}")
            print(f"   {H*100:4.0f} cm  | {a:5.2f} ({ve:9s}) en x={d[0]*100:2.0f},y={d[1]*100:2.0f} | "
                  + "  ".join(celdas))
