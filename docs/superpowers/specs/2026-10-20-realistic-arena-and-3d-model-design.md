# Diseño Técnico: Sub-proyecto 1 — Pista Semielíptica Realista y Modelo 3D Oficial en Webots

**Proyecto:** Orquitas (Keiko) — Ciclo 2026  
**Fecha:** 2026-10-20  
**Alcance:** Sub-proyecto 1 de 3 (Escenario Físico, Obstáculos, Presas y Modelo 3D del Robot)

---

## 1. Contexto y Motivación

La versión inicial de `keiko-sim` modelaba la Bahía de Caza como un cuadrado rígido de $2.40\text{ m} \times 2.40\text{ m}$ con muros de $4\text{ cm}$ de espesor, chaflanes artificiales a $30^\circ$ en la salida, un túnel cerrado de llegada y un chasis construido a mano con cajas primitivas.

De acuerdo con las especificaciones relevadas con la cátedra (`docs/consignas/La Competencia.md`) y los diseños CAD del equipo (`GreatXLabs/Keiko-Modelos-3D`):
1. **La Bahía real** es una **semielipse irregular** armada manualmente con cartón de $5\text{ mm}$ de espesor y $15-20\text{ cm}$ de altura contra una pared plana de $\sim 1.80\text{ m}$ ($2b$, con $b \approx 0.90\text{ m}$) y una profundidad máxima de $\sim 1.20\text{ m}$ ($a \approx 1.20\text{ m}$, área interior $\approx 1.70\text{ m}^2$).
2. **El Estrecho de Salida** mide exactamente **$30\text{ cm}$**, ubicado en el centro del arco curvo. Es simplemente un hueco donde falta cartón: no tiene chaflanes guía, ni marcos, ni postes en "T", ni paredes exteriores cercanas. Como cualquier distancia pared-a-pared dentro de la bahía es $\le 1.85\text{ m}$, la única firma física real de la salida para los sensores ultrasónicos es medir distancias abiertas muy altas ($> 2.00\text{ m}$) hacia el exterior del aula.
3. **Obstáculos internos:** Son de **2 a 3 objetos** del tamaño de un borrador de pizarra ($\sim 14\text{ cm} \times 6\text{ cm}$), significativamente más grandes que las canicas.
4. **Lobitos (3 canicas de $\varnothing = 16\text{ mm}$):** Se ubican con amplio margen respecto a muros y obstáculos para evitar penalizaciones al recolectarlas. La mecánica física de prensión queda fuera del alcance del simulador: al pasarles por arriba con el frente del vehículo se teletransportan fuera del mapa (`Z = -50.0 m`) y disparan el evento de captura.
5. **Modelo 3D del Robot (`Keiko-Modelos-3D`):** El equipo diseñó en FreeCAD el ensamblaje real (`Assembly_Main.FCStd` / `Assembly_Main-Orquita.stl`) con dimensiones exactas de $257\text{ mm} \times 150\text{ mm} \times 76.1\text{ mm}$ (ruedas TT de $\varnothing = 67.2\text{ mm}$ en $X = \pm 52.1\text{ mm}$, $Y = \pm 58.6\text{ mm}$) y una carcasa exterior opcional ("Capote" en `Chassis.FCStd`) con hocico recolector, pala frontal de $200\text{ mm}$ y aleta dorsal de orca.

---

## 2. Arquitectura de la Solución

### 2.1 Geometría Paramétrica de la Bahía Semielíptica Deformable

Se establece el sistema de coordenadas del mundo con el origen $(0, 0)$ en el centro operativo de la bahía:
- **Pared Plana de Fondo (Sur):** Ubicada en $Y = y_{\text{wall}} = -0.60\text{ m}$, extendiéndose en $X \in [-b, +b]$ (por defecto $b = 0.90\text{ m}$, longitud total $1.80\text{ m}$).
- **Arco Perimetral de Cartón:** Se extiende desde $( -b, y_{\text{wall}} )$ hasta $( +b, y_{\text{wall}} )$ pasando por el vértice de salida en $( 0, y_{\text{wall}} + a )$ (por defecto $a = 1.20\text{ m}$, por lo que el vértice se sitúa en $Y = +0.60\text{ m}$).
- **Ecuación Paramétrica con Deformación ("Armado Manual"):**
  Para $\theta \in [0, \pi]$ (donde $\theta = \pi/2$ corresponde al vértice de salida en $X = 0, Y = y_{\text{wall}} + a$):
  $$r(\theta) = 1.0 + \delta_1 \sin(2\theta) + \delta_2 \sin(3\theta) + \delta_3 \cos(\theta)$$
  $$x(\theta) = b \cdot r(\theta) \cdot \cos(\theta)$$
  $$y(\theta) = y_{\text{wall}} + a \cdot r(\theta) \cdot \sin(\theta)$$
  donde $(\delta_1, \delta_2, \delta_3)$ y los factores de escala $(a, b)$ permiten generar variantes de pista (`standard`, `deformed_wide`, `deformed_narrow`, `asymmetric`) para impedir que el robot sobreajuste tiempos o distancias fijas.
- **Discretización y Hueco de Salida de $30\text{ cm}$:**
  - El arco se construye mediante $N = 36$ segmentos rectos de cartón (`Box` con espesor fijo $t = 0.005\text{ m} = 5\text{ mm}$ y altura fija $h = 0.18\text{ m} = 18\text{ cm}$).
  - En el centro del arco se recortan los paneles cuya abscisa cae dentro de la ventana de egreso, garantizando una luz libre exacta de **$0.300\text{ m}$ ($30\text{ cm}$)** entre los bordes planos de cartón izquierdo y derecho.
  - No se generan chaflanes ni paredes de túnel a la salida: más allá de $Y > y_{\text{wall}} + a$ solo existe el piso abierto del aula (`SAFETY_GROUND` de $12\text{ m} \times 12\text{ m}$ con bordes recién a $\pm 6.0\text{ m}$), de modo que cualquier haz ultrasónico que apunte por el hueco de $30\text{ cm}$ mida el fondo de escala ($250-300\text{ cm}$).

### 2.2 Obstáculos ("Borradores") y Lobitos (Canicas)

- **Obstáculos (2 a 3 por variante de mapa):**
  - Dimensiones de referencia de borrador de pizarra: $0.14\text{ m}$ (largo) $\times 0.06\text{ m}$ (ancho) $\times 0.12\text{ m}$ (alto, suficiente para interceptar tanto el plano bajo como el plano alto de sensores cuando se coloca de canto o con soporte, configurable en `simulation_params.json`).
  - Se posicionan como cuerpos `Solid` fijos con orientaciones (`yaw`) variadas dentro del interior de la semielipse, manteniendo un paso libre mínimo de $35\text{ cm}$ respecto a los muros de cartón, la salida y entre sí.
- **Lobitos (3 canicas exactas de $\varnothing = 16\text{ mm}$, masa $5.5\text{ g}$):**
  - Ubicadas con margen $\ge 25\text{ cm}$ respecto a muros perimetrales, obstáculos y el vano de salida.
  - Captura en los controladores (`live_visualizer.py` y `robot_client.py`): al pasarles por arriba con el frente del robot (incluyendo el área de la pala/capote si está activo), se teletransportan a `Z = -50.0 m` y notifican la captura.

### 2.3 Integración Híbrida del Modelo 3D (`Keiko-Modelos-3D`)

Se incorpora un script generador/extractor de mallas (`keiko-sim/scripts/extract_cad_meshes.py`) que procesa los modelos del repositorio `GreatXLabs/Keiko-Modelos-3D` y deposita en `keiko-sim/meshes/` tres archivos STL binarios livianos en metros (sistema de coordenadas Webots: $+X$ frente, $+Y$ izquierda, $+Z$ arriba):

1. `keiko-sim/meshes/orquita_chassis_body.stl`:
   - Extraído y decimado a partir de los componentes del chasis de `Assembly_Main-Orquita.stl` (bandejas inferior y superior de $257 \times 150\text{ mm}$, 6 separadores de bronce M3, 4 motorreductores TT amarillos, soportes y tornillería), excluyendo las 4 ruedas para que estas puedan rotar libremente.
2. `keiko-sim/meshes/orquita_wheel.stl`:
   - Extraído y decimado de la llanta + neumático TT ($\varnothing = 67.2\text{ mm}$, ancho $25\text{ mm}$), centrado en su eje de giro e instanciado en los 4 `HingeJoint` en las coordenadas reales del CAD ($X = \pm 0.0521\text{ m}$, $Y = \pm 0.0586\text{ m}$, $Z = 0.0336\text{ m}$).
3. `keiko-sim/meshes/orquita_capote.stl`:
   - Reconstruido fielmente a partir de la geometría BRep de `Chassis.FCStd` (`Body`: carcasa de $265 \times 160 \times 100\text{ mm}$ con chaflanes traseros, hocico frontal hasta $+197.5\text{ mm}$, pala frontal de $200\text{ mm}$ de ancho y aleta dorsal de orca hasta $Z = 156\text{ mm}$).
   - En `build_worlds.py` y `simulation_params.json`, el parámetro booleano `include_capote` (o flag `--capote` / `--no-capote`) permite incluir o apagar visual y físicamente el Capote sobre el chasis.

---

## 3. Componentes y Archivos Afectados

1. `keiko-sim/scripts/extract_cad_meshes.py` *(Nuevo)*: Descarga/extrae desde `Keiko-Modelos-3D` (o caché local), separa componentes conexos (chasis vs. rueda), decima triángulos para fluido renderizado 60 FPS en Webots, genera la malla del Capote y escribe los 3 archivos `.stl` en `keiko-sim/meshes/`.
2. `keiko-sim/meshes/*.stl` *(Nuevos)*: `orquita_chassis_body.stl`, `orquita_wheel.stl`, `orquita_capote.stl`.
3. `keiko-sim/config/simulation_params.json` *(Modificado)*: Actualiza la sección `arena` (semielipse $1.80\text{ m} \times 1.20\text{ m}$, muros de cartón de $5\text{ mm} \times 18\text{ cm}$, hueco de salida de $30\text{ cm}$) y la sección `robot` (cotas reales del CAD de $257 \times 150 \times 76.1\text{ mm}$, ruedas de $67.2\text{ mm}$ y flag `include_capote`).
4. `keiko-sim/scripts/build_worlds.py` *(Modificado)*:
   - Implementa el generador de muro semielíptico por paneles de cartón de $5\text{ mm}$ con recorte exacto de $30\text{ cm}$ para la salida y deformación parametrizable.
   - Implementa la colocación de obstáculos tipo borrador de pizarra y las 3 canicas con validación de márgenes.
   - Reemplaza las primitivas manuales del robot por los nodos `Mesh` apuntando a `../meshes/orquita_*.stl`, soportando `include_capote=True/False`.
5. `keiko-sim/controllers/live_visualizer/live_visualizer.py`, `keiko-sim/controllers/robot_client/robot_client.py` y `keiko-sim/controllers/dataset_collector/dataset_collector.py` *(Modificados)*:
   - Adaptan los límites del recinto y la detección de cruce de salida a la nueva geometría semielíptica ($Y \in [-0.60, +0.60]\text{ m}$, $X \in [-0.90, +0.90]\text{ m}$, salida en $(0.0, +0.60)\text{ m}$ o según rotación del mundo).

---

## 4. Criterios de Verificación y Pruebas

1. **Verificación geométrica unitaria:**
   - El vano libre en el centro del arco semielíptico mide exactamente $30.0\text{ cm}$ ($\pm 0.5\text{ cm}$).
   - El grosor de cada panel de cartón es de $5\text{ mm}$ (`0.005 m`) y su altura de $18\text{ cm}$ (`0.18 m`).
   - El área encerrada por el polígono de la pista estándar es $\approx 1.70\text{ m}^2$.
   - Ninguna canica queda a menos de $22\text{ cm}$ de un muro u obstáculo.
2. **Verificación de Mallas 3D y Webots:**
   - Los 3 archivos `.stl` existen en `keiko-sim/meshes/`, tienen tamaño liviano ($< 5\text{ MB}$ en total) y Webots carga los mundos `.wbt` sin errores de sintaxis VRML ni advertencias de mallas corruptas.
   - Los rayos ultrasónicos atraviesan limpiamente el hueco de $30\text{ cm}$ hacia el exterior midiendo distancia máxima ($> 200\text{ cm}$).
