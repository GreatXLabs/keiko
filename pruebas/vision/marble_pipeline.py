"""
Pipeline combinado para candidatos a "canica" en una foto del piso de la pista.

No reemplaza a la red neuronal: extrae candidatos + sus features (color, forma,
brillo) para que la red (en la laptop) decida. Mientras no haya un modelo
entrenado, usa un puntaje a mano como sustituto para poder probar el pipeline.

Uso: python3 marble_pipeline.py foto.jpg [salida.png]
"""
import sys
import cv2
import numpy as np

MIN_AREA_FRAC = 0.00015   # ponytail: area minima como fraccion del cuadro, escala con la resolucion
MAX_AREA_FRAC = 0.05      # un candidato mas grande que esto no es una canica a esta distancia
HUE_TOLERANCE = 12        # grados de diferencia de tono vs. el piso para considerarse "no-piso"


def floor_reference_color(hsv):
    """Asume que el piso ocupa la mayor parte del cuadro: su color es la moda, no la media."""
    h, s, v = [hsv[:, :, i] for i in range(3)]
    h_ref = int(np.bincount(h.flatten(), minlength=180).argmax())
    s_ref = float(np.median(s))
    v_ref = float(np.median(v))
    return h_ref, s_ref, v_ref


def not_floor_mask(hsv, h_ref):
    h = hsv[:, :, 0].astype(np.int16)
    hue_dist = np.minimum(np.abs(h - h_ref), 180 - np.abs(h - h_ref))
    mask = (hue_dist > HUE_TOLERANCE).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    return mask


def candidate_features(img, gray, hsv, contour):
    area = cv2.contourArea(contour)
    perim = cv2.arcLength(contour, True)
    if perim == 0:
        return None
    circularity = min(4 * np.pi * area / (perim * perim), 1.0)
    (x, y), radius = cv2.minEnclosingCircle(contour)

    mask = np.zeros(gray.shape, np.uint8)
    cv2.drawContours(mask, [contour], -1, 255, -1)
    mean_sat = float(cv2.mean(hsv[:, :, 1], mask=mask)[0])
    max_brightness = int(gray[mask == 255].max()) if np.any(mask == 255) else 0

    return {
        "center": (x, y), "radius": radius, "area": area,
        "circularity": circularity, "mean_sat": mean_sat, "max_brightness": max_brightness,
    }


def score(feat):
    """Pesos a mano, placeholder hasta tener un dataset para entrenar la red real."""
    circularity_score = feat["circularity"]
    specular_score = min(feat["max_brightness"] / 255.0, 1.0)
    return 0.6 * circularity_score + 0.4 * specular_score


def run(path, out_path):
    img = cv2.imread(path)
    if img is None:
        raise SystemExit(f"no pude leer {path}")
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)

    h_ref, s_ref, v_ref = floor_reference_color(hsv)
    mask = not_floor_mask(hsv, h_ref)

    min_area = MIN_AREA_FRAC * w * h
    max_area = MAX_AREA_FRAC * w * h
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    candidates = []
    for c in contours:
        area = cv2.contourArea(c)
        if not (min_area < area < max_area):
            continue
        feat = candidate_features(img, gray, hsv, c)
        if feat is None:
            continue
        feat["score"] = score(feat)
        candidates.append(feat)

    candidates.sort(key=lambda f: f["score"], reverse=True)

    print(f"{path}: piso de referencia H={h_ref} S={s_ref:.0f} V={v_ref:.0f}")
    print(f"{len(candidates)} candidatos (de {len(contours)} blobs en la mascara)\n")
    print(f"{'score':>6} {'circ':>6} {'brillo':>7} {'sat':>6} {'radio':>6}  centro")
    for f in candidates[:8]:
        print(f"{f['score']:6.2f} {f['circularity']:6.2f} {f['max_brightness']:7d} "
              f"{f['mean_sat']:6.0f} {f['radius']:6.1f}  ({f['center'][0]:.0f},{f['center'][1]:.0f})")

    out = img.copy()
    for i, f in enumerate(candidates[:5]):
        color = (0, 0, 255) if i == 0 else (0, 200, 0)
        x, y = f["center"]
        cv2.circle(out, (int(x), int(y)), max(int(f["radius"]), 4), color, 2)
        cv2.putText(out, f"{f['score']:.2f}", (int(x) + 6, int(y) - 6),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)
    cv2.imwrite(out_path, out)
    print(f"\nanotado -> {out_path}")


if __name__ == "__main__":
    img_path = sys.argv[1]
    out_path = sys.argv[2] if len(sys.argv) > 2 else "pipeline_out.png"
    run(img_path, out_path)
