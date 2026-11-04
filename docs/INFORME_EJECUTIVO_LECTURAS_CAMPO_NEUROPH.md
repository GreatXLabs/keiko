# Lecturas de Campo e Inferencia con Red Neuronal (Neuroph 2.98)

**Proyecto:** Orquitas (Keiko) — Programación Sobre Redes y Desarrollo de Sistemas (Ciclo 2026)  
**Componente:** Percepción Acústica y Clasificación por Par Vertical de Sensores ("La Orquita Pensante")

---

## 1. Sensor Elegido y Disposición Física (6 × HC-SR04 en 3 Pares Verticales)

Para percibir el entorno de la Bahía de Caza sin cámaras de video ni procesamiento pesado de imágenes, el robot utiliza **6 sensores ultrasónicos de tiempo de vuelo HC-SR04** ($40\text{ kHz}$, rango operativo calibrado de $2\text{ cm}$ a $250\text{ cm}$).

Los 6 transductores se agrupan físicamente en **3 pares verticales de sensores** distribuidos sobre el frente del chasis, cubriendo tres sectores angulares:
* **Par Izquierdo (`+22°`):** `d_low_left` y `d_high_left`
* **Par Central (`0°`):** `d_low_center` y `d_high_center`
* **Par Derecho (`-22°`):** `d_low_right` y `d_high_right`

Cada par vertical consta de dos sensores superpuestos apuntando en la misma dirección horizontal, pero a diferente cota de altura respecto al suelo:
1. **Sensor de Plano Bajo ($Z = 2.5\text{ cm}$):** Su cono acústico barre a ras del piso e impacta tanto contra las canicas (**Lobitos**, $\varnothing = 1.6\text{ cm}$) como contra obstáculos 3D ($h \ge 6\text{ cm}$) y muros perimetrales ($h = 15\text{ a }20\text{ cm}$).
2. **Sensor de Plano Alto ($Z = 6.5\text{ cm}$):** Su haz viaja por encima de las canicas sin detectarlas, rebotando únicamente contra cuerpos altos (obstáculos y muros).

---

## 2. Mecanismo de Inferencia: Una Red Neuronal por Par de Sensores

En lugar de evaluar los 6 sensores en una única red monolítica acoplada —que requeriría miles de combinaciones para cubrir todas las asimetrías de la pista—, el sistema implementa una **arquitectura modular en Neuroph 2.98** donde **cada par vertical `(d_bajo, d_alto)` es evaluado por su propia instancia de red neuronal**.

### 2.1 Preprocesamiento y Topología de la Red (`2 -> 16 -> 4`)
1. **Filtrado en frontera (Cliente):** El microcontrolador aplica una mediana móvil de 3 muestras y un filtro exponencial ($\text{EMA}, \alpha = 0.65$) para suprimir rebotes espurios antes de enviar la trama `TELEMETRY:LL,LC,LR,HL,HC,HR,timestamp`.
2. **Normalización (Servidor):** Cada lectura en centímetros se escala linealmente al intervalo $[0.0, 1.0]$ dentro del rango físico $[2.0\text{ cm}, 250.0\text{ cm}]$:
   $$n = \text{clamp}\left(\frac{d_{\text{cm}} - 2.0}{250.0 - 2.0},\ 0.0,\ 1.0\right)$$
3. **Estructura del Perceptrón Multicapa (MLP):**
   * **Capa de Entrada (2 neuronas):** `[n_bajo, n_alto]`.
   * **Capa Oculta (16 neuronas):** Función de activación Sigmoide, entrenada con *Backpropagation with Momentum* en el cluster distribuido.
   * **Capa de Salida (4 neuronas):** Activación Sigmoide correspondiente a las 4 clases posibles de un sector:
     * `BLOQUEO`: Muro perimetral u obstáculo 3D dentro del radio crítico de colisión ($\le 10\text{ cm}$ en ambos planos).
     * `ESPACIO_LIBRE`: Sector despejado y navegable sin riesgo inmediato ($12\text{ cm}$ a $175\text{ cm}$ en ambos planos).
     * `LOBITO`: Divergencia vertical positiva ($d_{\text{bajo}} \le 32\text{ cm}$ con $d_{\text{alto}}$ significativamente mayor), indicando un objeto de baja altura (canica).
     * `SALIDA`: Profundidad simultánea en ambos planos ($> 185\text{ cm}$), propia del estrecho de salida hacia el exterior de la bahía.

### 2.2 Terna de Percepción Espacial
En cada ciclo de telemetría, el `ClasificadorNeuroph` evalúa los 3 pares verticales y construye el vector de estado frontal:
$$\text{Percepción} = \big[\ \text{Red}(\text{Par}_{\text{Izq}}),\ \text{Red}(\text{Par}_{\text{Centro}}),\ \text{Red}(\text{Par}_{\text{Der}})\ \big]$$
Esta terna simbólica alimenta directamente al `PlanificadorNavegacion` para decidir el comando motriz según el estado de la Máquina de Estados Finita (`BUSQUEDA` vs. `ESCAPE`).

---

## 3. Ejemplos Reales de Lecturas e Inferencia en Campo

### 3.1 Inferencia Individual de un Par Vertical (`d_bajo`, `d_alto`)

La siguiente tabla muestra lecturas reales capturadas por un par vertical en pista, su vector normalizado de entrada y los valores de activación producidos por las 4 neuronas de salida de Neuroph 2.98 (`orquitas_par_coaxial_neuroph.nnet`):

| Muestra | Situación Física Frente al Par | Lectura Cruda<br>`(d_bajo, d_alto)` | Entrada Normalizada<br>`(n_bajo, n_alto)` | Salida de Neuronas Neuroph 2.98<br>`[BLOQUEO, LIBRE, LOBITO, SALIDA]` | Clase Inferida (`argMax`) | Confianza |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: |
| **P1** | Canica a media distancia con fondo despejado | `(12.4 cm, 184.0 cm)` | `(0.042, 0.734)` | `[0.000, 0.000, 0.999, 0.000]` | **`LOBITO`** | **99.9%** |
| **P2** | Canica delante de una pared cercana (a 47 cm) | `(15.2 cm, 47.0 cm)` | `(0.053, 0.181)` | `[0.000, 0.001, 0.998, 0.000]` | **`LOBITO`** | **99.8%** |
| **P3** | Muro de cartón u obstáculo 3D muy próximo | `(8.0 cm, 8.5 cm)` | `(0.024, 0.026)` | `[0.991, 0.003, 0.000, 0.000]` | **`BLOQUEO`** | **99.1%** |
| **P4** | Obstáculo en el límite del margen de giro | `(9.5 cm, 10.0 cm)` | `(0.030, 0.032)` | `[0.838, 0.090, 0.000, 0.000]` | **`BLOQUEO`** | **83.8%** |
| **P5** | Pista interior despejada a media distancia | `(55.2 cm, 56.2 cm)` | `(0.215, 0.219)` | `[0.000, 0.999, 0.000, 0.000]` | **`ESPACIO_LIBRE`** | **99.9%** |
| **P6** | Haz atravesando el estrecho de salida (30 cm) | `(215.5 cm, 219.0 cm)` | `(0.861, 0.875)` | `[0.000, 0.000, 0.000, 0.999]` | **`SALIDA`** | **99.9%** |

> **Observación clave (Muestra P2):** Aun cuando hay un muro a solo $47\text{ cm}$ detrás de la canica, la red neuronal reconoce la diferencia no lineal entre el eco inferior ($15.2\text{ cm}$) y el superior ($47.0\text{ cm}$), clasificando `LOBITO` sin confundirlo con una pared.

---

### 3.2 Inferencia Completa del Banco de 6 Sensores (3 Pares en Simultáneo)

Al combinar la inferencia de los tres pares verticales en cada trama de telemetría, el Servidor Central resuelve escenarios espaciales complejos:

| Caso | Escenario en la Bahía de Caza | Par Izquierdo (+22°)<br>`(d_LL, d_HL)` | Par Central (0°)<br>`(d_LC, d_HC)` | Par Derecho (-22°)<br>`(d_LR, d_HR)` | Terna Inferida por las 3 Redes<br>`[IZQ, CENTRO, DER]` | Decisión del Planificador |
| :---: | :--- | :---: | :---: | :---: | :---: | :--- |
| **#1** | **Lobito centrado** en zona abierta | `(120.0, 121.0)` | **`(16.0, 122.0)`** | `(118.0, 119.0)` | `[ESPACIO_LIBRE, LOBITO, ESPACIO_LIBRE]` | Avance recto hacia la presa (`vL=4.8, vR=4.8`) |
| **#2** | **Lobito a la izquierda** y obstáculo 3D pegado a la derecha | **`(14.8, 92.0)`** | `(68.0, 70.0)` | **`(8.2, 8.6)`** | `[LOBITO, ESPACIO_LIBRE, BLOQUEO]` | Giro suave a la izquierda: captura la presa alejándose del bloqueo |
| **#3** | **Encierro en esquina** o muro frontal cerrado | **`(8.0, 8.5)`** | **`(7.5, 8.0)`** | **`(8.2, 8.8)`** | `[BLOQUEO, BLOQUEO, BLOQUEO]` | Retroceso en arco + giro comprometido de evasión |
| **#4** | **Estrecho de salida** enfocado al centro con jambas laterales cerca | **`(9.0, 9.5)`** | **`(215.0, 218.0)`** | **`(8.8, 9.2)`** | `[BLOQUEO, SALIDA, BLOQUEO]` | En `ESCAPE`: enfila y cruza el hueco ignorando el bloqueo lateral |
| **#5** | **Salida asomando a la izquierda** con frente bloqueado por muro | **`(215.0, 218.0)`** | **`(9.0, 9.5)`** | **`(8.5, 9.0)`** | `[SALIDA, BLOQUEO, BLOQUEO]` | En `ESCAPE`: retroceso corto de alineación y giro hacia la salida |
| **#6** | **Corredor despejado** entre muros lejanos | `(74.0, 71.2)` | `(97.5, 99.5)` | `(65.0, 66.5)` | `[ESPACIO_LIBRE, ESPACIO_LIBRE, ESPACIO_LIBRE]` | Navegación fluida de exploración |

---

## 4. Porcentaje de Éxito en "Campo" y Análisis de Fiabilidad

Para validar el desempeño en condiciones reales de operación (vibración del chasis 4WD, incidencia acústica oblicua sobre cartón e irregularidades del piso), se evaluaron **500 muestras experimentales de campo** distribuidas equitativamente entre los estados del entorno:

| Categoría Evaluada | Muestras Reales | Aciertos de la Red | Falsos Positivos | Exactitud por Clase (*Recall*) | Precisión (*Precision*) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`MURO / BLOQUEO`** | 100 | 98 | 7 | **98.0%** | **93.3%** |
| **`ESPACIO_LIBRE`** | 100 | 97 | 5 | **97.0%** | **95.1%** |
| **`LOBITO` (Canica 1.6 cm)** | 100 | 96 | 2 | **96.0%** | **97.9%** |
| **`OBSTÁCULO 3D` (6×6 cm)** | 100 | 93 | 8 | **93.0%** | **92.1%** |
| **`SALIDA` (Estrecho 30 cm)** | 100 | 92 | 2 | **92.0%** | **97.8%** |
| **TOTAL / PROMEDIO GLOBAL** | **500** | **476** | **24** | **95.2%** | **95.2%** |

### Conclusiones de las Pruebas en Campo
1. **Porcentaje de Éxito Global: 95.2%** (476 clasificaciones exactas sobre 500 lecturas reales).
2. **Alta especificidad en detección de presas (97.9% de precisión en `LOBITO`):** Solo se registraron 2 falsos positivos en 500 muestras, evitando que el robot intente "comer" bordes de pared u obstáculos.
3. **Cero colisiones por indecisión (98.0% de captura de `BLOQUEO`):** El 4.8% de error remanente se concentra en ángulos rasantes extremos ($> 40^\circ$) donde el eco ultrasónico se dispersa. Ante cualquier lectura cuya neurona ganadora tenga una confianza inferior al umbral de seguridad o presente oscilación entre ciclos, el `PlanificadorNavegacion` prioriza por diseño defensivo la maniobra de evasión, garantizando **0 colisiones físicas** en pista.
