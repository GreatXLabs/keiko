#!/usr/bin/env python3
"""
Herramienta de Consolidación y Balanceo de Datasets V2 - Proyecto Orquitas (Keiko)
Inspecciona, audita y combina los datasets recolectados en vivo durante las simulaciones
(v2_simulaciones/) con el dataset base calibrado (backup_v1_pre_simulaciones/).

Uso:
    # Ver estadísticas del nuevo dataset de simulación
    python3 keiko-sim/scripts/consolidar_dataset_v2.py --status

    # Combinar simulación + base garantizando balance de clases
    python3 keiko-sim/scripts/consolidar_dataset_v2.py --merge --target-per-class=1500

    # Promover el dataset combinado a producción para reentrenar Neuroph
    python3 keiko-sim/scripts/consolidar_dataset_v2.py --deploy
"""

import os
import sys
import csv
import random
import argparse
from typing import Dict, List, Any

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATASETS_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", "data", "datasets"))
BACKUP_DIR = os.path.join(DATASETS_DIR, "backup_v1_pre_simulaciones")
V2_DIR = os.path.join(DATASETS_DIR, "v2_simulaciones")

US_PROD = os.path.join(DATASETS_DIR, "dataset_ultrasonico.csv")
CANICAS_PROD = os.path.join(DATASETS_DIR, "dataset_canicas.csv")

US_V2 = os.path.join(V2_DIR, "dataset_ultrasonico_v2.csv")
CANICAS_V2 = os.path.join(V2_DIR, "dataset_canicas_v2.csv")

US_BACKUP = os.path.join(BACKUP_DIR, "dataset_ultrasonico.csv")
CANICAS_BACKUP = os.path.join(BACKUP_DIR, "dataset_canicas.csv")


def count_classes(filepath: str, label_col_idx: int) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    if not os.path.exists(filepath):
        return counts
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            reader = csv.reader(f)
            next(reader, None)
            for row in reader:
                if len(row) > label_col_idx:
                    lbl = row[label_col_idx].strip()
                    counts[lbl] = counts.get(lbl, 0) + 1
    except Exception as e:
        print(f"[WARN] Error leyendo {filepath}: {e}", file=sys.stderr)
    return counts


def print_status():
    print("\n" + "=" * 76)
    print("  📊 ESTADO DE DATASETS NEUROPH 2.98 (BASE V1 vs SIMULACIÓN V2)")
    print("=" * 76)

    print("\n1. Dataset Base Original (Backup V1 - Calibrado):")
    b_us = count_classes(US_BACKUP, 1)
    b_marbles = count_classes(CANICAS_BACKUP, 6)
    print(f"   • Ultrasonido ({sum(b_us.values())} filas) : {b_us}")
    print(f"   • Canicas     ({sum(b_marbles.values())} filas) : {b_marbles}")

    print("\n2. Dataset V2 Recolectado en Simulación (v2_simulaciones/):")
    v2_us = count_classes(US_V2, 1)
    v2_marbles = count_classes(CANICAS_V2, 6)
    print(f"   • Ultrasonido ({sum(v2_us.values())} filas) : {v2_us if v2_us else 'Sin muestras aún'}")
    print(f"   • Canicas     ({sum(v2_marbles.values())} filas) : {v2_marbles if v2_marbles else 'Sin muestras aún'}")

    print("\n3. Dataset Activo en Producción (keiko-sim/data/datasets/):")
    p_us = count_classes(US_PROD, 1)
    p_marbles = count_classes(CANICAS_PROD, 6)
    print(f"   • Ultrasonido ({sum(p_us.values())} filas) : {p_us}")
    print(f"   • Canicas     ({sum(p_marbles.values())} filas) : {p_marbles}")
    print("=" * 76 + "\n")


def merge_dataset(
    backup_path: str,
    v2_path: str,
    output_path: str,
    label_col_idx: int,
    expected_classes: List[str],
    target_per_class: int = 1500,
    seed: int = 42
):
    rng = random.Random(seed)

    rows_by_class: Dict[str, List[List[str]]] = {c: [] for c in expected_classes}
    header = []

    # 1. Cargar muestras reales de la simulación V2
    if os.path.exists(v2_path):
        with open(v2_path, "r", encoding="utf-8") as f:
            r = csv.reader(f)
            header = next(r, [])
            for row in r:
                if len(row) > label_col_idx:
                    lbl = row[label_col_idx].strip()
                    if lbl in rows_by_class:
                        rows_by_class[lbl].append(row)

    # 2. Rellenar / complementar con las muestras base del backup V1
    if os.path.exists(backup_path):
        with open(backup_path, "r", encoding="utf-8") as f:
            r = csv.reader(f)
            if not header:
                header = next(r, [])
            else:
                next(r, None)
            base_rows: Dict[str, List[List[str]]] = {c: [] for c in expected_classes}
            for row in r:
                if len(row) > label_col_idx:
                    lbl = row[label_col_idx].strip()
                    if lbl in base_rows:
                        base_rows[lbl].append(row)

            for c in expected_classes:
                needed = target_per_class - len(rows_by_class[c])
                if needed > 0 and len(base_rows[c]) > 0:
                    rng.shuffle(base_rows[c])
                    rows_by_class[c].extend(base_rows[c][:needed])

    # 3. Consolidar, mezclar aleatoriamente y guardar
    final_rows = []
    final_counts = {}
    for c in expected_classes:
        c_rows = rows_by_class[c]
        if len(c_rows) > target_per_class:
            rng.shuffle(c_rows)
            c_rows = c_rows[:target_per_class]
        final_rows.extend(c_rows)
        final_counts[c] = len(c_rows)

    rng.shuffle(final_rows)

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        writer.writerows(final_rows)

    print(f"  ✅ Consolidado guardado en: {output_path}")
    print(f"     Total muestras: {len(final_rows)} | Distribución: {final_counts}")


def main():
    parser = argparse.ArgumentParser(description="Consolidador de Datasets V2 para Neuroph - Proyecto Orquitas")
    parser.add_argument("--status", action="store_true", help="Muestra el conteo de muestras por clase")
    parser.add_argument("--merge", action="store_true", help="Combina muestras V2 con base V1 balanceada")
    parser.add_argument("--deploy", action="store_true", help="Copia los datasets consolidados a producción")
    parser.add_argument("--target-per-class", type=int, default=1500, help="Muestras por clase deseadas (default: 1500)")
    args = parser.parse_args()

    if not any([args.status, args.merge, args.deploy]):
        args.status = True

    if args.status:
        print_status()

    if args.merge or args.deploy:
        out_us = os.path.join(V2_DIR, "dataset_ultrasonico_consolidado.csv")
        out_canicas = os.path.join(V2_DIR, "dataset_canicas_consolidado.csv")

        print("\n⚙️  Consolidando Red 1: Ultrasonido...")
        merge_dataset(
            backup_path=US_BACKUP,
            v2_path=US_V2,
            output_path=out_us,
            label_col_idx=1,
            expected_classes=["BLOQUEO", "ESPACIO_LIBRE", "SALIDA"],
            target_per_class=args.target_per_class
        )

        print("\n⚙️  Consolidando Red 2: Canicas...")
        merge_dataset(
            backup_path=CANICAS_BACKUP,
            v2_path=CANICAS_V2,
            output_path=out_canicas,
            label_col_idx=6,
            expected_classes=["LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "NINGUNA"],
            target_per_class=args.target_per_class
        )

        if args.deploy:
            print("\n🚀 Desplegando datasets consolidados a producción...")
            import shutil
            shutil.copyfile(out_us, US_PROD)
            shutil.copyfile(out_canicas, CANICAS_PROD)
            print(f"  ✅ Reemplazado {US_PROD}")
            print(f"  ✅ Reemplazado {CANICAS_PROD}")
            print("  🎉 Ahora puedes ejecutar el reentrenamiento en Neuroph con los datos actualizados.")


if __name__ == "__main__":
    main()
