import numpy as np, warnings; warnings.filterwarnings("ignore")
from multiprocessing import Pool
import fit_laser as F
SIG, CEXP = 4, 0
DS = np.round(np.arange(0.03, 0.301, 0.01), 3)
H = (0.012, 0.014, 0.015, 0.016, 0.018, 0.020, 0.022, 0.025)

def rango(h, p, rho, y=0.0):
    rep = F.leer(h, p, SIG, CEXP)
    det = [d for d in DS if (l := F.leer(h, p, SIG, CEXP, (d, y), rho)) <= 0.18 and rep - l >= 0.08]
    return (min(det), max(det), len(det)) if det else None

def fila(h):
    ps = np.arange(-6, 10.01, 0.1)
    rep = np.array([F.leer(h, p, SIG, CEXP) for p in ps])
    ok = np.where((rep >= 0.37) & (rep <= 0.42))[0]
    out = [f"h={h*1000:4.1f} mm | piso entra al cono (incl 0°) a {h/np.tan(np.radians(12.5))*100:4.1f} cm |"]
    if not len(ok):
        out.append(" ninguna inclinación da reposo 37-42 cm"); return "".join(out)
    p = ps[ok[len(ok)//2]]
    out.append(f" incl {p:+.1f}° |")
    for rho, nom in ((0.1, "transp"), (0.3, "blanca x3"), (1.0, "blanca x10")):
        r = rango(h, p, rho); out.append(f" {nom}: " + (f"{r[0]*100:.0f}-{r[1]*100:.0f}" if r else "nada") + " |")
    for dp in (-1, 1):
        r = rango(h, p + dp, 0.3); out.append(f" {dp:+d}°: " + (f"{r[0]*100:.0f}-{r[1]*100:.0f}" if r else "nada"))
    r = rango(h, p, 0.3, y=0.02); out.append(f" | canica 2 cm de costado: " + (f"{r[0]*100:.0f}-{r[1]*100:.0f}" if r else "nada"))
    return "".join(out)

if __name__ == "__main__":
    print("Rango (cm) en que la regla del sketch marca la canica, con inclinación ajustada a reposo ~40 cm")
    with Pool() as pool:
        for l in pool.map(fila, H): print(l)
