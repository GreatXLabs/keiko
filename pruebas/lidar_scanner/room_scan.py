import sys
import time
from collections import deque

import numpy as np
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation
from matplotlib.widgets import Button
import serial

from scan_match import capture_scan, icp_2d

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
BAUD = 115200
HISTORY = 6  # ponytail: solo se muestran los ultimos N barridos (desvanecidos), no toda la sesion
# — así el drift del ICP no termina emborronando todo en una nube; si despues
# queres mas "memoria" del recorrido completo, subí este numero.

ser = serial.Serial(PORT, BAUD, timeout=1)
time.sleep(2)  # dejar que el Nano reinicie tras abrir el puerto


def fresh_state():
    return {
        "prev_scan": None,
        "cum_pos": np.zeros(2),
        "cum_theta": 0.0,
        "scan_history": deque(maxlen=HISTORY),  # cada item: puntos globales de un barrido, en orden angular
        "trail_x": [0.0],
        "trail_y": [0.0],
    }


state = fresh_state()

fig, ax = plt.subplots()
fig.subplots_adjust(bottom=0.15)
ax.set_aspect("equal")
ax.set_xlabel("mm")
ax.set_ylabel("mm")
ax.set_title(f"Mapa del entorno (scan matching) — {PORT}")

alphas = np.linspace(0.15, 1.0, HISTORY)
scan_lines = [ax.plot([], [], "-o", ms=3, lw=1.5, c="steelblue", alpha=a)[0] for a in alphas]
scan_lines[-1].set_label("barrido mas reciente")
(trail_line,) = ax.plot([], [], c="orange", lw=1, label="trayectoria estimada")
pos_marker = ax.scatter([0], [0], c="red", marker="^", s=80, label="sensor")
ax.legend(loc="upper right")
ax.set_xlim(-300, 300)
ax.set_ylim(-300, 300)


def reset(_event=None):
    state.update(fresh_state())


fig.canvas.mpl_connect("key_press_event", lambda e: reset() if e.key == "r" else None)

reset_button = Button(fig.add_axes([0.81, 0.02, 0.15, 0.06]), "Reset")
reset_button.on_clicked(reset)


def rescale_view():
    hist = state["scan_history"]
    xs = [p[0] for scan in hist for p in scan] + state["trail_x"] + [state["cum_pos"][0]]
    ys = [p[1] for scan in hist for p in scan] + state["trail_y"] + [state["cum_pos"][1]]
    margin = 150
    span = max(max(xs) - min(xs), max(ys) - min(ys), 300) + 2 * margin
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    ax.set_xlim(cx - span / 2, cx + span / 2)
    ax.set_ylim(cy - span / 2, cy + span / 2)


def update(_frame):
    scan = capture_scan(ser)
    if len(scan) < 5:
        return (*scan_lines, trail_line, pos_marker)

    if state["prev_scan"] is None or len(state["prev_scan"]) < 5:
        global_pts = scan  # primer escaneo valido: coincide con el marco global (origen)
    else:
        _R, t, angle_deg, _err = icp_2d(state["prev_scan"], scan)
        c, s = np.cos(state["cum_theta"]), np.sin(state["cum_theta"])
        state["cum_pos"] = state["cum_pos"] + np.array([c * t[0] - s * t[1], s * t[0] + c * t[1]])
        state["cum_theta"] += np.radians(angle_deg)
        cg, sg = np.cos(state["cum_theta"]), np.sin(state["cum_theta"])
        Rg = np.array([[cg, -sg], [sg, cg]])
        global_pts = (Rg @ scan.T).T + state["cum_pos"]

    state["scan_history"].append(global_pts)
    state["trail_x"].append(state["cum_pos"][0])
    state["trail_y"].append(state["cum_pos"][1])
    state["prev_scan"] = scan

    hist = list(state["scan_history"])
    pad = HISTORY - len(hist)
    for i, line_artist in enumerate(scan_lines):
        if i < pad:
            line_artist.set_data([], [])
        else:
            pts = hist[i - pad]
            line_artist.set_data(pts[:, 0], pts[:, 1])

    trail_line.set_data(state["trail_x"], state["trail_y"])
    pos_marker.set_offsets([state["cum_pos"]])
    rescale_view()
    return (*scan_lines, trail_line, pos_marker)


ani = FuncAnimation(fig, update, interval=50, cache_frame_data=False)
plt.show()
ser.close()
