import sys
from collections import deque

import numpy as np
import matplotlib.pyplot as plt
import serial
from matplotlib.animation import FuncAnimation

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
BAUD = 115200
MAX_MM = 1200  # VL53L0X useful range; readings beyond this are "no detection", not plotted
# ponytail: fixed-position point cloud, no odometry yet — moving the sensor (robot driving)
# will smear the map since points aren't transformed into a world frame. Add wheel
# encoders/IMU + pose transform before trusting this while the robot is in motion.
MAX_POINTS = 8000

ser = serial.Serial(PORT, BAUD, timeout=1)

xs = deque(maxlen=MAX_POINTS)
ys = deque(maxlen=MAX_POINTS)

fig, ax = plt.subplots()
ax.set_aspect("equal")
ax.set_xlim(-MAX_MM, MAX_MM)
ax.set_ylim(0, MAX_MM)
ax.set_xlabel("mm")
ax.set_ylabel("mm (adelante)")
ax.set_title(f"Mapa del entorno — {PORT}")
ax.plot(0, 0, marker="^", color="red", markersize=10, label="sensor")
ax.legend(loc="upper right")
scatter = ax.scatter([], [], s=4, c="steelblue")


def update(_frame):
    line = ser.readline().decode(errors="replace").strip()
    if "," not in line:
        return (scatter,)
    try:
        angle_str, dist_str = line.split(",")
        angle = int(angle_str)
        dist = int(dist_str)
    except ValueError:
        return (scatter,)
    if not (0 <= angle <= 180) or not (0 < dist <= MAX_MM):
        return (scatter,)  # fuera de rango o timeout: no hay pared ahi
    theta = np.radians(angle)
    xs.append(dist * np.cos(theta))
    ys.append(dist * np.sin(theta))
    scatter.set_offsets(np.column_stack([xs, ys]))
    return (scatter,)


ani = FuncAnimation(fig, update, interval=10, cache_frame_data=False)
plt.show()
ser.close()
