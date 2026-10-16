#!/usr/bin/env python3
"""
Mapeador de Ocupación y Exploración de Fronteras - Proyecto Orquitas (Keiko)
Módulo común de cartografía 2D en tiempo real (Occupancy Grid).

Características:
1. Rejilla discreta de ocupación (Occupancy Grid) con resolución configurable (por defecto 10 cm).
2. Trazado de celdas: LIBRE, VISITADO, OBSTÁCULO, SALIDA, POSICIÓN_ROBOT.
3. Cálculo de porcentaje de cobertura explorada de la Bahía de Caza.
4. Búsqueda de frontera: Selección dinámica del centroide de zonas no exploradas para
   guiar la navegación sin depender de waypoints fijos en mapas diversos.
5. Renderizador ASCII de alta legibilidad para consola y telemetría de depuración.
"""

import math

class ArenaMapper:
    # Estados de celda
    UNKNOWN = 0    # No explorada ' '
    FREE = 1       # Libre '·'
    VISITED = 2    # Recorrida por el robot '.'
    OBSTACLE = 3   # Obstáculo detectado '#'
    EXIT = 4       # Estrecho de Salida 'E'

    def __init__(self, x_min=-1.10, x_max=1.10, y_min=-1.10, y_max=1.10, res=0.10, exit_pos=(0.0, 1.20)):
        self.x_min = x_min
        self.x_max = x_max
        self.y_min = y_min
        self.y_max = y_max
        self.res = res
        self.exit_pos = (float(exit_pos[0]), float(exit_pos[1]))

        self.cols = int(round((x_max - x_min) / res)) + 1
        self.rows = int(round((y_max - y_min) / res)) + 1

        # Matriz 2D [row][col] (row 0 = y_min, row rows-1 = y_max)
        self.grid = [[self.UNKNOWN for _ in range(self.cols)] for _ in range(self.rows)]
        self.total_reachable_cells = self.rows * self.cols

        # Marcar celda de salida en la pared detectada
        exit_r, exit_c = self.world_to_grid(self.exit_pos[0], self.exit_pos[1])
        if self._in_bounds(exit_r, exit_c):
            self.grid[exit_r][exit_c] = self.EXIT

    def _is_exit_zone(self, x, y):
        """Zona perimetral de salida a preservar (abertura del estrecho)."""
        return math.hypot(x - self.exit_pos[0], y - self.exit_pos[1]) < 0.45

    def world_to_grid(self, x, y):
        c = int(round((x - self.x_min) / self.res))
        r = int(round((y - self.y_min) / self.res))
        return r, c

    def grid_to_world(self, r, c):
        x = self.x_min + c * self.res
        y = self.y_min + r * self.res
        return x, y

    def _in_bounds(self, r, c):
        return 0 <= r < self.rows and 0 <= c < self.cols

    def update(self, rx, ry, yaw, d_hl_cm, d_hr_cm, d_ll_cm, d_lr_cm):
        """
        Actualiza el mapa con la posición del robot y los datos del banco ultrasónico.
        """
        # 1. Marcar posición actual del robot como VISITADA
        rr, rc = self.world_to_grid(rx, ry)
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                nr, nc = rr + dr, rc + dc
                if self._in_bounds(nr, nc):
                    if self.grid[nr][nc] != self.OBSTACLE and self.grid[nr][nc] != self.EXIT:
                        self.grid[nr][nc] = self.VISITED

        # 2. Trazar rayos ultrasónicos
        # Los sensores están orientados hacia adelante (yaw), con aperturas aprox ±12 grados
        sensor_offsets = [
            (d_hl_cm, yaw + 0.20, True),   # High Left
            (d_hr_cm, yaw - 0.20, True),   # High Right
            (d_ll_cm, yaw + 0.10, False),  # Low Left
            (d_lr_cm, yaw - 0.10, False)   # Low Right
        ]

        for dist_cm, angle, is_high in sensor_offsets:
            if dist_cm <= 0.0 or dist_cm > 150.0:
                continue

            dist_m = dist_cm / 100.0
            cos_a = math.cos(angle)
            sin_a = math.sin(angle)

            # Celdas libres a lo largo del haz
            num_steps = max(1, int(dist_m / (self.res * 0.5)))
            for step in range(1, num_steps):
                d = step * (self.res * 0.5)
                if d >= dist_m - 0.08:
                    break
                px = rx + d * cos_a
                py = ry + d * sin_a
                pr, pc = self.world_to_grid(px, py)
                if self._in_bounds(pr, pc):
                    if self.grid[pr][pc] == self.UNKNOWN:
                        self.grid[pr][pc] = self.FREE

            # Celda de impacto (obstáculo alto permanente)
            # Solo consideramos obstáculo seguro si el plano alto lo detecta
            if is_high and dist_cm < 90.0:
                ox = rx + dist_m * cos_a
                oy = ry + dist_m * sin_a
                opr, opc = self.world_to_grid(ox, oy)
                if self._in_bounds(opr, opc):
                    # No tapar la salida perimetral (cualquier pared)
                    if not self._is_exit_zone(ox, oy):
                        self.grid[opr][opc] = self.OBSTACLE

    def get_coverage_percent(self):
        """Retorna el porcentaje de celdas visitadas o exploradas."""
        explored = 0
        for r in range(self.rows):
            for c in range(self.cols):
                if self.grid[r][c] in (self.FREE, self.VISITED, self.OBSTACLE):
                    explored += 1
        return (explored / float(self.total_reachable_cells)) * 100.0

    def get_visited_percent(self):
        """Retorna el porcentaje de celdas directamente transitadas."""
        visited = 0
        for r in range(self.rows):
            for c in range(self.cols):
                if self.grid[r][c] == self.VISITED:
                    visited += 1
        return (visited / float(self.total_reachable_cells)) * 100.0

    def get_frontier_target(self, rx, ry):
        """
        Encuentra el centroide de la mayor frontera desconocida más cercana al robot.
        Permite navegar y barrer toda la bahía de manera autónoma en cualquier mapa.
        """
        unknown_candidates = []

        # Buscar celdas UNKNOWN adyacentes a FREE o VISITED
        for r in range(1, self.rows - 1):
            for c in range(1, self.cols - 1):
                if self.grid[r][c] == self.UNKNOWN:
                    # Chequear vecindad
                    has_known_neighbor = False
                    for dr, dc in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                        if self.grid[r + dr][c + dc] in (self.FREE, self.VISITED):
                            has_known_neighbor = True
                            break
                    if has_known_neighbor:
                        wx, wy = self.grid_to_world(r, c)
                        # No seleccionar la salida como frontera de búsqueda si estamos cazando
                        if self._is_exit_zone(wx, wy):
                            continue
                        dist = math.hypot(wx - rx, wy - ry)
                        if dist > 0.25:  # Evitar puntos inmediatamente adyacentes
                            unknown_candidates.append((dist, wx, wy))

        if not unknown_candidates:
            # Si no hay fronteras claras, seleccionar cuadrante menos visitado
            quadrants = [
                (0.60, 0.60),    # Noreste
                (-0.60, 0.60),   # Noroeste
                (-0.60, -0.60),  # Suroeste
                (0.60, -0.60),   # Sureste
                (0.00, 0.00)     # Centro
            ]
            # Seleccionar el más alejado de la posición actual
            best_q = max(quadrants, key=lambda q: math.hypot(q[0] - rx, q[1] - ry))
            return best_q

        # Ordenar por distancia ponderada (equilibrio entre cercanía y tamaño de grupo)
        unknown_candidates.sort(key=lambda item: item[0])
        best_cand = unknown_candidates[0]
        return best_cand[1], best_cand[2]

    def get_least_visited_sector(self, rx, ry):
        """
        Calcula la densidad de celdas no exploradas en cada uno de los 4 cuadrantes
        y retorna el destino hacia el sector con mayor potencial de búsqueda.
        """
        quads = [
            {"name": "NE", "pos": (0.45, 0.45), "unknown": 0},
            {"name": "NW", "pos": (-0.45, 0.45), "unknown": 0},
            {"name": "SW", "pos": (-0.45, -0.45), "unknown": 0},
            {"name": "SE", "pos": (0.45, -0.45), "unknown": 0},
        ]

        for r in range(self.rows):
            for c in range(self.cols):
                if self.grid[r][c] == self.UNKNOWN:
                    wx, wy = self.grid_to_world(r, c)
                    if wx >= 0 and wy >= 0:
                        quads[0]["unknown"] += 1
                    elif wx < 0 and wy >= 0:
                        quads[1]["unknown"] += 1
                    elif wx < 0 and wy < 0:
                        quads[2]["unknown"] += 1
                    else:
                        quads[3]["unknown"] += 1

        # Ordenar por mayor cantidad de celdas desconocidas y que no sea la actual
        quads.sort(key=lambda q: q["unknown"], reverse=True)
        for q in quads:
            if math.hypot(q["pos"][0] - rx, q["pos"][1] - ry) > 0.35:
                return q["pos"]

        return quads[0]["pos"]

    def render_ascii(self, rx, ry, yaw, target_x=None, target_y=None):
        """
        Genera una representación ASCII compacta del mapa de la Bahía.
        Norte arriba (Y positivo), Sur abajo (Y negativo).
        Muestra posición del robot ([^ v < >]) y destino actual ([T]).
        """
        rr, rc = self.world_to_grid(rx, ry)
        tr, tc = self.world_to_grid(target_x, target_y) if (target_x is not None and target_y is not None) else (-1, -1)

        # Determinar flecha del robot
        angle_deg = (math.degrees(yaw) + 360) % 360
        if 45 <= angle_deg < 135:
            robot_char = "^"
        elif 135 <= angle_deg < 225:
            robot_char = "<"
        elif 225 <= angle_deg < 315:
            robot_char = "v"
        else:
            robot_char = ">"

        lines = []
        border = "+" + "-" * self.cols + "+"
        lines.append(border)

        # Iterar desde la fila superior (Y max) hacia la inferior (Y min)
        for r in range(self.rows - 1, -1, -1):
            row_chars = []
            for c in range(self.cols):
                if r == rr and c == rc:
                    row_chars.append(robot_char)
                elif r == tr and c == tc and self.grid[r][c] != self.OBSTACLE and self.grid[r][c] != self.EXIT:
                    row_chars.append("T")
                else:
                    val = self.grid[r][c]
                    if val == self.UNKNOWN:
                        row_chars.append(" ")
                    elif val == self.FREE:
                        row_chars.append("·")
                    elif val == self.VISITED:
                        row_chars.append(".")
                    elif val == self.OBSTACLE:
                        row_chars.append("#")
                    elif val == self.EXIT:
                        row_chars.append("E")
                    else:
                        row_chars.append("?")
            lines.append("|" + "".join(row_chars) + "|")

        lines.append(border)
        return "\n".join(lines)
