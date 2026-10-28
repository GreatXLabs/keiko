"""Chequeos del modelo contra casos con resultado conocido."""

import numpy as np
import acustica as ac

g = ac.ganancia()
db = ac.BINS[1] - ac.BINS[0]


def pico(sensor, facetas, con_piso=False, mu=0.0):
    perf = ac.perfil_coherente(sensor, facetas, ac.BINS, con_piso)
    pot = ac.potencia_piso(sensor, ac.BINS, mu) if mu else np.zeros(len(ac.BINS))
    env = ac.envolvente(perf, pot, db, g)
    return env.max(), ac.lectura(env, ac.BINS)


# 1) Placa grande a 0,5 m, sin piso: debe dar g / (2R) (fórmula de pared)
s = ac.Sensor(0, 0, 1.0)
placa = ac._rectangulo(np.array([0.5, -0.4, 0.6]), np.array([0, 1.0, 0]),
                       np.array([0, 0, 1.0]), 0.8, 0.8, np.array([-1.0, 0, 0]), 2e-3)
amp, lec = pico(s, placa)
esperado = g / (2 * 0.5) * np.exp(-2 * ac.ALFA_AIRE * 0.5)
print(f"Pared 0,5 m: amplitud {amp:.2f} (teórica {esperado:.2f}), lectura {lec*100:.1f} cm")

# 2) Esfera de 16 mm en el eje, en el aire (sin piso): sigma = pi r^2
for R in (0.10, 0.15, 0.20):
    bola = ac.esfera(R, 0, 0.008, cz=1.0)
    amp, lec = pico(s, bola)
    teo = g * 0.008 / (2 * R**2) * np.exp(-2 * ac.ALFA_AIRE * R)
    print(f"Canica en el aire a {R*100:.0f} cm: amplitud {amp:.2f} (teórica {teo:.2f}), "
          f"lectura {lec*100:.1f} cm")

# 3) Alcance máximo sobre la canica en el aire (sin piso)
for R in np.arange(0.10, 0.30, 0.01):
    amp, _ = pico(s, ac.esfera(R, 0, 0.008, cz=1.0))
    if amp < 1:
        print(f"Canica en el aire: deja de detectarse a ~{R*100:.0f} cm")
        break
