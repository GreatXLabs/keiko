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


# 150 ciclos a 32 ms por ciclo ~ 4.8 s: cubre el cruce completo de la bahía.
HORIZONTE_OLVIDO_OSCILACIONES = 150
UMBRAL_OSCILACIONES_ANTIBUCLE = 2
TAM_HISTORIAL_LADOS = 6

VEL_AVANCE_ARCO = 4.8
VEL_ARCO_LENTA = 2.0
VEL_RETRO_ARCO_RAPIDA = -4.2
VEL_RETRO_ARCO_NORMAL = -2.6
VEL_RETRO_ARCO_BLOQUEO = -2.4
VEL_RETRO_ARCO_ANTIBUCLE = -2.0
VEL_GIRO_COMPROMETIDO = 4.6
VEL_GIRO_AMPLIO = 5.0
SESGO_GIRO_ANTIBUCLE = 0.4

PASOS_RETRO_ARCO_NORMAL = 6
PASOS_RETRO_ARCO_TOTAL = 10
PASOS_RETRO_ARCO_ANTIBUCLE = 12
PASOS_GIRO_BLOQUEO = 12
PASOS_GIRO_ANTIBUCLE = 16


class CoaxialNavigationPlanner:
    """
    Algoritmo de decisión motriz basado exclusivamente en la terna neuronal
    [clase_izq, clase_centro, clase_der] y memoria de maniobras anti-bucle.
    """

    def __init__(self):
        self.state = "BUSQUEDA"
        self.reverse_steps_left = 0
        self.reverse_left_speed = VEL_RETRO_ARCO_RAPIDA
        self.reverse_right_speed = VEL_RETRO_ARCO_RAPIDA
        self.turn_steps_left = 0
        self.turn_dir = 1          # +1 = giro a derecha (vL > vR), -1 = giro a izquierda (vL < vR)
        self.turn_speed = VEL_GIRO_COMPROMETIDO
        self.turn_bias = 0.0
        self.last_blocked_side = 0 # -1 = izquierda, +1 = derecha
        self.same_side_block_cycles = 0
        self.cycles_since_block = HORIZONTE_OLVIDO_OSCILACIONES
        self.oscillation_count = 0
        self.cycle_count = 0
        self.side_history = [0] * TAM_HISTORIAL_LADOS
        self.side_history_index = 0
        self.side_history_count = 0

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

        # 2. Ejecución de maniobra comprometida activa (marcha atrás o giro post-retroceso).
        # El retroceso puede ser en arco asimétrico para que la cola siga la estela libre.
        if self.reverse_steps_left > 0:
            self.reverse_steps_left -= 1
            est = "ESCAPE" if self.state == "ESCAPE" else "BUSQUEDA"
            return (self.reverse_left_speed, self.reverse_right_speed, est,
                    f"MANIOBRA_RETROCESO({self.reverse_steps_left})")

        if self.turn_steps_left > 0:
            self.turn_steps_left -= 1
            est = "ESCAPE" if self.state == "ESCAPE" else "BUSQUEDA"
            if self.turn_dir >= 0:
                return (self.turn_speed, -self.turn_speed + self.turn_bias, est,
                        f"MANIOBRA_GIRO_DER({self.turn_steps_left})")
            return (-self.turn_speed + self.turn_bias, self.turn_speed, est,
                    f"MANIOBRA_GIRO_IZQ({self.turn_steps_left})")

        # 3. Encierro frontal total ([BLOQUEO, BLOQUEO, BLOQUEO] o esquina [BLOQUEO, *, BLOQUEO]).
        # En ESCAPE, si el centro ve SALIDA el robot ya enfila el hueco: avanzar aunque
        # las jambas laterales aparezcan como BLOQUEO (apertura de solo 30 cm).
        salida_frontal_en_escape = self.state == "ESCAPE" and cen == "SALIDA"
        if not salida_frontal_en_escape and ((b_izq and b_cen and b_der) or (b_izq and b_der)):
            self.oscillation_count += 1
            is_loop = (self.oscillation_count >= UMBRAL_OSCILACIONES_ANTIBUCLE
                       or self._is_alternating())
            reverse_steps = PASOS_RETRO_ARCO_ANTIBUCLE if is_loop else PASOS_RETRO_ARCO_TOTAL
            self._start_arc_reverse(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_BLOQUEO, reverse_steps)
            turn_dir = 1 if self.state == "ESCAPE" else (1 if self.last_blocked_side <= 0 else -1)
            if is_loop:
                self.oscillation_count = 0
                self._reset_side_history()
                self._start_committed_turn(
                    turn_dir, PASOS_GIRO_ANTIBUCLE, VEL_GIRO_AMPLIO, SESGO_GIRO_ANTIBUCLE)
            else:
                self._start_committed_turn(turn_dir, PASOS_GIRO_BLOQUEO, VEL_GIRO_AMPLIO)
            self.cycles_since_block = 0
            est = "ESCAPE" if self.state == "ESCAPE" else "BUSQUEDA"
            return VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_BLOQUEO, est, "INICIO_RETROCESO_FRONTAL"

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
                if b_cen:
                    return self._start_arc_reverse_and_pivot(-1, 7, "ESCAPE")
                if b_der:
                    self._record_side_block(1)
                    if self._should_start_anti_loop():
                        return self._activate_anti_loop(-1, "ESCAPE")
                    return VEL_ARCO_LENTA, VEL_AVANCE_ARCO, "ESCAPE", "ESCAPE_COSTEAR_MURO_DER"
                return 2.4, 4.6, "ESCAPE", "ESCAPE_DOBLAR_SALIDA_IZQ"

            if s_der:
                if b_cen:
                    return self._start_arc_reverse_and_pivot(1, 7, "ESCAPE")
                if b_izq:
                    self._record_side_block(-1)
                    if self._should_start_anti_loop():
                        return self._activate_anti_loop(1, "ESCAPE")
                    return VEL_AVANCE_ARCO, VEL_ARCO_LENTA, "ESCAPE", "ESCAPE_COSTEAR_MURO_IZQ"
                return 4.6, 2.4, "ESCAPE", "ESCAPE_DOBLAR_SALIDA_DER"

            # Aún no ve SALIDA: los bloqueos laterales usan arco hacia adelante;
            # el centro bloqueado o el encierro exigen retroceso en arco antes de pivotar.
            if b_izq and b_cen:
                self._record_side_block(-1)
                if self._should_start_anti_loop():
                    return self._activate_anti_loop(1, "ESCAPE")
                return self._start_arc_reverse_and_pivot(1, 7, "ESCAPE")
            if b_cen and b_der:
                self._record_side_block(1)
                if self._should_start_anti_loop():
                    return self._activate_anti_loop(-1, "ESCAPE")
                return self._start_arc_reverse_and_pivot(-1, 7, "ESCAPE")
            if b_cen:
                return self._start_arc_reverse_and_pivot(1, 7, "ESCAPE")
            if b_izq:
                self._record_side_block(-1)
                if self._should_start_anti_loop():
                    return self._activate_anti_loop(1, "ESCAPE")
                return VEL_AVANCE_ARCO, VEL_ARCO_LENTA, "ESCAPE", "ESCAPE_ESQUIVE_IZQ"
            if b_der:
                self._record_side_block(1)
                if self._should_start_anti_loop():
                    return self._activate_anti_loop(-1, "ESCAPE")
                return VEL_ARCO_LENTA, VEL_AVANCE_ARCO, "ESCAPE", "ESCAPE_ESQUIVE_DER"

            bias = 0.35 if ((self.cycle_count // 45) % 2 == 0) else -0.35
            return 4.8 + bias, 4.8 - bias, "ESCAPE", "ESCAPE_BARRIDO"

        # 5. Modo BUSQUEDA / APROXIMACION (Capturas < 3)
        # Prioridad 5a: Evitar salir accidentalmente por el hueco de salida de 30 cm antes de tiempo
        s_izq = (izq == "SALIDA")
        s_cen = (cen == "SALIDA")
        s_der = (der == "SALIDA")

        if s_cen or (s_izq and s_der):
            d = 1 if self.last_blocked_side <= 0 else -1
            self._start_committed_turn(d, 9)
            return (VEL_GIRO_COMPROMETIDO * d, -VEL_GIRO_COMPROMETIDO * d,
                    "BUSQUEDA", "REINGRESO_DESDE_SALIDA")
        if s_izq:
            self._start_committed_turn(1, 6)
            return (VEL_GIRO_COMPROMETIDO, -VEL_GIRO_COMPROMETIDO,
                    "BUSQUEDA", "EVITAR_SALIDA_IZQ")
        if s_der:
            self._start_committed_turn(-1, 6)
            return (-VEL_GIRO_COMPROMETIDO, VEL_GIRO_COMPROMETIDO,
                    "BUSQUEDA", "EVITAR_SALIDA_DER")

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
            if self.same_side_block_cycles >= 5 or self._should_start_anti_loop():
                return self._activate_anti_loop(1, "BUSQUEDA")
            return self._start_arc_reverse_and_pivot(1, 7, "BUSQUEDA")

        if b_cen and b_der:
            self._record_side_block(1)
            if self.same_side_block_cycles >= 5 or self._should_start_anti_loop():
                return self._activate_anti_loop(-1, "BUSQUEDA")
            return self._start_arc_reverse_and_pivot(-1, 7, "BUSQUEDA")

        if b_cen:
            d = 1 if self.last_blocked_side <= 0 else -1
            return self._start_arc_reverse_and_pivot(d, 14, "BUSQUEDA")

        if b_izq:
            self._record_side_block(-1)
            if self.same_side_block_cycles >= 4 or self._should_start_anti_loop():
                return self._activate_anti_loop(1, "BUSQUEDA")
            return VEL_AVANCE_ARCO, VEL_ARCO_LENTA, "BUSQUEDA", "DEFLEXION_PARED_IZQ"

        if b_der:
            self._record_side_block(1)
            if self.same_side_block_cycles >= 4 or self._should_start_anti_loop():
                return self._activate_anti_loop(-1, "BUSQUEDA")
            return VEL_ARCO_LENTA, VEL_AVANCE_ARCO, "BUSQUEDA", "DEFLEXION_PARED_DER"

        if self.cycles_since_block > HORIZONTE_OLVIDO_OSCILACIONES:
            self.oscillation_count = 0
            self.same_side_block_cycles = 0
            self.last_blocked_side = 0
            self._reset_side_history()

        sweep = 0.55 if ((self.cycle_count // 50) % 2 == 0) else -0.55
        action = f"EXPLORACION_LIBRE[{izq[0]},{cen[0]},{der[0]}]"
        return 4.8 + sweep, 4.8 - sweep, "BUSQUEDA", action

    def _record_side_block(self, side):
        if self.cycles_since_block >= HORIZONTE_OLVIDO_OSCILACIONES:
            self.oscillation_count = 0
            self.same_side_block_cycles = 0
            self.last_blocked_side = 0
            self._reset_side_history()
        if self.last_blocked_side == side:
            self.same_side_block_cycles += 1
        else:
            self.same_side_block_cycles = 1
            if self.last_blocked_side != 0:
                self.oscillation_count += 1
            self._record_side_history(side)
        self.last_blocked_side = side
        self.cycles_since_block = 0

    def _reset_side_history(self):
        self.side_history_index = 0
        self.side_history_count = 0

    def _record_side_history(self, side):
        self.side_history[self.side_history_index] = side
        self.side_history_index = (self.side_history_index + 1) % len(self.side_history)
        if self.side_history_count < len(self.side_history):
            self.side_history_count += 1

    def _is_alternating(self):
        if self.side_history_count < 3:
            return False
        previous = 0
        for i in range(self.side_history_count):
            index = (self.side_history_index - 1 - i + len(self.side_history) * 2)
            index %= len(self.side_history)
            current = self.side_history[index]
            if current == 0 or current == previous:
                return False
            previous = current
        return True

    def _should_start_anti_loop(self):
        return (self.oscillation_count >= UMBRAL_OSCILACIONES_ANTIBUCLE
                or self._is_alternating())

    def _start_arc_reverse(self, v_left, v_right, steps):
        # El ciclo actual ya devuelve el primer retroceso, por eso se descuenta uno.
        self.reverse_steps_left = steps - 1
        self.reverse_left_speed = v_left
        self.reverse_right_speed = v_right

    def _start_committed_turn(self, direction, steps, speed=VEL_GIRO_COMPROMETIDO, bias=0.0):
        self.turn_steps_left = steps
        self.turn_dir = direction
        self.turn_speed = speed
        self.turn_bias = bias

    def _start_arc_reverse_and_pivot(self, turn_dir, turn_steps, state):
        self._start_arc_reverse(
            VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_NORMAL, PASOS_RETRO_ARCO_NORMAL)
        self._start_committed_turn(turn_dir, turn_steps)
        return VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_NORMAL, state, "RETROCESO_ARCO_Y_PIVOTE"

    def _activate_anti_loop(self, direction, state):
        self.oscillation_count = 0
        self.same_side_block_cycles = 0
        self._reset_side_history()
        self._start_arc_reverse(
            VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_ANTIBUCLE, PASOS_RETRO_ARCO_ANTIBUCLE)
        self._start_committed_turn(
            direction, PASOS_GIRO_ANTIBUCLE, VEL_GIRO_AMPLIO, SESGO_GIRO_ANTIBUCLE)
        return VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_ANTIBUCLE, state, "ANTIBUCLE_ESCAPE"
