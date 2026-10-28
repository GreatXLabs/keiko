"""
Desglose del Montecarlo para las configuraciones candidatas (lógica mejorada):
qué obstáculos generan falsas canicas y por qué se pierden canicas reales.
"""

from collections import Counter, defaultdict
from multiprocessing import Pool

import numpy as np
import acustica as ac
import sim_escenas as se

N = 800


def correr(cfg, rp):
    with Pool() as pool:
        return pool.map(se._trabajo, [(cfg, rp, 5000 + i) for i in range(N)], chunksize=8)


def motivo_perdida(cfg, r):
    """Clasifica por qué no se informó la columna correcta."""
    _, real, dist, _, mej, _, lect, objs = r
    c = objs[0]
    if mej is not None:
        return "columna equivocada"
    # ¿Hay un obstáculo más cerca que la canica en la misma dirección?
    ang_c = np.arctan2(c["y"], c["x"])
    for o in objs[1:]:
        if np.hypot(o["x"], o["y"]) < dist and abs(np.arctan2(o["y"], o["x"]) - ang_c) < np.radians(20):
            return "tapada por obstáculo"
    if all(d >= ac.DIST_MAX or abs(d - dist) > 0.04 for d in lect[:3]):
        return "fuera de alcance/cono"
    return "el de arriba la vio o leyó algo parecido"


if __name__ == "__main__":
    for rp in (2.0, 3.5):
        print(f"\n######## Calibración pared {rp} m ########")
        for h_ar in (0.08, 0.10, 0.12):
            for ab in (0.0, 20.0, 30.0):
                cfg = dict(se.CONFIG_BASE, h_arriba=h_ar, abanico=ab)
                res = correr(cfg, rp)
                con = [r for r in res if r[0]]
                sin = [r for r in res if not r[0]]
                # Canicas "alcanzables": a menos de 30 cm y sin nada más en la escena
                limpias = [r for r in con if len(r[7]) == 1 and r[2] < 0.30]
                ok_l = sum(1 for r in limpias if r[4] == r[1])
                det_l = sum(1 for r in limpias if r[4] is not None)
                # Falsos por clase de obstáculo (escenas sin canica con un solo obstáculo)
                fp = defaultdict(lambda: [0, 0])
                for r in sin:
                    if len(r[7]) == 1:
                        k = r[5][0]
                        fp[k][1] += 1
                        fp[k][0] += r[4] is not None
                vacias = [r for r in sin if not r[7]]
                perd = Counter(motivo_perdida(cfg, r) for r in con if r[4] != r[1] and r[2] < 0.30)
                print(f"\n arriba {h_ar*100:.0f} cm, abanico ±{ab:.0f}°")
                print(f"   canica sola <30 cm: detectada {100*det_l/max(len(limpias),1):.0f}%, "
                      f"columna correcta {100*ok_l/max(len(limpias),1):.0f}% (n={len(limpias)})")
                print(f"   escenas vacías con falsa canica: {sum(r[4] is not None for r in vacias)}/{len(vacias)}")
                print("   falsas canicas con un solo obstáculo: " + ", ".join(
                    f"{k} {100*a/max(b,1):.0f}% ({a}/{b})" for k, (a, b) in sorted(fp.items())))
                print("   canicas <30 cm perdidas o mal ubicadas: " + ", ".join(
                    f"{k}: {v}" for k, v in perd.most_common()))
