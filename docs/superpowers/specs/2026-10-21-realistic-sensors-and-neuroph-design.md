# Diseño Técnico — Sub-proyecto 2: Sensores Ultrasónicos Realistas (6 Frontales Trisector) y Clasificación en Neuroph 2.98

**Fecha:** 2026-10-21  
**Rama:** `feat/realistic-arena-and-3d-model`  
**Estado:** Propuesto para revisión del usuario

---

## 1. Objetivo y Alcance

Adaptar el subsistema sensorial de la simulación (`keiko-sim`), el generador de datasets y entrenamiento (`neuroph-train`), y el clasificador/protocolo del Servidor Central (`keiko-server`) a las condiciones físicas reales de la competencia:

1. **Configuración Principal de 6 Sensores Frontales (`trisector_front_6`):** 3 sensores bajos (`us_low_left`, `us_low_center`, `us_low_right`) y 3 sensores altos (`us_high_left`, `us_high_center`, `us_high_right`), manteniendo soporte modular para configuraciones de 4, 6 (4+2 laterales), 8 y 10 sensores, así como futuros sensores adicionales.
2. **Modelo Físico Realista de Ultrasonido HC-SR04 (40 kHz):** Simulación de límite de sección eficaz acústica sobre canicas esféricas de $\varnothing 16\text{ mm}$ (visibles solo a $\le 32\text{ cm}$ y dentro de $\pm 9^\circ$ del eje acústico), pérdida de eco / *multipath* por reflexión especular en muros de cartón a ángulos oblicuos ($> 42^\circ$), ruido gaussiano proporcional a la distancia, zona muerta ($< 2.5\text{ cm}$) y picos aislados de *crosstalk*.
3. **Procesamiento 100% Neuronal en Neuroph 2.98 y Corrección de Bugs en `keiko-server`:**
   - Todos los canales sensoriales activos ingresan normalizados en $[0, 1]$ (`d_cm / 250.0`) al `MultiLayerPerceptron` de Neuroph 2.98.
   - Corrección del desfase de índices de clases entre `SimulatedDatasetTrainTest.java` y `ClasificadorNeuroph.java`.
   - Corrección de `MensajeProtocolo.java` y `VectorSensores.java` para soportar tramas de $N$ sensores ($4, 6, 8, 10$) sin truncar ni lanzar `NumberFormatException`.
   - Eliminación del método heurístico `inferenciaReglas` con umbrales fijos en `ClasificadorNeuroph.java`.
4. **Iteración y Benchmark Experimental de Esquemas de Clases en Neuroph 2.98:**
   - Evaluar empíricamente 3 esquemas de salida sobre el dataset realista de 6 sensores frontales con distintas arquitecturas ocultas (`[24]`, `[36]`, `[24, 16]`, `[32, 16]`):
     - **Esquema A (5 Clases):** `ESPACIO_LIBRE`, `LOBITO`, `MURO`, `OBSTACULO`, `SALIDA`.
     - **Esquema B (7 Clases - Lobito Direccional):** `ESPACIO_LIBRE`, `LOBITO_IZQ`, `LOBITO_CENTRO`, `LOBITO_DER`, `MURO`, `OBSTACULO`, `SALIDA`.
     - **Esquema C (9 Clases - Lobito y Obstáculo Direccional):** `ESPACIO_LIBRE`, `LOBITO_IZQ`, `LOBITO_CENTRO`, `LOBITO_DER`, `MURO`, `OBSTACULO_IZQ`, `OBSTACULO_CENTRO`, `OBSTACULO_DER`, `SALIDA`.

---

## 2. Disposición Geométrica de los 6 Sensores Frontales (`trisector_front_6`)

En el chasis CAD de la Orquita ($257 \times 150\text{ mm}$), los 6 transductores HC-SR04 se montan en el frente formando 3 pares coaxiales (Izquierda $+22^\circ$, Centro $0^\circ$, Derecha $-22^\circ$):

| Sensor | Nivel | Posición en Chasis $(X, Y, Z)\text{ [m]}$ | Orientación Yaw (rad / grados) | Función Acústica |
| :--- | :---: | :---: | :---: | :--- |
| `us_low_left` | Bajo | `(0.128, +0.052, 0.024)` | `+0.384 rad` (`+22°`) | Barrido rasante izquierdo (canicas, muros, obstáculos) |
| `us_low_center` | Bajo | `(0.134,  0.000, 0.024)` | ` 0.000 rad` (`  0°`) | Barrido rasante central (canicas al frente, hueco salida) |
| `us_low_right` | Bajo | `(0.128, -0.052, 0.024)` | `-0.384 rad` (`-22°`) | Barrido rasante derecho (canicas, muros, obstáculos) |
| `us_high_left` | Alto | `(0.122, +0.052, 0.095)` | `+0.384 rad` (`+22°`) | Referencia superior izquierda (pasa sobre canicas de 1.6 cm) |
| `us_high_center`| Alto | `(0.128,  0.000, 0.095)` | ` 0.000 rad` (`  0°`) | Referencia superior central (contraste coaxial y hueco >200 cm) |
| `us_high_right` | Alto | `(0.122, -0.052, 0.095)` | `-0.384 rad` (`-22°`) | Referencia superior derecha (pasa sobre canicas de 1.6 cm) |

- **Cabeceo (Pitch) = $0^\circ$:** Los sensores bajos se montan paralelos al suelo a $Z = 24\text{ mm}$ para interceptar la parte superior de las canicas ($Z \le 16\text{ mm}$) con la mitad inferior de su cono acústico sin rebotar contra el piso a corta/media distancia (*ground clutter*).

---

## 3. Modelo de Degradación Acústica Realista (`ultrasonic_physics.py`)

Dado que el nodo `DistanceSensor` nativo de Webots calcula intersección geométrica ideal de rayos contra cualquier triángulo de colisión (tratando igual una esfera de vidrio de $16\text{ mm}$ a $1.5\text{ m}$ que una pared de cartón), se introduce el módulo común `keiko-sim/controllers/common/ultrasonic_physics.py` que transforma las lecturas crudas según la física real de $40\text{ kHz}$:

1. **Visibilidad Acústica Real de la Canica ($\varnothing 16\text{ mm}$):**
   - Una canica solo produce eco válido en un sensor de nivel bajo (`us_low_*`) si:
     - La distancia del sensor a la canica es **$d \in [0.03\text{ m}, 0.32\text{ m}]$** ($3\text{ a }32\text{ cm}$), y
     - El ángulo local de la canica respecto al eje del sensor es **$|\alpha| \le 9.5^\circ$**, y
     - No hay un muro u obstáculo interpuesto delante.
   - Si Webots reportó un impacto del rayo bajo contra la canica a $> 32\text{ cm}$ (o con probabilidad de pérdida de eco en el borde $[24, 32]\text{ cm}$), el eco es demasiado débil para disparar el comparador del HC-SR04 y el sensor lee la distancia al objeto de fondo (calculada por el sensor alto coaxial o geometría de pared).
2. **Reflexión Especular en Paredes Oblicuas:**
   - Si el haz incide sobre una pared o bloque con ángulo de incidencia $|\theta_{\text{inc}}| > 42^\circ$ respecto a la normal de la superficie, la onda se refleja especularmente lejos del receptor: el sensor devuelve lectura saturada (`250.0 cm`) o un rebote secundario alargado (*multipath*, $\times 1.35$).
3. **Ruido Gaussiano, Cuantización y Crosstalk:**
   - $\sigma(d) = 0.5\text{ cm} + 0.015 \cdot d$, cuantización de $0.3\text{ cm}$, zona muerta $< 2.5\text{ cm}$, y $1\%$ de probabilidad de glitch de *crosstalk* que el filtro de ventana de 3 muestras del cliente atenúa antes de transmitir.

---

## 4. Arquitectura de Inferencia y Protocolo en `keiko-server` y `neuroph-train`

### 4.1 Vector de Sensores Dinámico (`VectorSensores.java` y `MensajeProtocolo.java`)
- `VectorSensores` almacena un arreglo `double[] distanciasCm` de longitud variable ($4, 6, 8, 10$) en orden canónico:
  - Para **6 sensores frontales (`trisector_front_6`)**: `[d_low_left, d_low_center, d_low_right, d_high_left, d_high_center, d_high_right]`.
- `toArrayNormalizado(double maxRangoCm)`: divide cada canal por `250.0 cm` y acota a $[0.0, 1.0]$. Esto elimina la discrepancia de `MIN_MAX` dinámico entre distintos CSVs y garantiza que tanto el entrenamiento en `neuroph-train` como la inferencia en `keiko-server` utilicen exactamente la misma escala física fija $[0, 250\text{ cm}] \to [0.0, 1.0]$.
- `MensajeProtocolo.parsearTelemetria(String linea)` detecta automáticamente la cantidad de canales sensoriales presentes antes del `timestamp` (entero de milisegundos) y soporta tanto tramas puras de sensores (`TELEMETRY:d1,d2,...,dN,timestamp`) como tramas extendidas de simulación.

### 4.2 Clasificador Neuroph Sin Umbrales Fijos (`ClasificadorNeuroph.java`)
- Lee las etiquetas de las neuronas de salida directamente del modelo `.nnet` (o de la lista canónica ordenada alfabéticamente usada en `DatasetParser`) y retorna tanto la clase ganadora (`argMax`) como el vector de activaciones de salida.
- Soporta transparentemente modelos entrenados con 5, 7 o 9 clases:
  - Si el modelo predice `LOBITO_IZQ` / `LOBITO_CENTRO` / `LOBITO_DER`, `PlanificadorNavegacion` usa directamente la dirección clasificada por la red neuronal para girar hacia la presa o embocarla de frente.
  - Si el modelo es de 5 clases (`LOBITO`), `PlanificadorNavegacion` pondera las entradas normalizadas de los 3 sectores (`Izq`, `Centro`, `Der`) para orientar el giro.
- Se elimina `inferenciaReglas(VectorSensores)` en operación normal; si el archivo `.nnet` no existe al iniciar el servidor, se lanza un error explícito de configuración para impedir que el robot opere sin red neuronal.
