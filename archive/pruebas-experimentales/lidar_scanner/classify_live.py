import sys
import time
from collections import deque

import serial

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB1"
BAUD = 115200
DURATION_S = 20
WINDOW = 10  # ponytail: ventana chica para reaccionar rapido al movimiento de mano
SNAPSHOT_EVERY = 0.5  # segundos entre impresiones
OUT_OF_RANGE_MM = 1200
WALL_STD_MAX = 2.5  # mm; por debajo = superficie estable (pared), por encima = inestable (posible canica)

ser = serial.Serial(PORT, BAUD, timeout=1)
time.sleep(2)
ser.reset_input_buffer()

window = deque(maxlen=WINDOW)
start = time.time()
last_print = 0.0

while time.time() - start < DURATION_S:
    line = ser.readline().decode(errors="replace").strip()
    if not line:
        continue
    if line == "TIMEOUT":
        window.append(None)
    else:
        try:
            window.append(int(line))
        except ValueError:
            continue

    now = time.time() - start
    if now - last_print < SNAPSHOT_EVERY or len(window) < WINDOW:
        continue
    last_print = now

    valid = [v for v in window if v is not None and v <= OUT_OF_RANGE_MM]
    if len(valid) < WINDOW // 2:
        label = "NADA (fuera de rango)"
        info = ""
    else:
        mean = sum(valid) / len(valid)
        std = (sum((v - mean) ** 2 for v in valid) / len(valid)) ** 0.5
        if std < WALL_STD_MAX:
            label = "PARED / superficie plana"
        else:
            label = "CANICA? (lectura inestable)"
        info = f"dist={mean:.0f}mm std={std:.2f}mm"

    print(f"t={now:4.1f}s  {info:28s} -> {label}")

ser.close()
