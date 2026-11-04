"""
Modelo simple del VL53L0X (SIMULACION-UNITY.md §5.2): la lectura es el
promedio de distancias de todo lo que hay en el cono, pesado por
reflectividad * cos(incidencia) * sensibilidad_angular / d^2.

Coordenadas: x adelante, y izquierda, z arriba. Piso z=0. Canica r=8 mm.
Regla del sketch: hay "algo bajo" si lee <= 18 cm y >= 8 cm menos que el reposo.
"""
import sys
import numpy as np

R = 0.008
FOV_MEDIO = np.radians(12.5)   # cono de 25° total
N = 81                         # grilla de rayos N x N


def rayos(pitch_deg, sigma_deg):
    a = np.linspace(-FOV_MEDIO, FOV_MEDIO, N)
    AY, AZ = np.meshgrid(a, a, indexing="ij")
    dentro = AY**2 + AZ**2 <= FOV_MEDIO**2
    AY, AZ = AY[dentro], AZ[dentro]
    w = np.exp(-(AY**2 + AZ**2) / (2 * np.radians(sigma_deg) ** 2))
    p = np.radians(pitch_deg)
    # dirección en el marco del sensor y luego inclinada
    d = np.stack([np.ones_like(AY), np.tan(AY), np.tan(AZ)], 1)
    d /= np.linalg.norm(d, axis=1, keepdims=True)
    rot = np.array([[np.cos(p), 0, -np.sin(p)], [0, 1, 0], [np.sin(p), 0, np.cos(p)]])
    return d @ rot.T, w


def leer(h, pitch, sigma, canica=None, rho_canica=1.0, rho_piso=1.0, umbral=None):
    D, w = rayos(pitch, sigma)
    O = np.array([0.0, 0.0, h])
    t = np.full(len(D), np.inf)
    cosi = np.zeros(len(D))
    rho = np.zeros(len(D))
    # piso
    baja = D[:, 2] < -1e-9
    tp = np.where(baja, -h / np.where(baja, D[:, 2], -1), np.inf)
    t, cosi, rho = tp, np.where(baja, -D[:, 2], 0), np.full(len(D), rho_piso)
    # canica
    if canica is not None:
        C = np.array([canica[0], canica[1], R])
        oc = O - C
        b = D @ oc
        c = oc @ oc - R * R
        disc = b * b - c
        hit = disc > 0
        ts = np.where(hit, -b - np.sqrt(np.where(hit, disc, 0)), np.inf)
        hit &= (ts > 0) & (ts < t)
        P = O + ts[:, None] * D
        n = (P - C) / R
        ci = np.abs((n * D).sum(1))
        t = np.where(hit, ts, t)
        cosi = np.where(hit, ci, cosi)
        rho = np.where(hit, rho_canica, rho)
    fin = np.isfinite(t) & (t < 2.0)
    ret = np.where(fin, rho * cosi * w / np.where(fin, t, 1) ** 2, 0)
    tot = ret.sum()
    if tot <= 0 or (umbral is not None and tot < umbral):
        return 9.99
    # el chip mide desde el sensor; lectura en metros
    return (ret * np.where(fin, t, 0)).sum() / tot


def ajustar():
    """Ajusta sigma y pitch con la 'prueba buena' (canica transparente, rho 0.3)."""
    datos = [(None, 0.37), (0.09, 0.11), (0.15, 0.19), (0.20, 0.34)]
    mejor = None
    for h in (0.010, 0.015, 0.018):
        for sigma in (3, 4, 5, 6, 8, 10, 15):
            for pitch in np.arange(-3, 6.01, 0.25):
                err = 0
                for dist, lec in datos:
                    can = None if dist is None else (dist, 0.0)
                    err += (leer(h, pitch, sigma, can, rho_canica=0.3) - lec) ** 2
                if mejor is None or err < mejor[0]:
                    mejor = (err, h, sigma, pitch)
    return mejor


def detecta(h, pitch, sigma, rho_c, d, y=0.0):
    reposo = leer(h, pitch, sigma)
    lec = leer(h, pitch, sigma, (d, y), rho_canica=rho_c)
    return lec <= 0.18 and (reposo - lec) >= 0.08, lec, reposo


if __name__ == "__main__":
    err, h0, sigma, p0 = ajustar()
    print(f"Ajuste con la prueba de banco: h={h0*100:.1f} cm, sigma={sigma}°, pitch={p0:+.2f}°, "
          f"error rms={np.sqrt(err/4)*100:.1f} cm")
    for dist, lec in [(None, 37), (0.09, 11), (0.15, 19), (0.20, 34)]:
        can = None if dist is None else (dist, 0.0)
        print(f"   {'sin canica' if dist is None else f'canica {dist*100:.0f} cm'}: "
              f"medido {lec} cm, modelo {leer(h0, p0, sigma, can, rho_canica=0.3)*100:.0f} cm")

    DS = np.round(np.arange(0.03, 0.301, 0.01), 3)
    print("\nPor altura del chip: inclinación que deja el reposo en ~37-42 cm, y rango en que detecta la canica")
    print("(canica en el eje; rho = reflectividad: 0,3 transparente, 1,0 y 2,0 blanca opaca)")
    for h in (0.012, 0.015, 0.016, 0.018, 0.020, 0.022, 0.025):
        # inclinación para reposo ~40 cm
        ps = np.arange(-6, 8.01, 0.1)
        rep = np.array([leer(h, p, sigma) for p in ps])
        ok = np.where((rep >= 0.37) & (rep <= 0.42))[0]
        if not len(ok):
            print(f"  h={h*1000:.0f} mm: no hay inclinación que dé reposo 37-42 cm")
            continue
        p = ps[ok[len(ok) // 2]]
        fila = f"  h={h*1000:4.0f} mm  incl {p:+.1f}° (reposo {leer(h, p, sigma)*100:.0f} cm) |"
        for rho in (0.3, 1.0, 2.0):
            det = [d for d in DS if detecta(h, p, sigma, rho, d)[0]]
            fila += f" rho {rho}: " + (f"{min(det)*100:.0f}-{max(det)*100:.0f} cm" if det else "nada") + " |"
        # robustez: errores de inclinación de ±1° con rho 1.0
        for dp in (-1, +1):
            det = [d for d in DS if detecta(h, p + dp, sigma, 1.0, d)[0]]
            fila += f" {dp:+d}°: " + (f"{min(det)*100:.0f}-{max(det)*100:.0f}" if det else "nada")
        print(fila)

    print("\nMismo análisis con inclinación 0° (montado derecho, sin ajustar):")
    for h in (0.012, 0.015, 0.016, 0.018, 0.020, 0.022, 0.025):
        fila = f"  h={h*1000:4.0f} mm  reposo {leer(h, 0, sigma)*100:3.0f} cm |"
        for rho in (0.3, 1.0, 2.0):
            det = [d for d in DS if detecta(h, 0, sigma, rho, d)[0]]
            fila += f" rho {rho}: " + (f"{min(det)*100:.0f}-{max(det)*100:.0f} cm" if det else "nada") + " |"
        print(fila)
