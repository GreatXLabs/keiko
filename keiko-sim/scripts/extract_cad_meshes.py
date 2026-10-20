#!/usr/bin/env python3
"""
Extractor y Decimador de Mallas 3D CAD - Proyecto Orquitas (Keiko)
Procesa los modelos del repositorio oficial GreatXLabs/Keiko-Modelos-3D:
1. Separa y decima el cuerpo del chasis 4WD (bandejas, separadores, motores TT) -> orquita_chassis_body.stl
2. Separa y decima la rueda TT (llanta + cubierta de 67.2 mm) -> orquita_wheel.stl
3. Reconstruye la carcasa exterior "Capote" (Chassis.FCStd: cuerpo de 265x160x100 mm con chaflanes,
   hocico frontal, pala de 200 mm y aleta dorsal de orca) -> orquita_capote.stl

Todas las mallas se exportan en formato STL binario en escala de METROS (1 unidad = 1 m)
y en el sistema de coordenadas nativo del robot en Webots (+X frente, +Y izquierda, +Z arriba).
"""

import math
import os
import struct
import subprocess
import urllib.request
from collections import defaultdict


SIM_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MESHES_DIR = os.path.join(SIM_DIR, "meshes")


def compute_normal(v0, v1, v2):
    ux, uy, uz = v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]
    vx, vy, vz = v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]
    nx = uy * vz - uz * vy
    ny = uz * vx - ux * vz
    nz = ux * vy - uy * vx
    length = math.sqrt(nx * nx + ny * ny + nz * nz)
    if length > 1e-12:
        return (nx / length, ny / length, nz / length)
    return (0.0, 0.0, 1.0)


def write_binary_stl(filepath, triangles, header_text="Keiko Orquita 3D Mesh (meters)"):
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    hdr = header_text.encode("ascii")[:80].ljust(80, b" ")
    with open(filepath, "wb") as f:
        f.write(hdr)
        f.write(struct.pack("<I", len(triangles)))
        for v0, v1, v2 in triangles:
            n = compute_normal(v0, v1, v2)
            f.write(struct.pack("<12fH", n[0], n[1], n[2], v0[0], v0[1], v0[2], v1[0], v1[1], v1[2], v2[0], v2[1], v2[2], 0))


def decimate_triangles(triangles, grid_step):
    """Agrupa vértices en una grilla espacial de paso `grid_step` y descarta triángulos degenerados."""
    if grid_step <= 0:
        return triangles

    cell_sum = defaultdict(lambda: [0.0, 0.0, 0.0, 0])
    vert_keys = []

    for v0, v1, v2 in triangles:
        k0 = (round(v0[0] / grid_step), round(v0[1] / grid_step), round(v0[2] / grid_step))
        k1 = (round(v1[0] / grid_step), round(v1[1] / grid_step), round(v1[2] / grid_step))
        k2 = (round(v2[0] / grid_step), round(v2[1] / grid_step), round(v2[2] / grid_step))
        for k, v in ((k0, v0), (k1, v1), (k2, v2)):
            c = cell_sum[k]
            c[0] += v[0]
            c[1] += v[1]
            c[2] += v[2]
            c[3] += 1
        vert_keys.append((k0, k1, k2))

    cell_avg = {k: (c[0] / c[3], c[1] / c[3], c[2] / c[3]) for k, c in cell_sum.items()}

    out = []
    seen = set()
    for k0, k1, k2 in vert_keys:
        if k0 == k1 or k1 == k2 or k2 == k0:
            continue
        face_key = tuple(sorted((k0, k1, k2)))
        if face_key in seen:
            continue
        seen.add(face_key)
        p0, p1, p2 = cell_avg[k0], cell_avg[k1], cell_avg[k2]
        # Verificar área no nula
        ux, uy, uz = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
        vx, vy, vz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
        nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        if (nx * nx + ny * ny + nz * nz) > 1e-16:
            out.append((p0, p1, p2))
    return out


def fetch_assembly_stl_bytes():
    cache_path = "/tmp/Assembly_Main-Orquita.stl"
    if os.path.exists(cache_path) and os.path.getsize(cache_path) > 40_000_000:
        with open(cache_path, "rb") as f:
            return f.read()

    url = subprocess.check_output(
        ["gh", "api", "repos/GreatXLabs/Keiko-Modelos-3D/contents/Modelos%20Exportados/Assembly_Main-Orquita.stl", "--jq", ".download_url"]
    ).decode().strip()
    data = urllib.request.urlopen(url).read()
    with open(cache_path, "wb") as f:
        f.write(data)
    return data


def extract_body_and_wheel_from_assembly(data):
    n_tri = struct.unpack("<I", data[80:84])[0]
    parent = list(range(n_tri))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(i, j):
        ri, rj = find(i), find(j)
        if ri != rj:
            parent[ri] = rj

    vert_to_tri = {}
    raw_tris = []
    bboxes = [[1e9, 1e9, 1e9, -1e9, -1e9, -1e9] for _ in range(n_tri)]

    offset = 84
    for t in range(n_tri):
        vals = struct.unpack_from("<9f", data, offset + 12)
        offset += 50
        v0 = (vals[0], vals[1], vals[2])
        v1 = (vals[3], vals[4], vals[5])
        v2 = (vals[6], vals[7], vals[8])
        raw_tris.append((v0, v1, v2))
        b = bboxes[t]
        for x, y, z in (v0, v1, v2):
            if x < b[0]: b[0] = x
            if y < b[1]: b[1] = y
            if z < b[2]: b[2] = z
            if x > b[3]: b[3] = x
            if y > b[4]: b[4] = y
            if z > b[5]: b[5] = z
            key = (round(x, 3), round(y, 3), round(z, 3))
            prev = vert_to_tri.get(key)
            if prev is not None:
                union(t, prev)
            else:
                vert_to_tri[key] = t

    comp_bbox = {}
    comp_count = defaultdict(int)
    for t in range(n_tri):
        r = find(t)
        comp_count[r] += 1
        b = bboxes[t]
        if r not in comp_bbox:
            comp_bbox[r] = list(b)
        else:
            cb = comp_bbox[r]
            for k in range(3):
                if b[k] < cb[k]: cb[k] = b[k]
                if b[k + 3] > cb[k + 3]: cb[k + 3] = b[k + 3]

    # En FreeCAD Assembly_Main:
    # - Las 4 cubiertas tienen ~103076 tris y tamaño ~(25, 52, 52) o ~(20.2, 67.2, 67.2) para llanta+cubierta
    # - Identificamos la rueda delantera derecha (centro FreeCAD aprox X=+59.6, Y=+58.6, Z=-9.7)
    wheel_roots = set()
    fl_wheel_roots = set()
    for r, cb in comp_bbox.items():
        dx, dy, dz = cb[3] - cb[0], cb[4] - cb[1], cb[5] - cb[2]
        cx, cy, cz = (cb[0] + cb[3]) / 2.0, (cb[1] + cb[4]) / 2.0, (cb[2] + cb[5]) / 2.0
        # Componentes de rueda (llantas de 52mm y neumáticos de 67.2mm)
        if 48.0 <= dy <= 70.0 and 48.0 <= dz <= 70.0 and dx <= 30.0:
            wheel_roots.add(r)
            if cx > 0 and cy > 0:
                fl_wheel_roots.add(r)

    # Transformación de coordenadas FreeCAD (mm) -> Webots Robot (m):
    # En FreeCAD: Y_fc es avance [-128.5, +128.5], X_fc es derecha [-76.15, +73.85] (centro X_fc = -1.15),
    # Z_fc tiene el piso de las ruedas en Z_fc = -43.32 mm.
    # En Webots:
    #   X_wb = Y_fc / 1000.0
    #   Y_wb = -(X_fc - (-1.15)) / 1000.0
    #   Z_wb = (Z_fc - (-43.32)) / 1000.0
    # Para mantener orientación directa (determinante +1), como negamos 1 eje (X_fc -> -Y_wb),
    # invertimos el orden de los vértices (v0, v2, v1).
    def fc_to_wb_body(pt):
        return (
            pt[1] / 1000.0,
            -(pt[0] + 1.15) / 1000.0,
            (pt[2] + 43.32) / 1000.0,
        )

    # Centro de la rueda seleccionada (cx > 0, cy > 0 en FreeCAD -> X_fc ≈ 59.65, Y_fc ≈ 58.56, Z_fc ≈ -9.70)
    def fc_to_wb_wheel(pt):
        return (
            (pt[1] - 58.563) / 1000.0,
            -(pt[0] - 59.65) / 1000.0,
            (pt[2] + 9.70) / 1000.0,
        )

    body_tris = []
    wheel_tris = []

    for t, (v0, v1, v2) in enumerate(raw_tris):
        r = find(t)
        if r not in wheel_roots:
            body_tris.append((fc_to_wb_body(v0), fc_to_wb_body(v2), fc_to_wb_body(v1)))
        elif r in fl_wheel_roots:
            wheel_tris.append((fc_to_wb_wheel(v0), fc_to_wb_wheel(v2), fc_to_wb_wheel(v1)))

    # Decimar para velocidad óptima en Webots (grilla de 1.8 mm para chasis, 1.5 mm para rueda)
    body_dec = decimate_triangles(body_tris, grid_step=0.0018)
    wheel_dec = decimate_triangles(wheel_tris, grid_step=0.0015)
    return body_dec, wheel_dec


def add_quad(tris, p0, p1, p2, p3):
    tris.append((p0, p1, p2))
    tris.append((p0, p2, p3))


def add_extruded_polygon_z(tris, poly_xy, z_bottom, z_top):
    """Construye un prisma cerrado extruyendo un polígono 2D (en sentido antihorario visto desde +Z)."""
    n = len(poly_xy)
    # Tapa superior (z = z_top) y tapa inferior (z = z_bottom) desde el centroide
    cx = sum(p[0] for p in poly_xy) / n
    cy = sum(p[1] for p in poly_xy) / n
    top_c = (cx, cy, z_top)
    bot_c = (cx, cy, z_bottom)

    for i in range(n):
        x0, y0 = poly_xy[i]
        x1, y1 = poly_xy[(i + 1) % n]
        p0_b = (x0, y0, z_bottom)
        p1_b = (x1, y1, z_bottom)
        p1_t = (x1, y1, z_top)
        p0_t = (x0, y0, z_top)
        # Cara lateral exterior
        add_quad(tris, p0_b, p1_b, p1_t, p0_t)
        # Tapa superior
        tris.append((top_c, p0_t, p1_t))
        # Tapa inferior
        tris.append((bot_c, p1_b, p0_b))


def build_capote_mesh():
    """
    Reconstruye la malla 3D exacta del Capote diseñada en Chassis.FCStd (convertida a metros en Webots):
    1. Cuerpo principal (Pad): X in [-0.1325, +0.1325], Y in [-0.080, +0.080], Z in [0.0, 0.100]
       con chaflanes traseros de 29 mm en las esquinas (-0.1325, ±0.080).
    2. Trompa / Hocico medio (Pad002): X in [0.0975, 0.1675], Y in [-0.055, +0.055], Z in [0.0175, 0.0825].
    3. Pala recolectora frontal (Pad003 + Pad004 + Chamfer001):
       X in [0.1375, 0.1975], Y in [-0.100, +0.100], Z in [0.0425, 0.0525] con chaflanes frontales de 16 mm.
    4. Aleta dorsal de Orca (Pad005):
       Perfil en X in [-0.0744, +0.0354], Y in [-0.005, +0.005], Z in [0.100, 0.156].
    """
    tris = []

    # 1. Cuerpo principal con chaflán trasero de 29 mm (0.029 m)
    c_rear = 0.029
    main_poly = [
        (-0.1325 + c_rear, -0.080),
        (0.1325, -0.080),
        (0.1325, 0.080),
        (-0.1325 + c_rear, 0.080),
        (-0.1325, 0.080 - c_rear),
        (-0.1325, -0.080 + c_rear),
    ]
    add_extruded_polygon_z(tris, main_poly, 0.002, 0.100)

    # 2. Hocico frontal intermedio
    snout_poly = [
        (0.0975, -0.055),
        (0.1675, -0.055),
        (0.1675, 0.055),
        (0.0975, 0.055),
    ]
    add_extruded_polygon_z(tris, snout_poly, 0.0175, 0.0825)

    # 3. Pala frontal de 200 mm de ancho con chaflanes de 16 mm (0.016 m)
    c_front = 0.016
    blade_poly = [
        (0.1375, -0.100),
        (0.1975 - c_front, -0.100),
        (0.1975, -0.100 + c_front),
        (0.1975, 0.100 - c_front),
        (0.1975 - c_front, 0.100),
        (0.1375, 0.100),
    ]
    add_extruded_polygon_z(tris, blade_poly, 0.0425, 0.0525)

    # 4. Aleta dorsal de Orca (extruida en Y entre -0.005 y +0.005)
    # Vértices exactos tomados del BRep de Chassis.FCStd (Sketch004 + Pad005)
    fin_xz = [
        (-0.07439, 0.10000),
        (-0.00939, 0.10000),
        (0.00661, 0.10000),
        (0.00060, 0.11249),
        (0.03541, 0.15600),
        (-0.00059, 0.15600),
        (-0.04771, 0.13335),
    ]
    y_left = 0.005
    y_right = -0.005
    n_f = len(fin_xz)
    cx = sum(p[0] for p in fin_xz) / n_f
    cz = sum(p[1] for p in fin_xz) / n_f
    c_left = (cx, y_left, cz)
    c_right = (cx, y_right, cz)

    for i in range(n_f):
        x0, z0 = fin_xz[i]
        x1, z1 = fin_xz[(i + 1) % n_f]
        p0_r = (x0, y_right, z0)
        p1_r = (x1, y_right, z1)
        p1_l = (x1, y_left, z1)
        p0_l = (x0, y_left, z0)
        add_quad(tris, p0_r, p1_r, p1_l, p0_l)
        tris.append((c_left, p1_l, p0_l))
        tris.append((c_right, p0_r, p1_r))

    return tris


def generate_all_meshes(meshes_dir=MESHES_DIR):
    os.makedirs(meshes_dir, exist_ok=True)
    print("Descargando / leyendo ensamblaje CAD desde Keiko-Modelos-3D...")
    stl_data = fetch_assembly_stl_bytes()
    body_tris, wheel_tris = extract_body_and_wheel_from_assembly(stl_data)
    capote_tris = build_capote_mesh()

    body_path = os.path.join(meshes_dir, "orquita_chassis_body.stl")
    wheel_path = os.path.join(meshes_dir, "orquita_wheel.stl")
    capote_path = os.path.join(meshes_dir, "orquita_capote.stl")

    write_binary_stl(body_path, body_tris, "Orquita 4WD Chassis Body (Keiko-Modelos-3D)")
    write_binary_stl(wheel_path, wheel_tris, "Orquita 4WD Wheel TT 67.2mm (Keiko-Modelos-3D)")
    write_binary_stl(capote_path, capote_tris, "Orquita Capote Shell (Keiko-Modelos-3D)")

    print(f"  -> {body_path} ({len(body_tris)} tris, {os.path.getsize(body_path) / 1024:.1f} KB)")
    print(f"  -> {wheel_path} ({len(wheel_tris)} tris, {os.path.getsize(wheel_path) / 1024:.1f} KB)")
    print(f"  -> {capote_path} ({len(capote_tris)} tris, {os.path.getsize(capote_path) / 1024:.1f} KB)")
    return {
        "body": body_path,
        "wheel": wheel_path,
        "capote": capote_path,
    }


if __name__ == "__main__":
    generate_all_meshes()
