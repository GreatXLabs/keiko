# Proyecto Orquitas: Competencia en la Bahía de Caza

Resumen técnico de especificaciones del escenario, reglamento y sistema de puntuación para el desarrollo del sistema autónomo.

---

## 1. Entorno Físico: La Bahía

Estructura con geometría aproximada de **semielipse** (o semicírculo irregular), montada contra una pared que conforma su lado plano, delimitada por muros perimetrales de cartón y con una abertura frontal de salida.

> [!IMPORTANT]
> La bahía **no es una figura regular perfecta**; se aproxima a una semielipse deformada por el armado manual. La profundidad, la longitud contra la pared y el área son **aproximaciones**. Las únicas **medidas fijas y exactas** son el ancho de la salida, el grosor y la altura de los muros.

### Especificaciones Físicas

| Elemento | Medida | Condición | Observaciones |
| :--- | :--- | :--- | :--- |
| **Ancho de salida** | 30 cm | Fija | Centro del arco; simple hueco sin marcas ni refuerzos |
| **Grosor de muros** | 5 mm | Fija | Material: cartón (extremos planos, sin postes en "T") |
| **Altura de muros** | 15 - 20 cm | Fija | Altura uniforme de contención |
| **Longitud de base** | ~1.80 m | Aproximada | Extensión sobre la pared (semieje menor $b = 0.90\text{ m}$) |
| **Profundidad máxima** | ~1.20 m | Aproximada | Distancia hacia la salida (semieje mayor $a = 1.20\text{ m}$, ~3 baldosas) |
| **Área interior** | ~1.70 m² | Aproximada | Semielipse: $A = \frac{1}{2}\pi \cdot a \cdot b \approx \frac{1}{2}\pi \cdot 1.20 \cdot 0.90$ |

### Esquema en Planta

```text
                     ==============================================  [ PARED ]
                    |<----------------- ~1.80 m ------------------>|
                    |            (2b = 2 x 0.90 m aprox)           |
                    |                                              |
                    |          Profundidad máxima ≈ 1.20 m         |
                    |          (semieje mayor a, ~3 baldosas)      |
                    |                        |                     |
                     \                  Área estimada             /
                      \                   ~1.70 m²               /   Muros:
                       \                     |                  /    - Altura: 15-20 cm (fijo)
                        \                    v                 /     - Grosor: 5 mm (fijo)
                         +--------------+           +---------+
                                        |<─ 30 cm ─>|
                                           (fijo)
                                         [ SALIDA ]
```

> [!NOTE]
> * **Cálculo del área de la semielipse:** Con un semieje mayor de **$a = 1.20\text{ m}$** (distancia máxima del centro a la salida) y un semieje menor de **$b = 0.90\text{ m}$** (mitad de la base de 1.80 m sobre la pared):
>   $$A = \frac{1}{2} \cdot \pi \cdot a \cdot b = \frac{1}{2} \cdot \pi \cdot (1.20\text{ m}) \cdot (0.90\text{ m}) = 0.54 \cdot \pi \approx \mathbf{1.6965\text{ m}^2 \approx 1.70\text{ m}^2}$$
> * La salida no posee ningún sensor, guía ni marco distintivo: es únicamente un corte o hueco de 30 cm donde falta cartón para completar la curva.

---

## 2. Elementos Interactivos: Lobitos y Obstáculos

| Elemento | Cantidad | Tamaño / Referencia | Disposición y Reglas |
| :--- | :--- | :--- | :--- |
| **Lobitos (Canicas)** | 3 (exactas) | 1.6 cm de diámetro | Ubicadas con amplio margen respecto a muros y obstáculos para evitar penalizaciones al recolectar |
| **Obstáculos** | 2 (únicamente) | 6 cm × 6 cm (base) / Altura mín.: 6 cm | Impresos en 3D. Sin altura exacta especificada (mínimo garantizado: 6 cm) |

> [!NOTE]
> Los obstáculos consisten en 2 piezas impresas en 3D con sección de **6 cm × 6 cm**. Si bien no se especificó la cota final de una de sus dimensiones (altura), se confirmó que tendrán una **altura mínima de 6 cm**.

---

## 3. Formato de la Competencia

La competencia se disputa en cuatro etapas sucesivas:

| Etapa | Modalidad | Tiempo Máx. | Clasificación / Definición |
| :--- | :--- | :--- | :--- |
| **1. Clasificación** | Individual contra reloj | 7 minutos | Se ordenan por puntaje. Los **4 mejores** pasan a semifinales |
| **2. Semifinales** | Cruces simultáneos (1.° vs 4.° y 2.° vs 3.°) | 10 minutos | Dos Orquitas compiten a la vez en la bahía. Pasan los ganadores |
| **3. Final** | Enfrentamiento directo entre ganadores de semis | 10 minutos | Define al Campeón de la jornada |
| **4. Recontrafinalísima** | Campeón Turno Tarde vs Campeón Turno Noche | 10 minutos | Enfrentamiento único. Define al Campeón General |

---

## 4. Sistema de Puntos

### Tabla de Puntuación

| Categoría | Acción | Puntos |
| :--- | :--- | :--- |
| **Captura de Lobitos** | Primer lobito | **+5** |
| | Segundo lobito (sin perder el primero) | **+10** |
| | Tercer lobito (sin perder el 1.° ni el 2.°) | **+15** |
| **Navegación / Escape** | Activación correcta del Modo Escape | **+15** |
| | Abandono completo de la Bahía | **+30** |
| **Bonificación por Tiempo** | Misión completada dentro del 1.° minuto | **+15** |
| | Misión completada entre el 2.° y el 4.° minuto | **+10** |
| | Misión completada entre el 4.° y el 10.° minuto | **+5** |
| **Penalizaciones** | Chocar contra muros | **-5** |
| | Chocar contra obstáculos | **-5** |
| | Pérdida / escape de 1 lobito | **-5** |

### Criterios de Desempate

En caso de igualdad de puntos, el desempate se resuelve en este orden de prioridad:

1. Mayor cantidad de Lobitos capturados.
2. Alcance efectivo del Modo Escape.
3. Mayor distancia recorrida hacia la salida.
4. Si persiste la igualdad: realización de una nueva prueba.

---

## 5. Reglas Operativas y Restricciones

* **Autonomía total:** Una vez iniciada la prueba, la Orquita debe operar de manera 100% autónoma. Queda prohibido tocarla, moverla o enviarle comandos manuales de navegación o captura. Cualquier intervención no autorizada descalifica la prueba.
* **Comunicación:** La Orquita solo puede comunicarse con el Servidor Central previamente asignado y emparejado.
* **Inactividad:** Si el robot permanece inactivo por más de **10 segundos**, queda fuera de competencia conservando los puntos acumulados hasta ese instante.
* **Condiciones de fin de prueba:**
  * Cumplimiento total de la misión (abandono de la bahía).
  * Agotamiento del tiempo límite (7 min en clasificación / 10 min en eliminatorias).
  * Inactividad mayor a 10 segundos o descalificación por infracción.
  * Detención por parte del tribunal docente por razones de seguridad.

---

## 6. Consideraciones Docentes y Estrategia

* **Premio especial:** El docente anunció un premio especial para quien logre capturar las 3 canicas dentro de los 7 minutos sin registrar choques contra muros u obstáculos.
* **Criterio de aprobación:** El hecho de que la captura perfecta de las 3 piezas sea recompensada con un premio indica que el umbral de aprobación de la materia no es tan exigente ni demanda una ejecución al 100%.
* **Libertad táctica:** La estrategia para maximizar el puntaje queda a criterio exclusivo de cada equipo.
  * *Ejemplo conservador y efectivo:* Capturar una sola canica (+5) y dirigirse directo a la salida (+15 Modo Escape + 30 Abandono de la Bahía + bonificación por tiempo). Esto permite asegurar un piso alto de puntos rápidamente, minimizando la exposición a penalizaciones por choque (-5) o tiempos muertos.

