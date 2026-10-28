"""
Distribuciones posibles en una placa de 155 x 100 mm (HC-SR04 de 45 x 20 mm,
centro de los cilindros a 10 mm del borde largo de la PCB).

A) Sensores acostados, placa de 155 de ancho x 100 de alto:
   columnas a 5,5 cm como máximo, filas a 8 cm como máximo entre centros.
B) Sensores parados (girados 90°), placa de 100 de ancho x 155 de alto:
   columnas a 4 cm, filas hasta 11 cm entre centros.

Se asume el borde inferior de la placa a ~5 mm del piso.
"""

from collections import defaultdict
from multiprocessing import Pool

import numpy as np
import sim_escenas as se

N = 800

BASE = dict(se.CONFIG_BASE)
CONFIGS = {
    # Acostados: eje abajo 1,5 cm (PCB a 5 mm del piso), arriba 1,5 + 8 = 9,5 cm
    "A acostados, abanico 0°": dict(BASE, separacion=0.055, h_abajo=0.015, h_arriba=0.095, abanico=0.0),
    "A acostados, abanico 20°": dict(BASE, separacion=0.055, h_abajo=0.015, h_arriba=0.095, abanico=20.0),
    "A acostados, abanico 30°": dict(BASE, separacion=0.055, h_abajo=0.015, h_arriba=0.095, abanico=30.0),
    # Parados: el centro del módulo queda a 5 mm + 22,5 mm = 2,75 cm
    # (transductores a ~1,45 y ~4,05 cm); arriba a 2,75 + 11 = 13,75 cm
    "B parados, abanico 0°": dict(BASE, separacion=0.04, h_abajo=0.0275, h_arriba=0.1375,
                                  abanico=0.0, vertical=True),
    "B parados, abanico 20°": dict(BASE, separacion=0.04, h_abajo=0.0275, h_arriba=0.1375,
                                   abanico=20.0, vertical=True),
    "B parados, abanico 30°": dict(BASE, separacion=0.04, h_abajo=0.0275, h_arriba=0.1375,
                                   abanico=30.0, vertical=True),
    # Referencia: lo recomendado antes, sin límite de placa
    "Ref. sin límite (10 cm, 1,5/12, 20°)": dict(BASE, abanico=20.0),
}


def resumen(res):
    con = [r for r in res if r[0]]
    sin = [r for r in res if not r[0]]
    limpias = [r for r in con if len(r[7]) == 1 and r[2] < 0.30]
    ok = sum(1 for r in limpias if r[4] == r[1])
    det = sum(1 for r in limpias if r[4] is not None)
    fp = defaultdict(lambda: [0, 0])
    for r in sin:
        if len(r[7]) == 1:
            fp[r[5][0]][1] += 1
            fp[r[5][0]][0] += r[4] is not None
    fp_tot = sum(1 for r in sin if r[4] is not None)
    bandas = []
    for a, b in ((0, 0.15), (0.15, 0.25), (0.25, 0.35), (0.35, 0.50)):
        sub = [r for r in con if a <= r[2] < b]
        bandas.append(100 * sum(1 for r in sub if r[4] == r[1]) / max(len(sub), 1))
    return det / max(len(limpias), 1), ok / max(len(limpias), 1), fp_tot / max(len(sin), 1), fp, bandas


if __name__ == "__main__":
    for rp in (2.0, 3.5):
        print(f"\n######## Calibración pared {rp} m ({'conservadora' if rp == 2.0 else 'optimista'}) ########")
        print(f"{'configuración':40s} | canica sola <30cm | col. correcta por distancia (todas las escenas) "
              f"| falsas | falsas por obstáculo (caja_alta/caja_baja/chico/lata/tapita)")
        print(f"{'':40s} | detect.  columna   |  <15    15-25   25-35   35-50 |")
        with Pool() as pool:
            for nombre, cfg in CONFIGS.items():
                res = pool.map(se._trabajo, [(cfg, rp, 5000 + i) for i in range(N)], chunksize=8)
                det, ok, fpt, fp, bandas = resumen(res)
                fps = "/".join(f"{100*fp[k][0]/max(fp[k][1],1):.0f}%"
                               for k in ("caja_alta", "caja_baja", "chico", "lata", "tapita"))
                print(f"{nombre:40s} |  {100*det:4.0f}%   {100*ok:4.0f}%    | "
                      + "  ".join(f"{b:5.0f}%" for b in bandas) + f" | {100*fpt:4.0f}%  | {fps}")
