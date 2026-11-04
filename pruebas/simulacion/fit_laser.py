import itertools, numpy as np, warnings
warnings.filterwarnings("ignore")
from multiprocessing import Pool
import sim_laser as L
L.N = 61

def leer(h, p, sig, cexp, can=None, rho=1.0):
    # variante con exponente del coseno de incidencia (0 = modelo del doc, 1 = Lambert)
    D, w = L.rayos(p, sig)
    O = np.array([0, 0, h]); baja = D[:, 2] < -1e-9
    t = np.where(baja, -h / np.where(baja, D[:, 2], -1), np.inf); ci = np.where(baja, -D[:, 2], 0); r = np.ones(len(D))
    if can is not None:
        C = np.array([can[0], can[1], L.R]); oc = O - C; b = D @ oc; c = oc @ oc - L.R**2; disc = b*b - c
        hit = disc > 0; ts = np.where(hit, -b - np.sqrt(np.where(hit, disc, 0)), np.inf); hit &= (ts > 0) & (ts < t)
        n = (O + np.where(hit, ts, 0)[:, None] * D - C) / L.R
        t = np.where(hit, ts, t); ci = np.where(hit, np.abs((n * D).sum(1)), ci); r = np.where(hit, rho, r)
    fin = np.isfinite(t) & (t < 2.0)
    ret = np.where(fin, r * ci**cexp * w / np.where(fin, t, 1)**2, 0)
    return 9.99 if ret.sum() <= 0 else (ret * np.where(fin, t, 0)).sum() / ret.sum()

TESTS = [  # (alturas posibles, [(dist canica o None, lectura medida)])
    ((0.010, 0.013, 0.016, 0.020), [(None, .37), (.09, .11), (.15, .19), (.20, .34)]),
    ((0.018,), [(None, .535), (.08, .37)]),
    ((0.015, 0.0175, 0.020), [(None, .19), (.10, .16)]),
]
PS = np.arange(-8, 10.01, 0.5)

def eval_combo(combo):
    sig, cexp, rho = combo
    tot, det = 0, []
    for hs, dat in TESTS:
        best = None
        for h in hs:
            for p in PS:
                e = sum((leer(h, p, sig, cexp, None if d is None else (d, 0), rho) - m)**2 for d, m in dat)
                if best is None or e < best[0]: best = (e, h, p)
        tot += best[0]; det.append(best[1:])
    return np.sqrt(tot / 8), combo, det

if __name__ == "__main__":
    combos = list(itertools.product((4, 6, 8, 10, 15, 30), (0, 1), (0.1, 0.2, 0.3, 0.5, 1.0)))
    with Pool() as pool: res = sorted(pool.map(eval_combo, combos), key=lambda r: r[0])
    for rms, (sig, cexp, rho), det in res[:6]:
        print(f"rms {rms*100:4.1f} cm | sigma {sig}° cos^{cexp} rho_transp {rho} | " +
              "; ".join(f"h={h*1000:.0f}mm p={p:+.1f}°" for h, p in det))
    rms, (sig, cexp, rho), det = res[0]
    print("\nControl del mejor ajuste:")
    for (hs, dat), (h, p) in zip(TESTS, det):
        print("  " + ", ".join(f"{'vacío' if d is None else f'{d*100:.0f}cm'}: med {m*100:.0f} / mod {leer(h,p,sig,cexp,None if d is None else (d,0),rho)*100:.0f}" for d, m in dat))
    np.save("mejor_ajuste.npy", np.array([sig, cexp, rho]))
