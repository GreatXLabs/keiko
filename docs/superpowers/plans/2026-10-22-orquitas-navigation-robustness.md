# Plan de Optimización y Robustez de Navegación: Orquitas (Keiko)

> **Para agentes de desarrollo:** REQUISITO: Utilizar `superpowers:subagent-driven-development` o `superpowers:executing-plans` para implementar este plan tarea por tarea. Cada paso utiliza la sintaxis de checklist (`- [ ]`) para su seguimiento.

**Meta:** Resolver de raíz los problemas cinemáticos de colisión por barrido de cola, oscilaciones en bucle cerrado (limit cycles), desaprovechamiento del perímetro de la pista y cierres prematuros de simulación, asegurando misiones completas autónomas bajo arquitectura cliente-servidor distribuida estricta (Java Server + Webots Client vía TCP 8888).

**Arquitectura:** 
1. Reducción física del umbral de `BLOQUEO` en el clasificador neuronal Neuroph 2.98 por par coaxial (de 24 cm a 16 cm) para permitir navegación cercana a muros perimetrales sin falsos bloqueos.
2. Sustitución de giros bruscos en el lugar (pivotes puros) por **giro diferencial en arco** ($v_L > 0, v_R > 0$) para evitar que la cola del chasis ($R = 14.88$ cm) golpee obstáculos laterales o muros.
3. Memoria temporal de oscilación ampliada (de 30 a 150 ciclos $\approx 4.8$ s) con detector de limit cycles (rebotes ping-pong y círculos repetitivos) y maniobra de desatasco en dos etapas (retroceso en arco + giro asimétrico).
4. Optimización de latencia de red (`TCP_NODELAY` activado en ambos extremos) y ajuste del watchdog de inactividad para evitar cierres prematuros.
5. Protocolo de auditoría visual del escritorio con captura periódica (`scrot` en `DISPLAY=:1`) para inspección visual de las simulaciones 3D.

**Stack Tecnológico:** Java 21 LTS, Neuroph 2.98, Webots R2023b, Python 3, Sockets TCP/IP, Linux X11 (`DISPLAY=:1`, `scrot`).

**Especificaciones y Restricciones Globales:**
- Servidor: `keiko-server/` (Java 21, NetBeans/Maven).
- Cliente: `keiko-sim/controllers/robot_client/robot_client.py` en `keiko-sim/worlds/bahia_de_caza_client.wbt`.
- Obligatoriedad: Toda prueba oficial debe ejecutarse con el Servidor Java activo y el cliente Webots conectado.
- Persistencia: `estomago.txt` bajo bloqueo concurrente es la única fuente de verdad (exactamente 3 capturas para transicionar a ESCAPE).
- Tiempo máximo reglamentario: < 10 minutos (600 s). Inactividad máxima permitida: < 10 s.

---

## Estructura de Archivos y Responsabilidades

| Archivo | Responsabilidad |
| :--- | :--- |
| `keiko-sim/controllers/dataset_collector/dataset_collector.py` | Generación del dataset por par coaxial con umbral cerrado de `BLOQUEO` ($2.0\text{--}16.0$ cm). |
| `neuroph-train/src/test/java/com/orquitas/neuroph/train/SimulatedDatasetTrainTest.java` | Entrenamiento de la red Neuroph 2.98 con el nuevo dataset cerrado y exportación de pesos. |
| `keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java` | Algoritmo de navegación en Java con giro en arco anti-cola y memoria anti-bucle de 150 ciclos. |
| `keiko-sim/controllers/common/coaxial_neural_planner.py` | Espejo algorítmico en Python del planificador para benchmark y consistencia. |
| `keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java` | Socket TCP con `setTcpNoDelay(true)` para evitar latencia de Nagle. |
| `keiko-sim/controllers/robot_client/robot_client.py` | Controlador Webots con `TCP_NODELAY`, watchdog robusto y telemetría de 6 sensores. |
| `keiko-sim/scripts/audit_desktop_sim.sh` | Script de automatización de auditoría visual con `scrot` y verificación de `estomago.txt`. |

---

## Tareas de Implementación

### Tarea 1: Calibración y Reentrenamiento del Modelo Neuronal con Umbral Cerrado

**Archivos:**
- Modificar: `keiko-sim/controllers/dataset_collector/dataset_collector.py`
- Ejecutar: `neuroph-train/src/test/java/com/orquitas/neuroph/train/SimulatedDatasetTrainTest.java`
- Modelos generados: `keiko-sim/data/models/orquitas_par_coaxial_neuroph.nnet` y `keiko-sim/data/models/orquitas_par_coaxial_weights.json`

**Justificación Técnica:**
Actualmente el dataset clasifica distancias de hasta 24 cm como `BLOQUEO`. Al estar los sensores a 13 cm del centro del chasis, el robot percibe obstáculos a 37 cm y se aleja excesivamente de los muros perimetrales, saturando el centro donde se encuentran los obstáculos `OBSTACLE_BOX_1` y `OBSTACLE_BOX_2`. Reducir el umbral a 16 cm expande el área transitable en 16 cm totales (8 cm por lado) y permite navegar rozando los muros sin activar maniobras de evasión prematuras.

- [ ] **Paso 1.1**: Modificar `dataset_collector.py` para fijar el rango de `BLOQUEO` en $2.0 \le d \le 16.0$ cm y `ESPACIO_LIBRE` en $17.5 \le d \le 180.0$ cm.
- [ ] **Paso 1.2**: Ejecutar `python3 keiko-sim/controllers/dataset_collector/dataset_collector.py` para regenerar `orquitas_dataset_par_coaxial.csv`.
- [ ] **Paso 1.3**: Ejecutar `mvn -f neuroph-train/pom.xml test -Dtest=SimulatedDatasetTrainTest#benchmarkCoaxialPairDataset` para entrenar el modelo Neuroph 2.98 al 100% de precisión y exportar los pesos actualizados.
- [ ] **Paso 1.4**: Verificar que el modelo `.nnet` y el archivo `.json` de pesos se copien correctamente a `keiko-sim/data/models/` y a los recursos del servidor.

---

### Tarea 2: Rediseño Cinemático Anti-Barrido de Cola (Giro en Arco Diferencial)

**Archivos:**
- Modificar: `keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java`
- Modificar: `keiko-sim/controllers/common/coaxial_neural_planner.py`
- Test: `keiko-server/src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java`

**Justificación Técnica:**
El chasis mide 257 mm de largo y 150 mm de ancho, con un radio de esquina trasera de 14.88 cm desde el centro de rotación. Al ejecutar un contragiro puro en el lugar ($v_L = +4.8, v_R = -4.8$), la cola se desplaza en sentido opuesto al giro unos 15 cm. Si el robot está esquivando un obstáculo cercano, la cola impacta inevitablemente contra él.
Sustituir el pivote puro en maniobras laterales por **giro diferencial en arco** ($v_L = 4.8, v_R = 2.0$ para esquivar a la derecha; $v_L = 2.0, v_R = 4.8$ para esquivar a la izquierda) desplaza el Centro Instantáneo de Rotación (CIR) fuera del chasis: el robot avanza curvándose y la cola sigue la estela ya libre sin golpear ningún elemento lateral.

- [ ] **Paso 2.1**: Escribir test unitario en `PlanificadorNavegacionTest.java` que verifique que ante detección lateral simple (`bIzq` o `bDer`) el comando resultante sea un avance con curvatura ($v_L > 0$ y $v_R > 0$ con delta diferencial) en lugar de giro puro en el lugar.
- [ ] **Paso 2.2**: Modificar `PlanificadorNavegacion.java`:
  - En evasión lateral (`bIzq`): aplicar $v_L = 4.8, v_R = 2.0$.
  - En evasión lateral (`bDer`): aplicar $v_L = 2.0, v_R = 4.8$.
  - En encierro frontal absoluto (`bIzq && bCen && bDer`): ejecutar retroceso en arco ($v_L = -4.2, v_R = -2.4$) durante 8 ciclos para separar la trompa antes de iniciar cualquier giro.
- [ ] **Paso 2.3**: Replicar la lógica en `coaxial_neural_planner.py` para mantener sincronía entre Java y Python.
- [ ] **Paso 2.4**: Ejecutar `mvn -f keiko-server/pom.xml test` y confirmar que todos los tests unitarios pasen.

---

### Tarea 3: Detección y Ruptura de Bucles de Límite (Anti-Loop con Memoria Ampliada)

**Archivos:**
- Modificar: `keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java`
- Modificar: `keiko-sim/controllers/common/coaxial_neural_planner.py`
- Test: `keiko-server/src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java`

**Justificación Técnica:**
Actualmente el contador de oscilaciones se resetea a cero si pasan más de 30 ciclos (~0.96 s) sin detectar un bloqueo. Como cruzar la pista de un extremo al otro toma entre 1.5 y 2.0 segundos, el robot que oscila de un muro a otro nunca acumula suficientes eventos para activar el anti-bucle.
Ampliar la ventana de memoria a 150 ciclos (~4.8 s) e incorporar un registro de secuencia de giros permite detectar tanto el rebote tipo ping-pong como giros circulares repetitivos. Ante un bucle detectado, el planificador aplicará una maniobra de escape enérgica: retroceso con desvío angular y un giro asimétrico de más de $90^\circ$ para quebrar la simetría del movimiento.

- [ ] **Paso 3.1**: Escribir test en `PlanificadorNavegacionTest.java` simulando una secuencia de rebotes espaciados por 40 ciclos para comprobar que el detector anti-bucle se dispara.
- [ ] **Paso 3.2**: En `PlanificadorNavegacion.java`:
  - Aumentar el horizonte de olvido de oscilaciones a 150 ciclos.
  - Implementar historial circular de las últimas 6 decisiones de giro.
  - Diseñar la maniobra de desatasco en dos fases: Fase 1 (retroceso en arco de 12 pasos a $v_L = -4.2, v_R = -2.0$), seguida de Fase 2 (giro comprometido de 16 pasos con velocidad máxima).
- [ ] **Paso 3.3**: Replicar en `coaxial_neural_planner.py`.
- [ ] **Paso 3.4**: Ejecutar `mvn -f keiko-server/pom.xml test` y validar el paso de todas las aserciones.

---

### Tarea 4: Optimización del Enlace de Sockets y Prevención de Cierres Prematuros

**Archivos:**
- Modificar: `keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java`
- Modificar: `keiko-sim/controllers/robot_client/robot_client.py`

**Justificación Técnica:**
Por defecto, los sockets TCP en Linux aplican el algoritmo de Nagle, acumulando pequeños paquetes hasta llenar el MSS o recibir un ACK. En un bucle de control a 32 ms con tramas cortas (`TELEMETRY:...` y `COMMAND:...`), esto introduce demoras esporádicas de 40 ms a 200 ms que disparan falsos timeouts en el watchdog del cliente (`> 500 ms`) o detienen los motores temporalmente acumulando tiempo de inactividad que termina cerrando la simulación. Activar `TCP_NODELAY` elimina por completo el buffer de Nagle. Asimismo, el watchdog de inactividad debe protegerse contra falsos positivos durante la inicialización.

- [ ] **Paso 4.1**: En `ManejadorCliente.java`, agregar `socket.setTcpNoDelay(true);` inmediatamente tras aceptar la conexión.
- [ ] **Paso 4.2**: En `robot_client.py`, agregar `sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)` tras el `connect()`.
- [ ] **Paso 4.3**: Ajustar el cálculo del watchdog de inactividad para que solo compute inactividad si los motores están efectivamente en reposo por orden deliberada del servidor y no por retardo transitorio de red.

---

### Tarea 5: Protocolo de Auditoría Visual de Simulación (Desktop Screen Audit)

**Archivos:**
- Crear: `keiko-sim/scripts/audit_desktop_sim.sh`

**Justificación Técnica:**
El usuario solicitó auditar visualmente las simulaciones. El entorno cuenta con el servidor gráfico `DISPLAY=:1` y las herramientas `/usr/bin/scrot` y `/usr/bin/ffmpeg`. Un script de auditoría automatizado permite lanzar la prueba distribuida completa (Servidor Java + Webots), capturar imágenes del escritorio en hitos clave (inicio, captura 1, captura 2, captura 3, escape) y dejarlas listas para inspección visual con `read_file`.

- [ ] **Paso 5.1**: Crear `keiko-sim/scripts/audit_desktop_sim.sh` con permisos de ejecución:
  - Inicia `ServidorCentral` en segundo plano con `--reset`.
  - Inicia Webots con `bahia_de_caza_client.wbt`.
  - Captura periódicamente capturas de pantalla con `DISPLAY=:1 scrot /tmp/webots_audit_<timestamp>.png`.
  - Monitorea `estomago.txt` y registra el tiempo total y colisiones.
- [ ] **Paso 5.2**: Probar la captura en seco y verificar que el archivo generado sea accesible y legible.

---

### Tarea 6: Verificación Integral Distribuida (Servidor Java + Cliente Webots)

**Justificación Técnica:**
Cumplir estrictamente la regla de probar SIEMPRE con el servidor y la simulación cliente conectados, con la red neuronal real Neuroph 2.98.

- [ ] **Paso 6.1**: Iniciar `ServidorCentral` (`mvn exec:java -Dexec.args="--port 8888 --reset"`).
- [ ] **Paso 6.2**: Iniciar `robot_client.py` en Webots sobre `bahia_de_caza_client.wbt`.
- [ ] **Paso 6.3**: Tomar captura visual del escritorio durante la corrida (`scrot`) e inspeccionar el comportamiento del robot.
- [ ] **Paso 6.4**: Verificar:
  1. Capturas físicas: exactamente 3 en `estomago.txt`.
  2. Transición verificada al estado `ESCAPE`.
  3. Abandono completo por el estrecho de salida ($y > 0.60$ m).
  4. Cero o mínimas colisiones registradas.
  5. Tiempo total < 10 minutos (idealmente < 3 minutos).
  6. Ningún cierre prematuro por inactividad.

---

## Criterios de Aceptación (Definición de Terminado)

1. **Persistencia Concurrente:** `estomago.txt` contiene exactamente 3 registros válidos de captura.
2. **Navegación Ajustada al Perímetro:** El robot se desplaza fluidamente entre 15 y 18 cm de los muros perimetrales sin falsos bloqueos.
3. **Cero Colisiones por Barrido de Cola:** Los giros evasivos laterales no golpean obstáculos con la parte trasera del chasis gracias a la cinemática en arco hacia adelante.
4. **Ausencia de Bucles Infinitos:** Toda oscilación se interrumpe y disuelve en un máximo de 2 a 3 ciclos de vaivén.
5. **Autonomía y Escape:** El robot abandona la bahía por el estrecho norte de 30 cm de forma 100% autónoma.
6. **Auditoría Visual Exitosa:** Capturas de pantalla del escritorio tomadas durante la simulación y verificadas visualmente.
