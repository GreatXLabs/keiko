#!/usr/bin/env python3
"""
Recolector y Balanceador Dinámico de Datasets en Simulación - Proyecto Orquitas (Keiko)
Registra en tiempo real las lecturas sensoriales físicamente etiquetadas durante
las iteraciones de la simulación en Webots 3D con bahías irregulares:

1. dataset_ultrasonico_v2.csv:
   - dist_us, class_label (BLOQUEO, ESPACIO_LIBRE, SALIDA)
2. dataset_canicas_v2.csv:
   - d_us_izq, d_us_cen, d_us_der, d_laser_izq, d_laser_der, angulo_servo, class_label
     (NINGUNA, LOBITO_IZQ, LOBITO_CENTRO, LOBITO_DER)

Aplica filtrado temporal inteligente (evita spamear muestras idénticas) y control de
balance de clases para prevenir el sesgo de 'data starvation' sobre clases raras.
"""

import os
import csv
import math
from typing import List, Tuple, Dict, Optional, Any


class SimulationDatasetRecorder:
    def __init__(self, output_dir: Optional[str] = None, enabled: bool = True, sample_interval_steps: int = 6):
        self.enabled = enabled
        self.sample_interval_steps = max(1, sample_interval_steps)
        self.step_counter = 0

        if output_dir is None:
            base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "data", "datasets"))
            self.output_dir = os.path.join(base_dir, "v2_simulaciones")
        else:
            self.output_dir = output_dir

        self.us_csv_path = os.path.join(self.output_dir, "dataset_ultrasonico_v2.csv")
        self.marbles_csv_path = os.path.join(self.output_dir, "dataset_canicas_v2.csv")

        self.counts_us: Dict[str, int] = {"BLOQUEO": 0, "ESPACIO_LIBRE": 0, "SALIDA": 0}
        self.counts_marbles: Dict[str, int] = {"NINGUNA": 0, "LOBITO_IZQ": 0, "LOBITO_CENTRO": 0, "LOBITO_DER": 0}

        # Contadores de la corrida actual
        self.session_counts_us: Dict[str, int] = {"BLOQUEO": 0, "ESPACIO_LIBRE": 0, "SALIDA": 0}
        self.session_counts_marbles: Dict[str, int] = {"NINGUNA": 0, "LOBITO_IZQ": 0, "LOBITO_CENTRO": 0, "LOBITO_DER": 0}

        if self.enabled:
            os.makedirs(self.output_dir, exist_ok=True)
            self._ensure_csv_files()

    def _ensure_csv_files(self):
        """Inicializa los archivos con sus cabeceras oficiales si no existen."""
        if not os.path.exists(self.us_csv_path) or os.path.getsize(self.us_csv_path) == 0:
            with open(self.us_csv_path, "w", newline="", encoding="utf-8") as f:
                writer = csv.writer(f)
                writer.writerow(["dist_us", "class_label"])

        if not os.path.exists(self.marbles_csv_path) or os.path.getsize(self.marbles_csv_path) == 0:
            with open(self.marbles_csv_path, "w", newline="", encoding="utf-8") as f:
                writer = csv.writer(f)
                writer.writerow([
                    "d_us_izq", "d_us_cen", "d_us_der",
                    "d_laser_izq", "d_laser_der", "angulo_servo",
                    "class_label"
                ])

        # Leer conteos acumulados existentes
        self._load_existing_counts()

    def _load_existing_counts(self):
        try:
            if os.path.exists(self.us_csv_path):
                with open(self.us_csv_path, "r", encoding="utf-8") as f:
                    r = csv.reader(f)
                    next(r, None)
                    for row in r:
                        if len(row) >= 2 and row[1] in self.counts_us:
                            self.counts_us[row[1]] += 1

            if os.path.exists(self.marbles_csv_path):
                with open(self.marbles_csv_path, "r", encoding="utf-8") as f:
                    r = csv.reader(f)
                    next(r, None)
                    for row in r:
                        if len(row) >= 7 and row[6] in self.counts_marbles:
                            self.counts_marbles[row[6]] += 1
        except Exception:
            pass

    def record_step(
        self,
        us_readings: Dict[str, float],
        laser_left: float,
        laser_right: float,
        servo_deg: float,
        robot_pose: Tuple[float, float, float],
        active_marbles: List[Tuple[float, float]],
        active_obstacles: List[Tuple[float, float]],
        exit_pos: Tuple[float, float],
        exit_yaw: float = math.pi / 2.0
    ):
        """
        Registra una muestra sensorial físicamente etiquetada si corresponde según frecuencia y balance.
        """
        if not self.enabled:
            return

        self.step_counter += 1
        rx, ry, yaw = robot_pose

        # 1. Determinar si hay canica en rango útil de detección
        best_marble_rel = None
        min_m_dist = 999.0
        for mx, my in active_marbles:
            dx = mx - rx
            dy = my - ry
            dist_m = math.hypot(dx, dy)
            fwd = dx * math.cos(yaw) + dy * math.sin(yaw)
            lat = -dx * math.sin(yaw) + dy * math.cos(yaw)
            if dist_m < min_m_dist:
                min_m_dist = dist_m
                best_marble_rel = (dist_m, fwd, lat)

        has_near_marble = (best_marble_rel is not None and best_marble_rel[0] <= 0.35)

        # Muestreo forzado en eventos clave (proximidad a canica) o regular cada N pasos
        should_sample = (self.step_counter % self.sample_interval_steps == 0) or (has_near_marble and (self.step_counter % 2 == 0))
        if not should_sample:
            return

        # -------------------------------------------------------------
        # 1. ETIQUETADO Y REGISTRO DE RED 2: CANICAS (6 ENTRADAS -> 4 CLASES)
        # -------------------------------------------------------------
        marble_label = "NINGUNA"
        if best_marble_rel is not None:
            dist_m, fwd, lat = best_marble_rel
            # Detección óptica en cono frontal
            if 0.02 <= fwd <= 0.25 and abs(lat) <= 0.16:
                if abs(lat) <= 0.038:
                    marble_label = "LOBITO_CENTRO"
                elif lat > 0.038:
                    marble_label = "LOBITO_IZQ"
                else:
                    marble_label = "LOBITO_DER"
            elif (laser_left < 23.5 or laser_right < 23.5) and dist_m <= 0.28:
                if laser_left < 23.5 and laser_right < 23.5 and abs(laser_left - laser_right) <= 5.0:
                    marble_label = "LOBITO_CENTRO"
                elif laser_left < laser_right:
                    marble_label = "LOBITO_IZQ"
                else:
                    marble_label = "LOBITO_DER"

        # Control de balance: regular la sobreabundancia de 'NINGUNA'
        record_marble = True
        if marble_label == "NINGUNA":
            # Si NINGUNA ya supera ampliamente al resto en la sesión, decimar 85%
            max_lobito = max(self.session_counts_marbles["LOBITO_CENTRO"],
                             self.session_counts_marbles["LOBITO_IZQ"],
                             self.session_counts_marbles["LOBITO_DER"])
            if self.session_counts_marbles["NINGUNA"] > (max_lobito * 2 + 10):
                if (self.step_counter % 20 != 0):
                    record_marble = False

        if record_marble:
            row_marble = [
                f"{us_readings.get('us_left', 250.0):.2f}",
                f"{us_readings.get('us_center', 250.0):.2f}",
                f"{us_readings.get('us_right', 250.0):.2f}",
                f"{laser_left:.2f}",
                f"{laser_right:.2f}",
                f"{servo_deg:.1f}",
                marble_label
            ]
            try:
                with open(self.marbles_csv_path, "a", newline="", encoding="utf-8") as f:
                    csv.writer(f).writerow(row_marble)
                self.counts_marbles[marble_label] += 1
                self.session_counts_marbles[marble_label] += 1
            except Exception:
                pass

        # -------------------------------------------------------------
        # 2. ETIQUETADO Y REGISTRO DE RED 1: ULTRASONIDO (1 ENTRADA -> 3 CLASES)
        # -------------------------------------------------------------
        # Comprobar si el robot está alineado con la salida
        dist_to_exit = math.hypot(rx - exit_pos[0], ry - exit_pos[1])
        angle_to_exit = math.atan2(exit_pos[1] - ry, exit_pos[0] - rx)
        heading_err = abs((yaw - angle_to_exit + math.pi) % (2.0 * math.pi) - math.pi)
        facing_door = (heading_err < math.radians(35.0) and dist_to_exit < 1.20)

        for s_name in ("us_left", "us_center", "us_right"):
            d = us_readings.get(s_name, 250.0)
            if d < 2.0 or d > 250.0:
                continue

            if d <= 16.0:
                lbl = "BLOQUEO"
            elif d >= 180.0 and facing_door:
                lbl = "SALIDA"
            elif 17.0 <= d <= 165.0:
                lbl = "ESPACIO_LIBRE"
            elif d > 165.0 and not facing_door:
                # Lectura larga en bahía elíptica profunda que no da directo a la puerta
                lbl = "ESPACIO_LIBRE"
            else:
                continue

            # Balancear ESPACIO_LIBRE para no saturar el dataset
            record_us = True
            if lbl == "ESPACIO_LIBRE":
                if self.session_counts_us["ESPACIO_LIBRE"] > (self.session_counts_us["BLOQUEO"] + self.session_counts_us["SALIDA"] * 5 + 15):
                    if (self.step_counter % 12 != 0):
                        record_us = False

            if record_us:
                try:
                    with open(self.us_csv_path, "a", newline="", encoding="utf-8") as f:
                        csv.writer(f).writerow([f"{d:.2f}", lbl])
                    self.counts_us[lbl] += 1
                    self.session_counts_us[lbl] += 1
                except Exception:
                    pass

    def get_session_summary(self) -> Dict[str, Any]:
        return {
            "session_us": dict(self.session_counts_us),
            "session_marbles": dict(self.session_counts_marbles),
            "total_us": dict(self.counts_us),
            "total_marbles": dict(self.counts_marbles),
            "us_path": self.us_csv_path,
            "marbles_path": self.marbles_csv_path,
        }
