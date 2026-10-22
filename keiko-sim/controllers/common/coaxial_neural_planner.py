"""
Clasificador Neuronal por Par Coaxial (Neuroph 2.98) y Planificador de Navegación
para la Orquita (Keiko).

Evalúa la misma red neuronal entrenada en Neuroph 2.98 (2 entradas: d_bajo, d_alto ->
4 salidas: BLOQUEO, ESPACIO_LIBRE, LOBITO, SALIDA) sobre cada uno de los 3 pares
frontales [IZQ, CENTRO, DER] y ejecuta el algoritmo de toma de decisiones con
memoria de maniobra (retroceso + giro comprometido y detector anti-bucle), sin
utilizar coordenadas GPS ni mapas del Supervisor.
"""

import json
import math
import os


def _sigmoid(x):
    if x < -60.0:
        return 0.0
    if x > 60.0:
        return 1.0
    return 1.0 / (1.0 + math.exp(-x))


class CoaxialNeuralClassifier:
    """Ejecuta los pesos exactos de la red Neuroph 2.98 (orquitas_par_coaxial_neuroph.nnet)."""

    def __init__(self, weights_json_path=None):
        if weights_json_path is None:
            base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
            weights_json_path = os.path.join(base_dir, "data", "models", "orquitas_par_coaxial_weights.json")
        with open(weights_json_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        self.min_cm = float(data.get("min_cm", 2.0))
        self.max_cm = float(data.get("max_cm", 250.0))
        self.classes = list(data["classes"])
        self.layers = data["layers"]

    def classify_pair(self, d_low_cm, d_high_cm):
        span = self.max_cm - self.min_cm
        n_low = max(0.0, min(1.0, (float(d_low_cm) - self.min_cm) / span))
        n_high = max(0.0, min(1.0, (float(d_high_cm) - self.min_cm) / span))
        activations = [n_low, n_high]

        for layer in self.layers:
            next_act = []
            for neuron in layer:
                w = neuron["weights"]
                b = neuron["bias"]
                s = b
                for i in range(min(len(w), len(activations))):
                    s += w[i] * activations[i]
                next_act.append(_sigmoid(s))
            activations = next_act

        best_idx = 0
        best_val = activations[0]
        for i in range(1, len(activations)):
            if activations[i] > best_val:
                best_val = activations[i]
                best_idx = i
        return self.classes[best_idx]

    def classify_3_pairs(self, readings):
        d_ll = readings.get("us_low_left", 250.0)
        d_hl = readings.get("us_high_left", 250.0)
        d_lr = readings.get("us_low_right", 250.0)
        d_hr = readings.get("us_high_right", 250.0)
        d_lc = readings.get("us_low_center", 0.5 * (d_ll + d_lr))
        d_hc = readings.get("us_high_center", 0.5 * (d_hl + d_hr))

        c_izq = self.classify_pair(d_ll, d_hl)
        c_cen = self.classify_pair(d_lc, d_hc)
        c_der = self.classify_pair(d_lr, d_hr)
        return [c_izq, c_cen, c_der]


class CoaxialNavigationPlanner:
    """
    Algoritmo de decisión motriz basado exclusivamente en la terna neuronal
    [clase_izq, clase_centro, clase_der] y memoria de maniobras anti-bucle.
    """

    def __init__(self):
        self.state = "BUSQUEDA"
        self.reverse_steps_left = 0
        self.turn_steps_left = 0
        self.turn_dir = 1          # +1 = giro a derecha (vL > vR), -1 = giro a izquierda (vL < vR)
        self.last_blocked_side = 0 # -1 = izquierda, +1 = derecha
        self.same_side_block_cycles = 0
        self.cycles_since_block = 100
        self.oscillation_count = 0
        self.cycle_count = 0
        self.escape_sweep_dir = 1  # Dirección preferida de barrido perimetral en ESCAPE

    def activate_escape_mode(self):
        self.state = "ESCAPE"
        self.reverse_steps_left = 0
        self.turn_steps_left = 0

    def evaluate(self, pairs, captures_count=0):
        """
        Recibe pairs = [c_izq, c_cen, c_der] y devuelve (v_left, v_right, new_state, action_desc).
        """
        self.cycle_count += 1
        self.cycles_since_block += 1

        if captures_count >= 3 and self.state != "FINALIZACION":
            self.state = "ESCAPE"

        if self.state in ("FINALIZACION", "ERROR_SEGURO"):
            return 0.0, 0.0, self.state, "DETENIDO"

        izq, cen, der = pairs[0], pairs[1], pairs[2]
        b_izq = (izq == "BLOQUEO")
        b_cen = (cen == "BLOQUEO")
        b_der = (der == "BLOQUEO")

        # 1. Interrupción oportunista de giro comprometido si engancha objetivo con el centro libre
        if self.reverse_steps_left == 0 and self.turn_steps_left > 0:
            if self.state != "ESCAPE" and not b_cen and ("LOBITO" in (izq, cen, der)):
                self.turn_steps_left = 0
            elif self.state == "ESCAPE" and not b_cen and ("SALIDA" in (izq, cen, der)):
                self.turn_steps_left = 0

        # 2. Ejecución de maniobra comprometida activa (marcha atrás o giro post-retroceso)
        if self.reverse_steps_left > 0:
            self.reverse_steps_left -= 1
            est = "ESCAPE" if self.state == "ESCAPE" else "BUSQUEDA"
            return -4.6, -4.6, est, f"MANIOBRA_RETROCESO({self.reverse_steps_left})"

        if self.turn_steps_left > 0:
            self.turn_steps_left -= 1
            est = "ESCAPE" if self.state == "ESCAPE" else "BUSQUEDA"
            vg = 4.8
            if self.turn_dir >= 0:
                return vg, -vg, est, f"MANIOBRA_GIRO_DER({self.turn_steps_left})"
            else:
                return -vg, vg, est, f"MANIOBRA_GIRO_IZQ({self.turn_steps_left})"

        # 3. Encierro frontal total ([BLOQUEO, BLOQUEO, BLOQUEO] o esquina [BLOQUEO, *, BLOQUEO])
        if (b_izq and b_cen and b_der) or (b_izq and b_der):
            self.oscillation_count += 1
            is_loop = self.oscillation_count >= 3
            self.reverse_steps_left = 11 if is_loop else 7
            self.turn_steps_left = 15 if is_loop else 10
            if is_loop:
                self.oscillation_count = 0
                self.escape_sweep_dir = -self.escape_sweep_dir
            self.turn_dir = self.escape_sweep_dir if self.state == "ESCAPE" else (1 if self.last_blocked_side <= 0 else -1)
            self.cycles_since_block = 0
            est = "ESCAPE" if self.state == "ESCAPE" else "BUSQUEDA"
            return -4.6, -4.6, est, "INICIO_RETROCESO_FRONTAL"

        # 4. Modo ESCAPE (3 canicas capturadas: busca y cruza SALIDA ignorando canicas)
        if self.state == "ESCAPE":
            s_izq = (izq == "SALIDA")
            s_cen = (cen == "SALIDA")
            s_der = (der == "SALIDA")

            if s_cen:
                if b_izq and not b_der:
                    return 4.6, 3.6, "ESCAPE", "ESCAPE_SALIDA_CORR_DER"
                if b_der and not b_izq:
                    return 3.6, 4.6, "ESCAPE", "ESCAPE_SALIDA_CORR_IZQ"
                return 4.4, 4.4, "ESCAPE", "ESCAPE_SALIDA_DIRECTA"

            if s_izq:
                if b_cen or b_der:
                    return -4.2, 4.2, "ESCAPE", "ESCAPE_ALINEAR_SALIDA_IZQ"
                return 2.4, 4.6, "ESCAPE", "ESCAPE_DOBLAR_SALIDA_IZQ"

            if s_der:
                if b_cen or b_izq:
                    return 4.2, -4.2, "ESCAPE", "ESCAPE_ALINEAR_SALIDA_DER"
                return 4.6, 2.4, "ESCAPE", "ESCAPE_DOBLAR_SALIDA_DER"

            if b_izq and b_cen:
                self._record_side_block(-1)
                self.turn_steps_left = 6
                self.turn_dir = 1
                return 4.6, -4.6, "ESCAPE", "ESCAPE_ESQUIVE_DER"
            if b_cen and b_der:
                self._record_side_block(1)
                self.turn_steps_left = 6
                self.turn_dir = -1
                return -4.6, 4.6, "ESCAPE", "ESCAPE_ESQUIVE_IZQ"
            if b_cen:
                self.turn_steps_left = 7
                self.turn_dir = self.escape_sweep_dir
                return 4.6 * self.turn_dir, -4.6 * self.turn_dir, "ESCAPE", "ESCAPE_ESQUIVE_CENTRO"
            if b_izq:
                self._record_side_block(-1)
                return 4.4, 2.6, "ESCAPE", "ESCAPE_COSTEAR_IZQ"
            if b_der:
                self._record_side_block(1)
                return 2.6, 4.4, "ESCAPE", "ESCAPE_COSTEAR_DER"

            bias = 0.35 if ((self.cycle_count // 45) % 2 == 0) else -0.35
            return 4.8 + bias, 4.8 - bias, "ESCAPE", "ESCAPE_BARRIDO"

        # 5. Modo BUSQUEDA / APROXIMACION (Capturas < 3)
        # Prioridad 5a: Evitar salir accidentalmente por el hueco de salida de 30 cm antes de tiempo
        s_izq = (izq == "SALIDA")
        s_cen = (cen == "SALIDA")
        s_der = (der == "SALIDA")

        if s_cen or (s_izq and s_der):
            self.turn_steps_left = 9
            self.turn_dir = 1 if self.last_blocked_side <= 0 else -1
            return 4.6 * self.turn_dir, -4.6 * self.turn_dir, "BUSQUEDA", "REINGRESO_DESDE_SALIDA"
        if s_izq:
            self.turn_steps_left = 6
            self.turn_dir = 1
            return 4.6, -4.6, "BUSQUEDA", "EVITAR_SALIDA_IZQ"
        if s_der:
            self.turn_steps_left = 6
            self.turn_dir = -1
            return -4.6, 4.6, "BUSQUEDA", "EVITAR_SALIDA_DER"

        # Prioridad 5b: Detección y persecución de presas (LOBITO)
        l_izq = (izq == "LOBITO")
        l_cen = (cen == "LOBITO")
        l_der = (der == "LOBITO")

        if l_cen:
            if b_izq and not b_der:
                return 3.6, 3.0, "APROXIMACION", "CAPTURA_CENTRO_ABRE_DER"
            if b_der and not b_izq:
                return 3.0, 3.6, "APROXIMACION", "CAPTURA_CENTRO_ABRE_IZQ"
            return 3.8, 3.8, "APROXIMACION", "CAPTURA_LOBITO_CENTRO"

        if l_izq:
            if b_cen:
                return -3.4, 3.4, "APROXIMACION", "GIRO_LOBITO_IZQ"
            return 1.2, 4.2, "APROXIMACION", "APROX_LOBITO_IZQ"

        if l_der:
            if b_cen:
                return 3.4, -3.4, "APROXIMACION", "GIRO_LOBITO_DER"
            return 4.2, 1.2, "APROXIMACION", "APROX_LOBITO_DER"

        # Prioridad 5c: Esquive y deflexión de muros/obstáculos en BUSQUEDA (<= 22 cm)
        if b_izq and b_cen:
            self._record_side_block(-1)
            if self.same_side_block_cycles >= 5 or self.oscillation_count >= 3:
                self._start_anti_loop(1)
                return -4.6, -4.6, "BUSQUEDA", "ANTIBUCLE_RETROCESO"
            self.turn_steps_left = 6
            self.turn_dir = 1
            return 4.8, -4.8, "BUSQUEDA", "ESQUIVE_DOBLE_IZQ"

        if b_cen and b_der:
            self._record_side_block(1)
            if self.same_side_block_cycles >= 5 or self.oscillation_count >= 3:
                self._start_anti_loop(-1)
                return -4.6, -4.6, "BUSQUEDA", "ANTIBUCLE_RETROCESO"
            self.turn_steps_left = 6
            self.turn_dir = -1
            return -4.8, 4.8, "BUSQUEDA", "ESQUIVE_DOBLE_DER"

        if b_cen:
            d = 1 if self.last_blocked_side <= 0 else -1
            self.turn_steps_left = 14
            self.turn_dir = d
            return 4.8 * d, -4.8 * d, "BUSQUEDA", "DEFLEXION_OBSTACULO_CENTRO"

        if b_izq:
            self._record_side_block(-1)
            if self.same_side_block_cycles >= 4 or self.oscillation_count >= 3:
                self._start_anti_loop(1)
                return -4.6, -4.6, "BUSQUEDA", "ANTIBUCLE_RETROCESO"
            self.turn_steps_left = 12
            self.turn_dir = 1
            return 4.8, -4.8, "BUSQUEDA", "DEFLEXION_PARED_IZQ"

        if b_der:
            self._record_side_block(1)
            if self.same_side_block_cycles >= 4 or self.oscillation_count >= 3:
                self._start_anti_loop(-1)
                return -4.6, -4.6, "BUSQUEDA", "ANTIBUCLE_RETROCESO"
            self.turn_steps_left = 12
            self.turn_dir = -1
            return -4.8, 4.8, "BUSQUEDA", "DEFLEXION_PARED_DER"

        if self.cycles_since_block > 30:
            self.oscillation_count = 0
            self.same_side_block_cycles = 0

        sweep = 0.55 if ((self.cycle_count // 50) % 2 == 0) else -0.55
        return 4.8 + sweep, 4.8 - sweep, "BUSQUEDA", f"EXPLORACION_LIBRE[{izq[0]},{cen[0]},{der[0]}]"

    def _record_side_block(self, side):
        if self.last_blocked_side == side:
            self.same_side_block_cycles += 1
        else:
            self.same_side_block_cycles = 1
            if self.last_blocked_side != 0 and self.cycles_since_block < 22:
                self.oscillation_count += 1
        if self.cycles_since_block >= 30:
            self.oscillation_count = 0
        self.last_blocked_side = side
        self.cycles_since_block = 0

    def _start_anti_loop(self, preferred_dir):
        self.oscillation_count = 0
        self.same_side_block_cycles = 0
        self.reverse_steps_left = 9
        self.turn_steps_left = 13
        self.turn_dir = preferred_dir
