import sys
import time

import numpy as np
import serial

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
BAUD = 115200
MAX_MM = 1200
CAPTURE_TIMEOUT_S = 6  # tope de seguridad; normalmente corta antes, al detectar que el servo invirtio


def capture_scan(ser):
    """Captura un solo barrido (una direccion), cortando apenas el servo invierte sentido.
    Mantiene los puntos en orden angular, necesario para dibujarlos como linea sin zigzag."""
    ser.reset_input_buffer()
    points = []
    last_angle = None
    direction = None
    deadline = time.time() + CAPTURE_TIMEOUT_S
    while time.time() < deadline:
        line = ser.readline().decode(errors="replace").strip()
        if "," not in line:
            continue
        try:
            angle_str, dist_str = line.split(",")
            angle, dist = int(angle_str), int(dist_str)
        except ValueError:
            continue

        if last_angle is not None and angle != last_angle:
            this_dir = 1 if angle > last_angle else -1
            if direction is None:
                direction = this_dir
            elif this_dir != direction:
                break  # el barrido invirtio sentido: este escaneo esta completo
        last_angle = angle
        if not (0 <= angle <= 180) or not (0 < dist <= MAX_MM):
            continue
        theta = np.radians(angle)
        points.append((dist * np.cos(theta), dist * np.sin(theta)))
    return np.array(points)


def icp_2d(src, dst, max_iterations=50, tolerance=1e-6):
    src = src.copy()
    R_total = np.eye(2)
    t_total = np.zeros(2)
    prev_error = None
    for _ in range(max_iterations):
        dists = np.linalg.norm(src[:, None, :] - dst[None, :, :], axis=2)
        matched = dst[np.argmin(dists, axis=1)]

        src_mean, matched_mean = src.mean(axis=0), matched.mean(axis=0)
        H = (src - src_mean).T @ (matched - matched_mean)
        U, _, Vt = np.linalg.svd(H)
        R = Vt.T @ U.T
        if np.linalg.det(R) < 0:
            Vt[-1, :] *= -1
            R = Vt.T @ U.T
        t = matched_mean - R @ src_mean

        src = (R @ src.T).T + t
        R_total = R @ R_total
        t_total = R @ t_total + t

        error = float(np.mean(np.linalg.norm(matched - src, axis=1)))
        if prev_error is not None and abs(prev_error - error) < tolerance:
            break
        prev_error = error
    angle_deg = np.degrees(np.arctan2(R_total[1, 0], R_total[0, 0]))
    return R_total, t_total, angle_deg, prev_error


def main():
    ser = serial.Serial(PORT, BAUD, timeout=1)
    time.sleep(2)  # dejar que el Nano reinicie tras abrir el puerto
    print(f"Capturando cada {CAPTURE_SECONDS}s automaticamente. Move el sensor despacio. Ctrl+C para salir.\n")

    prev_scan = capture_scan(ser)
    cum_pos = np.zeros(2)
    cum_theta = 0.0  # radianes

    try:
        while True:
            scan = capture_scan(ser)
            if len(prev_scan) < 5 or len(scan) < 5:
                print("Pocos puntos validos, sigo...")
                prev_scan = scan
                continue

            _R, t, angle_deg, error = icp_2d(prev_scan, scan)

            # componer la pose global: rotar el desplazamiento relativo por el heading actual antes de sumarlo
            c, s = np.cos(cum_theta), np.sin(cum_theta)
            cum_pos += np.array([c * t[0] - s * t[1], s * t[0] + c * t[1]])
            cum_theta += np.radians(angle_deg)

            print(f"paso: dx={t[0]:+.0f}mm dy={t[1]:+.0f}mm rot={angle_deg:+.1f}°  err={error:.1f}mm"
                  f"  | acumulado: x={cum_pos[0]:.0f}mm y={cum_pos[1]:.0f}mm heading={np.degrees(cum_theta):.1f}°")
            prev_scan = scan
    except KeyboardInterrupt:
        pass
    finally:
        ser.close()


if __name__ == "__main__":
    main()
