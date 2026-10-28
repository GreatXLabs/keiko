"""
Mapa de cobertura vista desde arriba: para cada posición de la canica en el
piso, qué columna informa la lógica mejorada (o si no la detecta).
Genera resultados/cobertura.png y resultados/lateral.png.
"""

import os
from multiprocessing import Pool

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap
import numpy as np

import acustica as ac
import sim_escenas as se
import analisis_lateral as al

XS = np.arange(0.03, 0.51, 0.015)
YS = np.arange(-0.30, 0.301, 0.015)
SALIDA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resultados")


def _punto(args):
    cfg, rp, x, y = args
    g = ac.ganancia(rp)
    ss = se.sensores(cfg)
    obj = [se.canica(x, y)]
    lect = [se.leer(s, obj, g) for s in ss]
    col = se.decidir_mejorada(lect, [ac.DIST_MAX] * 6)
    return -1 if col is None else col


def mapa(cfg, rp, pool):
    tareas = [(cfg, rp, x, y) for x in XS for y in YS]
    return np.array(pool.map(_punto, tareas, chunksize=16)).reshape(len(XS), len(YS))


if __name__ == "__main__":
    os.makedirs(SALIDA, exist_ok=True)
    cmap = ListedColormap(["#e8e8e8", "#3b82f6", "#22c55e", "#f59e0b"])
    fig, ejes = plt.subplots(2, 2, figsize=(11, 10), sharex=True, sharey=True)
    with Pool() as pool:
        for i, rp in enumerate((2.0, 3.5)):
            for j, ab in enumerate((0.0, 20.0)):
                cfg = dict(se.CONFIG_BASE, abanico=ab)
                m = mapa(cfg, rp, pool)
                ax = ejes[i, j]
                ax.pcolormesh(YS * 100, XS * 100, m, cmap=cmap, vmin=-1.5, vmax=2.5, shading="nearest")
                for s in se.sensores(cfg)[:3]:
                    ax.annotate("", xy=(s.centro[1] * 100 + s.eje[1] * 8, s.eje[0] * 8),
                                xytext=(s.centro[1] * 100, 0),
                                arrowprops=dict(arrowstyle="->", color="k"))
                ax.set_title(f"Abanico ±{ab:.0f}° · calibración {'conservadora' if rp == 2.0 else 'optimista'}")
                ax.invert_xaxis()
                ax.set_aspect("equal")
                ax.grid(alpha=0.3)
    for ax in ejes[-1]:
        ax.set_xlabel("lateral (cm)  ←  derecha   |   izquierda  →")
    for ax in ejes[:, 0]:
        ax.set_ylabel("distancia hacia adelante (cm)")
    fig.legend(handles=[plt.Rectangle((0, 0), 1, 1, color=c) for c in cmap.colors],
               labels=["no la detecta", "IZQUIERDA", "MEDIO", "DERECHA"], loc="upper center", ncol=4)
    fig.suptitle("Dónde detecta la canica (vista de arriba) — abajo 1,5 cm, arriba 12 cm, columnas a 10 cm",
                 y=0.95)
    fig.savefig(os.path.join(SALIDA, "cobertura.png"), dpi=110, bbox_inches="tight")

    # Vista lateral: eco de la canica vs distancia para distintas alturas
    fig, axs = plt.subplots(1, 2, figsize=(12, 4.5), sharey=True)
    for ax, rp, nombre in zip(axs, (2.0, 3.5), ("conservadora", "optimista")):
        g = ac.ganancia(rp)
        for h in (0.010, 0.015, 0.020, 0.030, 0.050):
            a = al.alcance_canica(h, 0, g)
            ax.plot(al.XS * 100, 20 * np.log10(np.maximum(a, 1e-3)), label=f"eje abajo a {h*100:.1f} cm")
        for H, est in ((0.08, ":"), (0.12, "--")):
            s = ac.Sensor(0, 0, H)
            a = [ac.envolvente(ac.perfil_coherente(s, ac.esfera(x, 0, 0.008), ac.BINS), al.np.zeros(len(ac.BINS)),
                               al.DB, g).max() for x in al.XS]
            ax.plot(al.XS * 100, 20 * np.log10(np.maximum(a, 1e-3)), "k" + est, label=f"sensor de ARRIBA a {H*100:.0f} cm")
        ax.axhline(0, color="r", lw=1)
        ax.axhline(3, color="r", lw=0.6, ls="--")
        ax.set_title(f"Eco de la canica en el eje · calibración {nombre}")
        ax.set_xlabel("distancia a la canica (cm)")
        ax.set_ylim(-25, 25)
        ax.grid(alpha=0.3)
    axs[0].set_ylabel("eco respecto del umbral (dB) — por encima de 0 se detecta")
    axs[1].legend(fontsize=8, loc="upper right")
    fig.savefig(os.path.join(SALIDA, "lateral.png"), dpi=110, bbox_inches="tight")
    print("Listo:", SALIDA)
