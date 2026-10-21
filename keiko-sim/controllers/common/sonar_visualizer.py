#!/usr/bin/env python3
"""
Visualizador de Haces Ultrasónicos y Radar Sonar 2D en Tiempo Real
Proyecto Orquitas (Keiko) - Ecosistema de Percepción y Clasificación

Proporciona dos modalidades de depuración visual de alta fidelidad:
1. Sonar HUD en Pantalla (Webots Display Device):
   - Proyección gráfica 2D superpuesta en el viewport 3D de Webots.
   - Dibuja el chasis del robot, los conos de apertura acústica (30 deg) de cada transductor,
     los vectores de propagación del haz hasta el punto de impacto y marcadores coloreados
     según la clasificación de la red neuronal:
     * Verde Neón (#00FF66): Presa Biológica (Lobito / Canica) detectada por diferencial vertical.
     * Rojo Carmesí (#EF4444): Muro perimetral u obstáculo estático.
     * Ámbar (#F59E0B): Advertencia de proximidad en flancos laterales o retaguardia.
     * Azul/Cian (#38BDF8): Espacio libre navegable o Estrecho de Salida.
   - Muestra telemetría en vivo: Estado FSM, Canicas aseguradas (X/3), velocidad y deltas coaxiales.

2. Matriz de Haces y Radar Polar ASCII para Consola y Archivos de Log:
   - Diagrama angular y gráfico de barras de profundidad para inspección en terminal y ejecución batch.
"""

import math

class SonarVisualizer:
    def __init__(self, robot=None, display_name="sensor_hud", config_name="dual_plane_8", width=340, height=340):
        self.robot = robot
        self.display_name = display_name
        self.config_name = config_name
        self.width = width
        self.height = height
        self.cx = width // 2
        self.cy = height // 2
        
        # Escala: 200 cm = 135 píxeles (0.675 px/cm)
        self.max_range_cm = 200.0
        self.scale = 135.0 / self.max_range_cm

        # Inicializar dispositivo Display de Webots si existe en el robot
        self.display = None
        if self.robot is not None:
            try:
                self.display = self.robot.getDevice(display_name)
                if self.display is not None:
                    # Configurar fuente inicial legible
                    try:
                        self.display.setFont("Lucida Console", 10, True)
                    except Exception:
                        pass
            except Exception:
                self.display = None

        # Definición geométrica y angular de configuraciones de sensores
        # Ángulos en grados: 0° = Norte/Frente, +90° = Este/Derecha, -90° = Oeste/Izquierda, 180° = Sur/Atrás
        self.sensor_profiles = {
            "trisector_front_6": {
                "us_low_left":     {"yaw": -22.0, "x_off": -9, "y_off": -16, "is_high": False, "is_front": True,  "pair": "us_high_left",     "label": "FL-Low"},
                "us_low_center":   {"yaw": 0.0,   "x_off": 0,  "y_off": -18, "is_high": False, "is_front": True,  "pair": "us_high_center",   "label": "FC-Low"},
                "us_low_right":    {"yaw": +22.0, "x_off": +9, "y_off": -16, "is_high": False, "is_front": True,  "pair": "us_high_right",    "label": "FR-Low"},
                "us_high_left":    {"yaw": -22.0, "x_off": -9, "y_off": -16, "is_high": True,  "is_front": True,  "pair": "us_low_left",      "label": "FL-High"},
                "us_high_center":  {"yaw": 0.0,   "x_off": 0,  "y_off": -18, "is_high": True,  "is_front": True,  "pair": "us_low_center",    "label": "FC-High"},
                "us_high_right":   {"yaw": +22.0, "x_off": +9, "y_off": -16, "is_high": True,  "is_front": True,  "pair": "us_low_right",     "label": "FR-High"},
            },
            "dual_plane_8": {
                "us_low_left":   {"yaw": -5.0,  "x_off": -6, "y_off": -18, "is_high": False, "is_front": True,  "pair": "us_high_left",  "label": "FL-Low"},
                "us_low_right":  {"yaw": +5.0,  "x_off": +6, "y_off": -18, "is_high": False, "is_front": True,  "pair": "us_high_right", "label": "FR-Low"},
                "us_high_left":  {"yaw": -5.0,  "x_off": -6, "y_off": -18, "is_high": True,  "is_front": True,  "pair": "us_low_left",   "label": "FL-High"},
                "us_high_right": {"yaw": +5.0,  "x_off": +6, "y_off": -18, "is_high": True,  "is_front": True,  "pair": "us_low_right",  "label": "FR-High"},
                "us_side_left":  {"yaw": -90.0, "x_off": -12, "y_off": 0,  "is_high": False, "is_front": False, "pair": None,            "label": "L-Side"},
                "us_side_right": {"yaw": +90.0, "x_off": +12, "y_off": 0,  "is_high": False, "is_front": False, "pair": None,            "label": "R-Side"},
                "us_rear_left":  {"yaw": -175.0,"x_off": -6, "y_off": +18, "is_high": False, "is_front": False, "pair": None,            "label": "RL-Rear"},
                "us_rear_right": {"yaw": +175.0,"x_off": +6, "y_off": +18, "is_high": False, "is_front": False, "pair": None,            "label": "RR-Rear"},
            },
            "trisector_10": {
                "us_low_left":     {"yaw": -25.0, "x_off": -9, "y_off": -16, "is_high": False, "is_front": True,  "pair": "us_high_left",     "label": "FL-Low-Ang"},
                "us_low_center":   {"yaw": 0.0,   "x_off": 0,  "y_off": -18, "is_high": False, "is_front": True,  "pair": "us_high_center",   "label": "FC-Low"},
                "us_low_right":    {"yaw": +25.0, "x_off": +9, "y_off": -16, "is_high": False, "is_front": True,  "pair": "us_high_right",    "label": "FR-Low-Ang"},
                "us_high_left":    {"yaw": -25.0, "x_off": -9, "y_off": -16, "is_high": True,  "is_front": True,  "pair": "us_low_left",      "label": "FL-High-Ang"},
                "us_high_center":  {"yaw": 0.0,   "x_off": 0,  "y_off": -18, "is_high": True,  "is_front": True,  "pair": "us_low_center",    "label": "FC-High"},
                "us_high_right":   {"yaw": +25.0, "x_off": +9, "y_off": -16, "is_high": True,  "is_front": True,  "pair": "us_low_right",     "label": "FR-High-Ang"},
                "us_side_left":    {"yaw": -90.0, "x_off": -12, "y_off": 0,  "is_high": False, "is_front": False, "pair": None,               "label": "L-Side"},
                "us_side_right":   {"yaw": +90.0, "x_off": +12, "y_off": 0,  "is_high": False, "is_front": False, "pair": None,               "label": "R-Side"},
                "us_rear_left":    {"yaw": -175.0,"x_off": -6, "y_off": +18, "is_high": False, "is_front": False, "pair": None,               "label": "RL-Rear"},
                "us_rear_right":   {"yaw": +175.0,"x_off": +6, "y_off": +18, "is_high": False, "is_front": False, "pair": None,               "label": "RR-Rear"},
            },
            "convergent_8": {
                "us_low_left":   {"yaw": +12.0, "x_off": -8, "y_off": -18, "is_high": False, "is_front": True,  "pair": "us_high_left",  "label": "FL-Conv"},
                "us_low_right":  {"yaw": -12.0, "x_off": +8, "y_off": -18, "is_high": False, "is_front": True,  "pair": "us_high_right", "label": "FR-Conv"},
                "us_high_left":  {"yaw": +12.0, "x_off": -8, "y_off": -18, "is_high": True,  "is_front": True,  "pair": "us_low_left",   "label": "FL-HConv"},
                "us_high_right": {"yaw": -12.0, "x_off": +8, "y_off": -18, "is_high": True,  "is_front": True,  "pair": "us_low_right",  "label": "FR-HConv"},
                "us_side_left":  {"yaw": -90.0, "x_off": -12, "y_off": 0,  "is_high": False, "is_front": False, "pair": None,            "label": "L-Side"},
                "us_side_right": {"yaw": +90.0, "x_off": +12, "y_off": 0,  "is_high": False, "is_front": False, "pair": None,            "label": "R-Side"},
                "us_rear_left":  {"yaw": -175.0,"x_off": -6, "y_off": +18, "is_high": False, "is_front": False, "pair": None,            "label": "RL-Rear"},
                "us_rear_right": {"yaw": +175.0,"x_off": +6, "y_off": +18, "is_high": False, "is_front": False, "pair": None,            "label": "RR-Rear"},
            }
        }

    def set_config(self, config_name):
        if config_name in self.sensor_profiles:
            self.config_name = config_name

    def render_display(self, readings, state_info=None):
        """
        Dibuja el Sonar HUD 2D interactivo en el dispositivo Display de Webots.
        """
        if self.display is None:
            return

        profile = self.sensor_profiles.get(self.config_name, self.sensor_profiles["dual_plane_8"])
        cx, cy = self.cx, self.cy

        # 1. Fondo Radar
        self.display.setColor(0x0A0E17)
        self.display.fillRectangle(0, 0, self.width, self.height)

        # 2. Rejilla Polar y Anillos Concéntricos de Alcance
        self.display.setColor(0x1B263B)
        for r_cm in [25, 50, 100, 150, 200]:
            r_px = int(r_cm * self.scale)
            self.display.drawOval(cx, cy, r_px, r_px)

        # Ejes ortogonales
        self.display.drawLine(cx, 25, cx, self.height - 25)
        self.display.drawLine(25, cy, self.width - 25, cy)

        # Etiquetas de distancia
        self.display.setColor(0x526E8C)
        self.display.drawText("50cm", cx + 3, cy - int(50 * self.scale) + 2)
        self.display.drawText("1.0m", cx + 3, cy - int(100 * self.scale) + 2)
        self.display.drawText("1.5m", cx + 3, cy - int(150 * self.scale) + 2)
        self.display.drawText("2.0m", cx + 3, cy - int(200 * self.scale) + 2)

        # 3. Chasis del Robot en el Centro
        # Cuerpo principal
        self.display.setColor(0x1E293B)
        self.display.fillRectangle(cx - 10, cy - 16, 20, 32)
        self.display.setColor(0x475569)
        self.display.drawRectangle(cx - 10, cy - 16, 20, 32)

        # 4 Ruedas TT Amarillas
        self.display.setColor(0xEAB308)
        self.display.fillRectangle(cx - 14, cy - 14, 4, 8)  # FL
        self.display.fillRectangle(cx + 10, cy - 14, 4, 8)  # FR
        self.display.fillRectangle(cx - 14, cy + 6, 4, 8)   # RL
        self.display.fillRectangle(cx + 10, cy + 6, 4, 8)   # RR

        # Boca / Tolva de Recolección (Frontal)
        self.display.setColor(0x06B6D4)
        self.display.drawLine(cx - 8, cy - 16, cx - 4, cy - 10)
        self.display.drawLine(cx + 8, cy - 16, cx + 4, cy - 10)
        self.display.drawLine(cx - 4, cy - 10, cx + 4, cy - 10)

        # 4. Trazado de Haces Ultrasónicos y Marcadores de Detección
        any_marble_seen = False
        active_target_dist = 0.0

        for s_name, meta in profile.items():
            if s_name not in readings:
                continue

            dist_cm = max(2.0, min(self.max_range_cm, readings[s_name]))
            yaw_deg = meta["yaw"]
            rad = math.radians(yaw_deg)
            is_high = meta["is_high"]
            is_front = meta["is_front"]
            pair_name = meta["pair"]

            # Origen del transductor en píxeles
            sx = cx + meta["x_off"]
            sy = cy + meta["y_off"]

            # Apertura acústica de 30° (±15° respecto al eje del haz)
            beam_half_angle = math.radians(14.0)
            ray_len = dist_cm * self.scale

            # Extremo del haz central
            tx = int(sx + ray_len * math.sin(rad))
            ty = int(sy - ray_len * math.cos(rad))

            # Cono de apertura acústica (haz lateral izquierdo y derecho)
            c1_x = int(sx + ray_len * math.sin(rad - beam_half_angle))
            c1_y = int(sy - ray_len * math.cos(rad - beam_half_angle))
            c2_x = int(sx + ray_len * math.sin(rad + beam_half_angle))
            c2_y = int(sy - ray_len * math.cos(rad + beam_half_angle))

            # Determinar clasificación del haz
            is_marble = False
            delta_h_l = 0.0

            if is_front and not is_high and pair_name and (pair_name in readings):
                d_high = readings[pair_name]
                delta_h_l = d_high - dist_cm
                if dist_cm < 60.0 and delta_h_l > 5.0:
                    is_marble = True
                    any_marble_seen = True
                    active_target_dist = dist_cm

            # Selección de color según clasificación
            if is_marble:
                beam_color = 0x00FF66      # Verde Neón: Lobito detectado
                cone_color = 0x005522
                hit_color = 0x00FF88
            elif not is_high and dist_cm < 28.0:
                beam_color = 0xEF4444      # Rojo: Obstáculo peligroso / Muro cercano
                cone_color = 0x551111
                hit_color = 0xFF2222
            elif is_high and dist_cm < 35.0:
                beam_color = 0xF59E0B      # Naranja: Obstáculo alto detectado
                cone_color = 0x442800
                hit_color = 0xFBBF24
            elif not is_front and dist_cm < 25.0:
                beam_color = 0xEC4899      # Magenta: Flanco o retaguardia con proximidad
                cone_color = 0x441133
                hit_color = 0xF472B6
            else:
                beam_color = 0x223348      # Gris/Azul tenue: Espacio libre
                cone_color = 0x0F1722
                hit_color = 0x38BDF8

            # Dibujar cono acústico lateral
            self.display.setColor(cone_color)
            self.display.drawLine(sx, sy, c1_x, c1_y)
            self.display.drawLine(sx, sy, c2_x, c2_y)
            self.display.drawLine(c1_x, c1_y, c2_x, c2_y)

            # Dibujar vector central de propagación
            self.display.setColor(beam_color)
            self.display.drawLine(sx, sy, tx, ty)

            # Marcador de impacto en el extremo del haz
            if is_marble:
                # Doble anillo pulsante para la presa
                self.display.setColor(0x00FF66)
                self.display.fillOval(tx, ty, 5, 5)
                self.display.drawOval(tx, ty, 8, 8)
                self.display.drawText(f"LOBITO {dist_cm:.0f}cm", tx + 8, ty - 5)
            elif dist_cm < (self.max_range_cm - 10):
                self.display.setColor(hit_color)
                self.display.fillOval(tx, ty, 3, 3)

        # 5. Panel Superior de Telemetría y Estado FSM
        self.display.setColor(0x0F172A)
        self.display.fillRectangle(0, 0, self.width, 24)
        self.display.setColor(0x38BDF8)
        
        state_str = state_info.get("state", "BUSQUEDA") if state_info else "BUSQUEDA"
        captures = state_info.get("captures", 0) if state_info else 0
        cfg_tag = self.config_name.upper().replace("_", " ")

        self.display.drawText(f"KEIKO RADAR | {cfg_tag}", 8, 4)
        self.display.setColor(0x10B981 if state_str == "ESCAPE" else 0xF59E0B)
        self.display.drawText(f"[{state_str}]", self.width - 85, 4)

        # 6. Barra Inferior de Métricas
        self.display.setColor(0x0F172A)
        self.display.fillRectangle(0, self.height - 24, self.width, 24)

        self.display.setColor(0xE2E8F0)
        canicas_txt = f"CANICAS: {captures}/3"
        self.display.drawText(canicas_txt, 8, self.height - 18)

        # Indicador de alerta de canica
        if any_marble_seen:
            self.display.setColor(0x00FF66)
            self.display.drawText(f"★ PRESA A {active_target_dist:.0f}cm ★", 120, self.height - 18)
        else:
            vl = state_info.get("vl", 0.0) if state_info else 0.0
            vr = state_info.get("vr", 0.0) if state_info else 0.0
            self.display.setColor(0x94A3B8)
            self.display.drawText(f"V:[{vl:.1f}, {vr:.1f}]", self.width - 95, self.height - 18)

    def render_ascii(self, readings, state_info=None):
        """
        Genera el radar polar y matriz de haces acústicos en formato ASCII
        para consola y registro de depuración en archivos .log.
        """
        state_str = state_info.get("state", "BUSQUEDA") if state_info else "BUSQUEDA"
        captures = state_info.get("captures", 0) if state_info else 0
        elapsed = state_info.get("elapsed", 0.0) if state_info else 0.0
        cov = state_info.get("coverage", 0.0) if state_info else 0.0

        d_ll = readings.get("us_low_left", 250.0)
        d_lr = readings.get("us_low_right", 250.0)
        d_hl = readings.get("us_high_left", 250.0)
        d_hr = readings.get("us_high_right", 250.0)
        d_sl = readings.get("us_side_left", 250.0)
        d_sr = readings.get("us_side_right", 250.0)
        d_rl = readings.get("us_rear_left", 250.0)
        d_lc = readings.get("us_low_center", None)
        d_hc = readings.get("us_high_center", None)
        d_rr = readings.get("us_rear_right", 250.0)

        delta_l = d_hl - d_ll
        delta_r = d_hr - d_lr
        delta_c = (d_hc - d_lc) if (d_lc is not None and d_hc is not None) else None

        # Detección y clasificación física
        marble_l = (d_ll < 60.0) and (delta_l > 5.0)
        marble_r = (d_lr < 60.0) and (delta_r > 5.0)
        marble_c = (d_lc is not None) and (d_lc < 60.0) and (delta_c is not None and delta_c > 5.0)

        def make_bar(dist_cm, max_bars=16):
            filled = int(min(dist_cm, 200.0) / 200.0 * max_bars)
            return "=" * filled + ">" + " " * (max_bars - filled)

        lines = [
            "=" * 78,
            f" [RADAR SONAR & MATRIZ DE HACES KEIKO 4WD] | T={elapsed:05.1f}s | FSM={state_str} | Canicas={captures}/3",
            f" Configuración: {self.config_name.upper()} | Cobertura={cov:4.1f}% | ΔL={delta_l:+5.1f}cm | ΔR={delta_r:+5.1f}cm",
            "-" * 78,
        ]

        if self.config_name == "trisector_front_6" and d_lc is not None:
            lines.extend([
                "           [ABANICO FRONTAL 6 SENSORES: IZQ (+22°), CEN (0°), DER (-22°)]",
                f"   FL(+22°): L={d_ll:4.1f} H={d_hl:4.1f}{'★' if marble_l else ' '} | FC(0°): L={d_lc:4.1f} H={d_hc:4.1f}{'★' if marble_c else ' '} | FR(-22°): L={d_lr:4.1f} H={d_hr:4.1f}{'★' if marble_r else ' '}",
                "               \\                    |                    /",
                "                          [CHASIS ORQUITA 4WD]",
            ])
        elif self.config_name == "trisector_10" and d_lc is not None:
            lines.extend([
                "           [ABANICO FRONTAL TRISECTORIAL: IZQ (-25°), CEN (0°), DER (+25°)]",
                f"   FL(-25°): L={d_ll:4.1f} H={d_hl:4.1f}{'★' if marble_l else ' '} | FC(0°): L={d_lc:4.1f} H={d_hc:4.1f}{'★' if marble_c else ' '} | FR(+25°): L={d_lr:4.1f} H={d_hr:4.1f}{'★' if marble_r else ' '}",
                "               \\                    |                    /",
                f"  Flanco-Izq: {d_sl:5.1f}cm <-------  [CHASIS ROVER 4WD]  -------> Flanco-Der: {d_sr:5.1f}cm",
                "               /                                         \\",
                f"    RL-Tras: {d_rl:5.1f}cm                                  RR-Tras: {d_rr:5.1f}cm",
                "                                [RETAGUARDIA 180°]",
            ])
        else:
            lines.extend([
                "                   [FRONTAL +0° (Doble Nivel Vertical)]",
                f"          FL-Alto: {d_hl:5.1f}cm                  FR-Alto: {d_hr:5.1f}cm",
                f"          FL-Bajo: {d_ll:5.1f}cm {'★' if marble_l else ' '}                  FR-Bajo: {d_lr:5.1f}cm {'★' if marble_r else ' '}",
                "                     \\                          /",
                f"  Flanco-Izq: {d_sl:5.1f}cm <----  [CHASIS ROVER 4WD]  ----> Flanco-Der: {d_sr:5.1f}cm",
                "                     /                          \\",
                f"          RL-Tras: {d_rl:5.1f}cm                  RR-Tras: {d_rr:5.1f}cm",
                "                      [RETAGUARDIA 180°]",
            ])

        lines.extend([
            "-" * 78,
            " Haz Transductor | Distancia | Coaxial Δ | Perfil Haz       | Inferencia Estado",
            "-" * 78,
        ])

        def format_row(name, dist, delta_val=None, is_pair=False, is_marble=False):
            bar = make_bar(dist)
            delta_str = f"{delta_val:+6.1f} cm" if delta_val is not None else "   ---   "
            if is_marble:
                infer = ">>> LOBITO (PRESA) <<<"
            elif dist < 18.0:
                infer = "PELIGRO: MURO/OBSTÁCULO"
            elif dist < 35.0:
                infer = "PROXIMIDAD DETECTADA"
            elif dist > 140.0:
                infer = "ESPACIO LIBRE"
            else:
                infer = "TRANSITABLE"
            return f" {name:15s} | {dist:5.1f} cm  | {delta_str} | [{bar}] | {infer}"

        lines.append(format_row("us_low_left", d_ll, delta_l, True, marble_l))
        lines.append(format_row("us_high_left", d_hl, None, True, False))
        if d_lc is not None and d_hc is not None:
            lines.append(format_row("us_low_center", d_lc, delta_c, True, marble_c))
            lines.append(format_row("us_high_center", d_hc, None, True, False))
        lines.append(format_row("us_low_right", d_lr, delta_r, True, marble_r))
        lines.append(format_row("us_high_right", d_hr, None, True, False))
        if "us_side_left" in readings:
            lines.append(format_row("us_side_left", d_sl))
        if "us_side_right" in readings:
            lines.append(format_row("us_side_right", d_sr))
        if "us_rear_left" in readings:
            lines.append(format_row("us_rear_left", d_rl))
        if "us_rear_right" in readings:
            lines.append(format_row("us_rear_right", d_rr))
        lines.append("=" * 78)

        return "\n".join(lines)
