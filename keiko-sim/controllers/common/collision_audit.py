#!/usr/bin/env python3
import math


def find_penalizable_contact(points, marble_positions, robot_position, exit_direction):
    """Devuelve el primer contacto penalizable según altura, canicas y coordenada exterior de salida."""
    exit_coordinate = robot_position[0] * exit_direction[0] + robot_position[1] * exit_direction[1]
    for point in points:
        if point[2] <= 0.022:
            continue
        if any(math.hypot(point[0] - mx, point[1] - my) < 0.035 for mx, my in marble_positions):
            continue
        if exit_coordinate > 0.58:
            continue
        return point
    return None


def is_new_impact(contact_point, was_colliding):
    """Indica el flanco de inicio de un impacto, sin ocultar recontactos por tiempo."""
    return contact_point is not None and not was_colliding
