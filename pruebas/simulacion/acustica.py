"""
Modelo acústico de un HC-SR04 para simular la detección de canicas.

Método: óptica física (Kirchhoff). Cada objeto se divide en facetas y se suma
de forma coherente el eco de cada faceta iluminada, teniendo en cuenta:
  - la directividad de los transductores (pistón circular, 40 kHz);
  - el rebote especular en el piso (método de imágenes: 4 caminos posibles);
  - la dispersión difusa del piso (modelo de Lambert, suma incoherente);
  - la absorción del aire;
  - el umbral del receptor: el HC-SR04 reporta el PRIMER eco que lo supera.

Coordenadas en metros: x hacia adelante, y hacia la izquierda, z hacia arriba.
El piso es z = 0.
"""

import numpy as np
from scipy.special import j1

# --- Parámetros físicos -------------------------------------------------------
C_SONIDO = 343.0
FRECUENCIA = 40e3
LAMBDA = C_SONIDO / FRECUENCIA
K = 2 * np.pi / LAMBDA

# Radio efectivo del pistón: ajustado para que cada transductor tenga un haz
# de 55° total a -6 dB (dato de fabricantes de transductores de 16 mm).
RADIO_PISTON = 6.5e-3
# Absorción del aire a 40 kHz (~1,3 dB/m), en neper/m de amplitud
ALFA_AIRE = 1.3 / 8.686
# Coeficiente de reflexión del piso duro (madera, MDF, cerámica)
GAMMA_PISO = 0.95
# Separación entre el centro del emisor y el del receptor en el módulo
SEP_TX_RX = 0.026
# Ráfaga de 8 ciclos -> resolución en distancia c*tau/2
LARGO_PULSO = 8 / FRECUENCIA * C_SONIDO / 2
DIST_MIN = 0.02
DIST_MAX = 4.0  # igual que DIST_MAX_CM del sketch (sin eco)

# Calibración del umbral: una pared plana perpendicular se detecta hasta
# R_PARED_MAX. Con eso queda fijada la ganancia relativa al umbral.
R_PARED_MAX = 3.5


def ganancia(r_pared_max=R_PARED_MAX):
    return 2 * r_pared_max * np.exp(2 * ALFA_AIRE * r_pared_max)


def directividad(cos_t):
    """Directividad de un pistón circular en pantalla (amplitud, con signo)."""
    cos_t = np.clip(cos_t, -1.0, 1.0)
    sen_t = np.sqrt(1 - cos_t**2)
    x = K * RADIO_PISTON * sen_t
    d = np.where(x < 1e-6, 1.0, 2 * j1(np.maximum(x, 1e-9)) / np.maximum(x, 1e-9))
    return np.where(cos_t > 0, d, 0.0)


# --- Sensores -----------------------------------------------------------------
class Sensor:
    """HC-SR04. Acostado: emisor y receptor uno al lado del otro. Parado
    (vertical=True): el módulo girado 90°, el receptor arriba del emisor."""

    def __init__(self, x, y, z, yaw_deg=0.0, pitch_deg=0.0, nombre="", vertical=False):
        self.nombre = nombre
        self.centro = np.array([x, y, z], float)
        yaw, pitch = np.radians(yaw_deg), np.radians(pitch_deg)
        self.eje = np.array([np.cos(pitch) * np.cos(yaw),
                             np.cos(pitch) * np.sin(yaw),
                             np.sin(pitch)])
        if vertical:
            sep = np.array([-np.sin(pitch) * np.cos(yaw), -np.sin(pitch) * np.sin(yaw), np.cos(pitch)])
        else:
            sep = np.array([-np.sin(yaw), np.cos(yaw), 0.0])
        self.tx = self.centro - sep * SEP_TX_RX / 2
        self.rx = self.centro + sep * SEP_TX_RX / 2


def _espejo(p):
    q = np.array(p, float).copy()
    q[..., 2] *= -1
    return q


# --- Objetos como facetas -----------------------------------------------------
class Facetas:
    """Facetas planas: centro P, normal n, tangentes u/v y lados du/dv."""

    def __init__(self, P, n, u, v, du, dv):
        self.P, self.n, self.u, self.v = P, n, u, v
        self.du, self.dv = du, dv
        self.area = du * dv

    @staticmethod
    def unir(lista):
        lista = [f for f in lista if f is not None and len(f.P)]
        if not lista:
            return None
        cat = lambda a: np.concatenate([getattr(f, a) for f in lista])
        return Facetas(cat("P"), cat("n"), cat("u"), cat("v"), cat("du"), cat("dv"))


def esfera(cx, cy, radio, cz=None, paso=1.0e-3):
    """Esfera apoyada en el piso (por defecto el centro queda a z = radio)."""
    cz = radio if cz is None else cz
    n_t = max(8, int(np.pi * radio / paso))
    th = (np.arange(n_t) + 0.5) * np.pi / n_t
    P, N, U, V, DU, DV = [], [], [], [], [], []
    for t in th:
        n_p = max(8, int(2 * np.pi * radio * np.sin(t) / paso))
        ph = (np.arange(n_p) + 0.5) * 2 * np.pi / n_p
        n = np.stack([np.sin(t) * np.cos(ph), np.sin(t) * np.sin(ph),
                      np.full_like(ph, np.cos(t))], 1)
        u = np.stack([np.cos(t) * np.cos(ph), np.cos(t) * np.sin(ph),
                      np.full_like(ph, -np.sin(t))], 1)
        v = np.stack([-np.sin(ph), np.cos(ph), np.zeros_like(ph)], 1)
        P.append(np.array([cx, cy, cz]) + radio * n)
        N.append(n); U.append(u); V.append(v)
        DU.append(np.full(n_p, radio * np.pi / n_t))
        DV.append(np.full(n_p, 2 * np.pi * radio * np.sin(t) / n_p))
    f = Facetas(*(np.concatenate(a) for a in (P, N, U, V, DU, DV)))
    return _sobre_piso(f)


def _rectangulo(origen, eje_u, eje_v, largo_u, largo_v, normal, paso):
    nu = max(1, int(np.ceil(largo_u / paso)))
    nv = max(1, int(np.ceil(largo_v / paso)))
    su = (np.arange(nu) + 0.5) / nu * largo_u
    sv = (np.arange(nv) + 0.5) / nv * largo_v
    SU, SV = np.meshgrid(su, sv, indexing="ij")
    P = origen + SU.reshape(-1, 1) * eje_u + SV.reshape(-1, 1) * eje_v
    m = len(P)
    rep = lambda w: np.repeat(np.asarray(w, float)[None], m, 0)
    return Facetas(P, rep(normal), rep(eje_u), rep(eje_v),
                   np.full(m, largo_u / nu), np.full(m, largo_v / nv))


def caja(cx, cy, ancho, prof, alto, yaw_deg=0.0, paso=3e-3):
    """Caja apoyada en el piso: 4 caras laterales + tapa."""
    a = np.radians(yaw_deg)
    ex = np.array([np.cos(a), np.sin(a), 0.0])   # profundidad
    ey = np.array([-np.sin(a), np.cos(a), 0.0])  # ancho
    ez = np.array([0.0, 0.0, 1.0])
    c = np.array([cx, cy, 0.0])
    caras = []
    for s in (-1, 1):
        o = c + s * ex * prof / 2 - ey * ancho / 2
        caras.append(_rectangulo(o, ey, ez, ancho, alto, s * ex, paso))
        o = c + s * ey * ancho / 2 - ex * prof / 2
        caras.append(_rectangulo(o, ex, ez, prof, alto, s * ey, paso))
    o = c - ex * prof / 2 - ey * ancho / 2 + ez * alto
    caras.append(_rectangulo(o, ex, ey, prof, ancho, ez, paso))
    return Facetas.unir(caras)


def cilindro(cx, cy, radio, alto, paso=3e-3):
    """Cilindro vertical apoyado en el piso (lata, botella, poste)."""
    n_p = max(12, int(2 * np.pi * radio / paso))
    n_z = max(1, int(np.ceil(alto / paso)))
    ph = (np.arange(n_p) + 0.5) * 2 * np.pi / n_p
    zz = (np.arange(n_z) + 0.5) / n_z * alto
    PH, ZZ = np.meshgrid(ph, zz, indexing="ij")
    PH, ZZ = PH.ravel(), ZZ.ravel()
    n = np.stack([np.cos(PH), np.sin(PH), np.zeros_like(PH)], 1)
    u = np.stack([-np.sin(PH), np.cos(PH), np.zeros_like(PH)], 1)
    v = np.repeat([[0.0, 0.0, 1.0]], len(PH), 0)
    P = np.stack([cx + radio * np.cos(PH), cy + radio * np.sin(PH), ZZ], 1)
    m = len(P)
    lat = Facetas(P, n, u, v, np.full(m, 2 * np.pi * radio / n_p), np.full(m, alto / n_z))
    tapa = _disco(cx, cy, alto, radio, paso)
    return Facetas.unir([lat, tapa])


def _disco(cx, cy, z, radio, paso):
    lado = 2 * radio
    f = _rectangulo(np.array([cx - radio, cy - radio, z]), np.array([1.0, 0, 0]),
                    np.array([0, 1.0, 0]), lado, lado, np.array([0, 0, 1.0]), paso)
    dentro = (f.P[:, 0] - cx) ** 2 + (f.P[:, 1] - cy) ** 2 <= radio**2
    return Facetas(f.P[dentro], f.n[dentro], f.u[dentro], f.v[dentro],
                   f.du[dentro], f.dv[dentro])


def _sobre_piso(f):
    ok = f.P[:, 2] > 1e-4
    return Facetas(f.P[ok], f.n[ok], f.u[ok], f.v[ok], f.du[ok], f.dv[ok])


# --- Eco de un objeto ---------------------------------------------------------
def perfil_coherente(sensor, facetas, bins, con_piso=True):
    """Suma compleja de ecos por intervalo de distancia (antes del envolvente)."""
    perfil = np.zeros(len(bins), complex)
    if facetas is None:
        return perfil
    P, n = facetas.P, facetas.n
    db = bins[1] - bins[0]
    caminos = [(0, 0), (0, 1), (1, 0), (1, 1)] if con_piso else [(0, 0)]
    for it, ir in caminos:
        St = _espejo(sensor.tx) if it else sensor.tx
        Sr = _espejo(sensor.rx) if ir else sensor.rx
        vt, vr = P - St, P - Sr
        Rt = np.linalg.norm(vt, axis=1)
        Rr = np.linalg.norm(vr, axis=1)
        ut, ur = vt / Rt[:, None], vr / Rr[:, None]
        ilum_t = -(n * ut).sum(1)
        ilum_r = -(n * ur).sum(1)
        lit = (ilum_t > 0) & (ilum_r > 0)
        if not lit.any():
            continue
        # Dirección con la que el rayo sale/entra por el transductor real
        dt = _espejo(ut) if it else ut
        dr = _espejo(ur) if ir else ur
        D = directividad(dt @ sensor.eje) * directividad(dr @ sensor.eje)
        q = K * (ut + ur)
        sinc = (np.sinc((q * facetas.u).sum(1) * facetas.du / (2 * np.pi))
                * np.sinc((q * facetas.v).sum(1) * facetas.dv / (2 * np.pi)))
        obl = (ilum_t + ilum_r) / 2
        ruta = Rt + Rr
        amp = (K / (2 * np.pi)) * obl * D * GAMMA_PISO ** (it + ir) * sinc \
            * facetas.area * np.exp(-ALFA_AIRE * ruta) / (Rt * Rr)
        c = amp * np.exp(-1j * K * ruta)
        idx = np.clip(((ruta / 2 - bins[0]) / db).astype(int), 0, len(bins) - 1)
        c = np.where(lit, c, 0)
        perfil += np.bincount(idx, c.real, len(bins)) + 1j * np.bincount(idx, c.imag, len(bins))
    return perfil


def potencia_piso(sensor, bins, mu, alcance=2.0, paso=4e-3):
    """Eco difuso del piso (Lambert: sigma0 = mu * sen^2(rasante)), en potencia."""
    pot = np.zeros(len(bins))
    if mu <= 0:
        return pot
    db = bins[1] - bins[0]
    xs = np.arange(paso / 2, alcance, paso)
    for y0 in np.arange(-alcance, alcance, 0.25):
        ys = np.arange(y0 + paso / 2, min(y0 + 0.25, alcance), paso)
        X, Y = np.meshgrid(xs, ys, indexing="ij")
        P = np.stack([X.ravel(), Y.ravel(), np.zeros(X.size)], 1)
        vt, vr = P - sensor.tx, P - sensor.rx
        Rt = np.linalg.norm(vt, axis=1)
        Rr = np.linalg.norm(vr, axis=1)
        D = directividad((vt / Rt[:, None]) @ sensor.eje) * directividad((vr / Rr[:, None]) @ sensor.eje)
        sen_ras = sensor.centro[2] / ((Rt + Rr) / 2)
        sigma0 = mu * sen_ras**2
        p = (D * np.exp(-ALFA_AIRE * (Rt + Rr)) / (Rt * Rr)) ** 2 * sigma0 * paso**2 / (4 * np.pi)
        idx = np.clip((((Rt + Rr) / 2 - bins[0]) / db).astype(int), 0, len(bins) - 1)
        pot += np.bincount(idx, p, len(bins))
    return pot


def ventana_pulso(db):
    """Envolvente del eco recibido (triangular, base = 2 * largo del pulso)."""
    m = int(round(LARGO_PULSO / db))
    w = np.concatenate([np.arange(1, m + 1), np.arange(m - 1, 0, -1)]) / m
    return w


def envolvente(perfil, pot_difusa, db, g):
    w = ventana_pulso(db)
    m = (len(w) - 1) // 2  # alineado: el pico del eco cae en la distancia real
    coh = np.convolve(perfil, w)[m: m + len(perfil)]
    inc = np.convolve(pot_difusa, w**2)[m: m + len(perfil)]
    return g * np.sqrt(np.abs(coh) ** 2 + inc)


def lectura(env, bins, umbral=1.0):
    """Distancia que reportaría el HC-SR04: el primer eco que supera el umbral
    (se toma el pico de ese eco, porque el módulo está calibrado para leer
    la distancia real de un blanco y no el flanco del envolvente)."""
    ok = (env > umbral) & (bins >= DIST_MIN)
    if not ok.any():
        return DIST_MAX
    i = np.argmax(ok)
    m = int(round(LARGO_PULSO / (bins[1] - bins[0])))
    return bins[i + np.argmax(env[i: i + 2 * m])]


BINS = np.arange(0.0, 2.5, 1e-3)
