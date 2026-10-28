"""Opción A con abanico de 30°: efecto de inclinar la fila de arriba y de columnas a 5,4 cm."""

from multiprocessing import Pool
import analisis_placa as ap, sim_escenas as se
base = ap.CONFIGS["A acostados, abanico 30°"]
cfgs = {f"A 30°, arriba inclinado {p}°": dict(base, pitch_arriba=p) for p in (0, 5, 10)}
cfgs["A 30°, columnas a 5,4 cm"] = dict(base, separacion=0.054)
if __name__ == "__main__":
    for rp in (2.0, 3.5):
        print(f"\n## calibración {rp} m")
        with Pool() as pool:
            for n, c in cfgs.items():
                res = pool.map(se._trabajo, [(c, rp, 5000 + i) for i in range(ap.N)], chunksize=8)
                det, ok, fpt, fp, b = ap.resumen(res)
                print(f"{n:32s} det {100*det:3.0f}%  col {100*ok:3.0f}%  bandas " + " ".join(f"{x:3.0f}%" for x in b) + f"  falsas {100*fpt:3.0f}%")
