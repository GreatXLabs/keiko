# Diseño de Fase B (`v2.0.0`): Navegación 100 % Neuronal, Eliminación de Atajos y Alineación Sim-Real

**Fecha:** 2026-11-23
**Rama de trabajo:** `feat/phase-b-pure-nn-v2` (creada desde `main` en `e56079c`)
**Versión objetivo:** `2.0.0` (rig `mega-3us-2laser`)

---

## 1. Objetivo

Resolver la totalidad del backlog inventariado en [`docs/AUDITORIA_CONSOLIDACION.md`](../../AUDITORIA_CONSOLIDACION.md) para alcanzar una versión limpia **`2.0.0`** donde:
1. Todas las decisiones perceptuales del Servidor Central provengan **exclusivamente de Neuroph 2.98** (`modelo_ultrasonico.nnet` + `modelo_canicas.nnet`), sin umbrales fijos en cm/grados ni filtros post-inferencia (`HRD-01..14`).
2. El cliente de simulación Webots (`robot_client.py`) transmita **exactamente la misma telemetría y capacidades físicas** que el robot real Arduino Mega (`mega-3us-2laser`), eliminando `hasPose()`, `(rx, ry, yaw)` en `TELEMETRY:`, `EVENT:SALIDA_META` y `EVENT:COLISION` hacia el servidor (`SIM-01..13`, `COMP-01..02`).
3. La suite de pruebas Java (`keiko-server`) y Python (`keiko-sim/tests`) pasen en su totalidad (`0` fallos, `0` errores), el controlador `live_visualizer.py` quede reparado, y se valide la navegación pura en Webots headless (reentrenando redes `.nnet` en Neuroph 2.98 si hace falta).
4. El firmware oficial (`orquita_firmware.ino`) quede listo para competencia (`#define PERMITIR_MANUAL 0` por defecto, `COMP-03`) y sincronizado en `VERSION` = `2.0.0`.

---

## 2. Limpieza de `keiko-server/` (`SIM-03..13`, `HRD-01..14`, `COMP-02`)

### 2.1 Telemetría pura sin pose (`VectorSensores.java`, `MensajeProtocolo.java`, `ManejadorCliente.java`)
- **`VectorSensores.java` (`SIM-03`):** retirar los campos `hasPose`, `posX`, `posY`, `theta`, el constructor de pose y los métodos `hasPose()`, `getPosX()`, `getPosY()`, `getTheta()`. Mantener el vector sensorial de 6 canales (`us_izq, us_cen, us_der, laser_izq, laser_der, angulo_servo`) y compatibilidad legacy de 4/6/8/10 canales puramente sensoriales.
- **`MensajeProtocolo.java` (`SIM-03`):** retirar la desambiguación y extracción de pose `(x, y, theta)` en `parsearTelemetria` (solo 4/6/8/10 distancias o con timestamp `5/7/9/11` campos; el formato oficial es 7 campos: 6 canales + timestamp).
- **`ManejadorCliente.java` (`SIM-04`, `COMP-02`):** eliminar el manejo de `EVENT:SALIDA_META:` y retirar la invocación a `planificador.notificarColision(...)` en `EVENT:COLISION` (ya que el robot físico no posee bumpers).
- **`ProtocolParserTest.java`:** actualizar `testParseValid6SensorTelemetry` y `testParseValid8SensorTelemetry` para usar tramas sin pose y agregar aserciones verificando que no se acepten tramas con pose de 12/14 campos ni `SALIDA_META:`.

### 2.2 Inferencia Neuroph 2.98 pura (`ClasificadorNeuroph.java`, `HRD-01..02`, `COMP-05`)
- Eliminar por completo los filtros cinemáticos/binoculares post-inferencia (`HRD-01`: `servoDeg >= 18.0`, `HRD-02`: `dl < dr - 3.5` / `dl <= 23.0`).
- `clasificarSectorUltrasonico(double)` y `clasificarCanicas(VectorSensores)` normalizan entradas y devuelven directamente la etiqueta `argmax` de Neuroph 2.98 (`BLOQUEO`/`ESPACIO_LIBRE`/`SALIDA` y `LOBITO_CENTRO`/`LOBITO_DER`/`LOBITO_IZQ`/`NINGUNA`).
- Mantener la fusión simbólica (`COMP-05`): Red 2 (`LOBITO_*`) asigna `"LOBITO"` al sector correspondiente de `terna[0..2]` únicamente si Red 1 no clasificó `"BLOQUEO"` ni `"SALIDA"` en ese sector (sin umbrales numéricos).

### 2.3 Planificador FSM puro (`PlanificadorNavegacion.java`, `SIM-05..13`, `HRD-03..14`)
- Reducir `PlanificadorNavegacion` a una Máquina de Estados Finita (FSM) limpia que opere **exclusivamente sobre la terna simbólica `String[] pares` (`[IZQ, CEN, DER]`)**, `EstadoRobot`, y memoria temporal de pasos/oscilaciones:
  - Eliminar por completo `ultimoSensores`, `metaSalida*`, `establecerSalidaMeta`, `verificarEscudoPerimetral`, `esPuntoEnPared`, `esLaserEnPared`, `vectorRadar*`, `ventanaUs*`, `esSombraObstaculoUltrasonico`, `esAnchoTicsCanicaValido`, `actualizarVectorRadar`, `calcularDireccionEvasiva` por cm, Stop-and-Scan por distancia y toda rama `if (sensores.hasPose())` o umbral `getUs*`/`getLaser*`/`getAnguloServo`.
  - Mantener firma `evaluarPares(VectorSensores sensores, String[] pares)` por compatibilidad de llamada en `ManejadorCliente` y tests, ignorando distancias crudas internamente (decidiendo exclusivamente sobre `pares`, estado FSM y memoria de pasos/oscilaciones).
  - Preservar íntegramente el comportamiento simbólico verificado por `PlanificadorNavegacionTest`:
    - `ESCAPE`: alineación hacia `SALIDA` (`SALIDA_CEN` recto `3.4, 3.4`; jamba izquierda/derecha bloqueada `3.2, 1.6` / `1.6, 3.2`; pinzado `BLOQUEO, SALIDA, BLOQUEO` retroceso `-2.4, -2.4` por 6 pasos; salida lateral `SALIDA_IZQ`/`SALIDA_DER` en arco hacia adelante; sin `SALIDA` visible en `ESCAPE`, exploración evasiva sin quedar atascado).
    - `BUSQUEDA` / `APROXIMACION` / `CAPTURA`: prioridad a `LOBITO` (centrado suave al centro, arco hacia `LOBITO_IZQ`/`LOBITO_DER` con memoria corta de persistencia simbólica tras perder la vista durante barrido), bloqueo central (`10` pasos retroceso `-3.6, -3.6` + pivote alejado del lado bloqueado), bloqueo lateral (arco hacia adelante `4.8, 2.0` / `2.0, 4.8`), memoria anti-bucle (`HORIZONTE_OLVIDO_OSCILACIONES = 150`, `UMBRAL_OSCILACIONES_ANTIBUCLE = 2` o `esPatronAlternante()` -> 12 pasos retroceso `-4.2, -4.2` + 16 pasos giro amplio `4.2, -2.4`), y `FINALIZACION` estacionaria (`0.0, 0.0`).
  - `notificarCaptura()`: incrementa contador/inicia breve avance/giro de limpieza solo con estado simbólico (sin consultar pose ni distancias).

---

## 3. Alineación Fiel de `keiko-sim/` (`SIM-01..02`, `COMP-01..02`, `30/30 unittest`)

### 3.1 `keiko-sim/controllers/robot_client/robot_client.py`
- **`SIM-01`:** eliminar el envío de `EVENT:SALIDA_META:...` en `try_connect()`. Mantener `detect_exit(robot)` únicamente para auditoría local del juez en Webots (detección de egreso y puntuación).
- **`SIM-02`:** transmitir exactamente 7 campos en `TELEMETRY:` (`usL,usC,usR,lasL,lasR,ang,ts`), idéntico al firmware Mega (`orquita_firmware.ino`).
- **`COMP-01`:** alinear el barrido continuo en espejo al rango oficial del firmware Mega (`[-45.0, +60.0]` grados, paso `5.0°`, leyendo de `simulation_params.json` con fallback `(-45.0, 60.0, 5.0)`), eliminando el recorte artificial `[-15.0, +35.0]` y los modos especiales `approach_binocular_angles` / `escape_guard_angles` que el firmware físico no tiene.
- **`COMP-02`:** mantener `find_penalizable_contact` para conteo y log local de penalizaciones `-5 pts` en Webots, pero **no** enviar `EVENT:COLISION` al servidor.

### 3.2 Reparación de `live_visualizer.py`, `build_worlds.py` y `keiko-sim/tests/`
- **`live_visualizer.py:231`:** corregir llamada `us_physics.apply(raw_readings, robot_pose=(rx, ry, yaw), active_obstacles_xy=active_marbles_xy)` (o pasar obstáculos reales según firma de `UltrasonicPhysicsModel.apply`).
- **`build_worlds.py`:**
  - En `get_arena_geometry`: ajustar `half_gap = exit_width / 2.0 + wall_thickness / 2.0` (`0.1525 m`, eje-a-eje `0.305 m` con `5 mm` de muro -> **luz libre interior exacta `0.300 m`** y `actual_exit_gap_m = 0.305 m` dentro de `0.30 ± 0.005 m`), manteniendo compatibilidad con `SENSOR_LAYOUTS` o actualizando los tests de física/arena a las especificaciones actuales.
  - Regenerar mundos `.wbt` si `build_worlds.py` cambia la apertura a `0.305 m` centerline.
- **`keiko-sim/tests/`:** actualizar `test_meshes_and_arena.py` y `test_ultrasonic_physics.py` para reflejar la geometría actual (5 cajas CAD con hueco bucal `7.0 cm`, variantes activas `standard`/`circular`/`realistic`/`scen_1..14`, salida `NORTH @ (0.00, 0.58)` o exacta, API `active_obstacles_xy`, y layout `3US+2Laser` actual sin perder cobertura de física acústica ni de Webots headless). Meta: **100 % en verde** en `python3 -m unittest discover -s keiko-sim/tests -v`.

---

## 4. Validación Neuronal, Reentrenamiento (si aplica) y Firmware Competencia (`v2.0.0`)

1. **Validación sin hardcodeos:** tras limpiar `ClasificadorNeuroph` y `PlanificadorNavegacion`, correr `mvn clean test` (incluyendo `TwoNetworksInferenceTest`).
2. **Evaluación en simulación Webots headless:** ejecutar escenarios representativos con `keiko-server` + `robot_client.py` en Webots headless para verificar captura de canicas y escape usando 100 % Neuroph 2.98.
3. **Reentrenamiento condicional en Neuroph 2.98:** si `TwoNetworksInferenceTest` o los escenarios headless muestran fallos de clasificación por la ausencia de `HRD-01..14` (p. ej. falsos `LOBITO` en ángulos laterales `>= 18°` o frente a obstáculos `6x6x6`), enriquecer `dataset_canicas.csv` / `dataset_ultrasonico.csv` con muestras limpias (`-45..+60°`, 7 campos sin pose) generadas con `robot_client.py` / `generate_datasets.py` y reentrenar con `TrainTwoNetworksTest` (Neuroph 2.98), sincronizando los `.nnet` resultantes entre `keiko-sim/data/models/`, `keiko-server/models/` y `keiko-server/src/main/resources/models/`.
4. **Firmware de competencia (`COMP-03`):** en `firmware/orquita_firmware/orquita_firmware.ino`, establecer `#define PERMITIR_MANUAL 0` por defecto (documentando claramente que en banco puede cambiarse a `1` para pruebas `WASD`/`'k'`) y actualizar `KEIKO_VERSION = "2.0.0"`.

---

## 5. Cierre de Versión `2.0.0`

- Actualizar `VERSION` a `2.0.0` y propagar a `keiko-server/pom.xml`, `ConfiguracionServidor.java`, `orquita_firmware.ino`, `simulation_params.json`, `robot_client.py` (`./scripts/check_versions.sh` -> **9/9 `[OK]`**).
- Actualizar `docs/AUDITORIA_CONSOLIDACION.md` marcando `SIM-01..13`, `HRD-01..14` y `COMP-01..05` como **RESUELTOS en `v2.0.0`**, actualizar `AGENTS.md` (retirando el aviso de deuda técnica pendiente de Fase A), `CHANGELOG.md` (entrada `[2.0.0]` con hashes `.nnet`, cambios y evidencia real de todas las pruebas Java, Python y Webots headless), y `README.md` / `keiko-server/README.md` / `keiko-sim/README.md`.
- Preparar PR hacia `main` y crear tag de release `v2.0.0` tras el merge.
