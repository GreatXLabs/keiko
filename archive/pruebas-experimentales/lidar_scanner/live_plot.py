import sys
import serial
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
BAUD = 115200
MAX_MM = 1200  # VL53L0X useful range; 8191 ("out of range") gets clipped to this

ser = serial.Serial(PORT, BAUD, timeout=1)

angles = np.radians(np.arange(0, 181))
dists = np.zeros_like(angles)

fig = plt.figure()
ax = fig.add_subplot(projection="polar")
ax.set_theta_zero_location("N")
ax.set_theta_direction(-1)
ax.set_thetamin(0)
ax.set_thetamax(180)
scatter = ax.scatter(angles, dists, s=8, c=dists, cmap="viridis", vmin=0, vmax=MAX_MM)
ax.set_rmax(MAX_MM)
ax.set_autoscaley_on(False)


def update(_frame):
    line = ser.readline().decode(errors="replace").strip()
    if "," not in line:
        return (scatter,)
    try:
        angle_str, dist_str = line.split(",")
        angle = int(angle_str)
        dist = min(int(dist_str), MAX_MM)
    except ValueError:
        return (scatter,)
    if not (0 <= angle <= 180):
        return (scatter,)
    dists[angle] = dist
    scatter.set_offsets(np.column_stack([angles, dists]))
    scatter.set_array(dists)
    return (scatter,)


ani = FuncAnimation(fig, update, interval=10, cache_frame_data=False)
plt.title(f"LiDAR scan — {PORT}")
plt.show()
ser.close()
