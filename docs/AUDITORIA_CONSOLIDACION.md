# Auditoría Técnica de Consolidación y Cumplimiento Anti-Hardcoding (`v2.0.0-audit.1`)

**Estado del repositorio:** Fase A (`2.0.0-audit.1`) sobre rama `chore/repo-reorg-and-audit` (HEAD `f86830c`)  
**Configuración de hardware objetivo (`rig`):** `mega-3us-2laser` (3× HC-SR04 fijos a 5.5 cm + 2× VL53L0X sobre servos SG90 + 1× VL53L0X estómago + tracción 4WD L293D)  
**Regla rectora evaluada:**  
> *Sensores directo a la red neuronal (`Neuroph 2.98`); prohibido hardcodear decisiones de navegación con umbrales crudos o usar atajos de simulación (`hasPose`, `SALIDA_META`, `posX`/`posY`/`theta`).*

---

## 1. Resumen Ejecutivo de Unificación (Fase A)

Durante la Fase A se consolidaron tres líneas de desarrollo divergentes sin alterar deliberadamente la lógica de decisión de navegación, con el objetivo de establecer un único árbol reproducible y auditar con precisión las violaciones antes de la refactorización de Fase B (`v2.0.0`):

1. **`main` (`25e2600`):** Base de documentación, modelos 3D CAD (`modelos-3d/Assembly_Main.FCStd`, `bahia-keiko.stl`), pruebas de banco y suite inicial `keiko-server` / `keiko-sim`.
2. **`origin/feat/hw-field-ready` (`365b998`):** Soporte de transporte serie real (`ReceptorSerie.java`, puente `bt_rfcomm_bridge.py`, CLI `--serial`/`--baud`/`--list-ports` y carga `.env` en `ConfiguracionServidor.java`), cooldown anti-rebote de capturas y pruebas `HardwareSerialAndCaptureTest.java` / `ConfiguracionServidorTest.java`.
3. **`origin/tests-emi` (`83f86bf`):** Firmware definitivo para Arduino Mega 2560 (`firmware/orquita_firmware/orquita_firmware.ino`), suite Webots 3US+2Laser con múltiples escenarios (`build_worlds.py`, `bahia_de_caza_scen_1..14.wbt`, `bahia_de_caza_circular.wbt`), `DatasetRecorder`, planificador de doble red en `keiko-server` y modelos `.nnet` reentrenados tras simulación dinámica.

### Hallazgo central de la auditoría
La unificación de Fase A compila y sincroniza versiones (`2.0.0-audit.1` | `mega-3us-2laser`) y pasa **36/36** tests Java, **pero el sistema actual incumple gravemente la regla anti-hardcoding y anti-atajos de simulación**:
- **`PlanificadorNavegacion.java` (1528 líneas)** concentra **21 entradas de hallazgos prohibidos** (**9 entradas `SIM-05`..`SIM-13`** —11 bloques/métodos— dependientes de pose omnisciente de simulación `hasPose`, `posX`, `posY`, `theta`, `SALIDA_META`; y **12 entradas `HRD-03`..`HRD-14`** con umbrales crudos sobre `getUs*`, `getLaser*` y `getAnguloServo` que corrigen, sustituyen u omiten a las redes Neuroph 2.98).
- **`ClasificadorNeuroph.java` (375 líneas)** aplica **2 filtros/sobreescrituras** con umbrales crudos post-inferencia sobre Red 2 (`modelo_canicas.nnet`).
- **`robot_client.py` (620 líneas)** y **`ManejadorCliente.java` / `MensajeProtocolo.java` / `VectorSensores.java`** inyectan `EVENT:SALIDA_META` y la pose exacta `(rx, ry, yaw)` del `Supervisor` de Webots en `TELEMETRY:`.
- **`orquita_firmware.ino` (1178 líneas)** cumple con no hardcodear navegación ni enviar pose, pero presenta desalineación de rango de servo (`[-45°, +60°]` vs `<= 50°` en Java y `[-15°, +35°]` en Webots) y expone comandos de banco `TEST:` / `'1'..'6'` / `'c'` que deben blindarse antes de competencia.
- **`keiko-sim` (`unittest`)** presenta **3 fallos y 4 errores** sobre 30 tests por desincronización entre pruebas legacy coaxiales y la refactorización `tests-emi`, incluido un `TypeError` activo en `live_visualizer.py:231` (`marble_positions`).

---

## 2. Matriz de Origen de Componentes y Comparación SHA-256 de `.nnet`

### 2.1 Origen de componentes en el árbol unificado (`f86830c`)

| Componente / Ruta | Origen Principal | Aportes Integrados en Unificación | Estado en `2.0.0-audit.1` |
| :--- | :--- | :--- | :--- |
| `firmware/orquita_firmware/orquita_firmware.ino` | `origin/tests-emi` (`83f86bf`) | Pines Mega verificados (`TRIG {24,27,29}`, `ECHO {25,26,28}`), `VL53L0X` estómago, handshake `HELLO:2.0.0-audit.1,mega-3us-2laser` | Activo en `firmware/` |
| `keiko-server/src/.../ReceptorSerie.java`, `ConfiguracionServidor.java`, `ServidorCentral.java`, `scripts/bt_rfcomm_bridge.py` | `origin/feat/hw-field-ready` (`365b998`) | Transporte serial SPP/USB, `.env`, `--serial`/`--baud`, broadcast defensivo en `ReceptorConexiones.java` (`9a54230`) | Activo y testeado |
| `keiko-server/src/.../PlanificadorNavegacion.java`, `ClasificadorNeuroph.java`, `VectorSensores.java`, `MensajeProtocolo.java` | `origin/tests-emi` (`83f86bf`) + `hw-field-ready` (`365b998`) | Planificador doble red (1528 líneas), soporte 1000 cm timeout VL53L0X, cooldown captura y `HELLO` handshake en `ManejadorCliente.java` | Requiere limpieza total en Fase B |
| `keiko-sim/` (`robot_client.py`, `build_worlds.py`, `worlds/`, `datasets/`) | `origin/tests-emi` (`83f86bf`) | Escenarios `scen_1..14`, `circular`, `DatasetRecorder`, scripts `iniciar_simulacion.sh` / `iterar_simulacion.sh` | 23/30 tests OK (7 legacy/bug) |
| `modelos-3d/`, `pruebas/`, `docs/` | `main` (`25e2600`) | CAD FreeCAD + STL bay, bitácoras de hardware (`pruebas/README.md`) | Activo |
| `archive/` | `main` (`25e2600`) | Firmware UNO legacy, entregas Hito 1–3 y experimentos descartados (`7e8cceb`) | Archivado fuera de ruta activa |
| `VERSION`, `scripts/check_versions.sh` | Fase A (`f86830c`) | Versionado sincronizado entre Maven, Java, Arduino `.ino`, `simulation_params.json` y `robot_client.py` | 9/9 checks OK |

### 2.2 Comparación SHA-256 de modelos `.nnet` entre ramas

Durante la auditoría se calcularon los hashes SHA-256 directos de los binarios `.nnet` en `origin/main` (`25e2600`), `origin/feat/hw-field-ready` (`365b998`), `origin/tests-emi` (`83f86bf`) y el árbol unificado (`HEAD` `f86830c`):

| Archivo | `origin/main` (`25e2600`) | `origin/feat/hw-field-ready` (`365b998`) | `origin/tests-emi` (`83f86bf`) | Unificado `HEAD` (`f86830c`) | Observación |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `modelo_ultrasonico.nnet` | `13c3da5be0b161db...` (solo en `keiko-sim/data/models/`) | `534808b581b20a42...` (`keiko-server/models/` y `resources/models/`) | `c0952be2aa3cdbfb...` (`keiko-sim/data/models/`) | `c0952be2aa3cdbfb...` (en las 3 rutas) | Se unificó la versión más reciente de `tests-emi` en `keiko-server` y `keiko-sim`. Difiere de `hw-field-ready`. |
| `modelo_canicas.nnet` | `b39ab2eced4af5dc...` (solo en `keiko-sim/data/models/`) | `ba191173fe06745d...` (`keiko-server/models/` y `resources/models/`, 14 738 B) | `9e7175709f93b12f...` (`keiko-sim/data/models/`, 35 745 B) | `9e7175709f93b12f...` (en las 3 rutas) | **Cambio estructural:** `hw-field-ready` tenía una red distinta (14.7 KB) que la de `tests-emi` (35.7 KB). Unificadas al artefacto `tests-emi`. |
| `orquitas_production_champion.nnet` (legacy coaxial) | `2df7196c78ce96f4...` | `55b64483e49c3cc2...` | `2df7196c78ce96f4...` | `2df7196c78ce96f4...` | Los modelos coaxiales legacy en `hw-field-ready` también divergen respecto a `main`/`tests-emi`. |

> **Riesgo de pesos en Fase B:** Los modelos `c0952be2...` (`modelo_ultrasonico.nnet`) y `9e717570...` (`modelo_canicas.nnet`) fueron entrenados mientras existían filtros/umbrales compensatorios en `ClasificadorNeuroph` y `PlanificadorNavegacion`. Al eliminar los umbrales crudos en Fase B, deberá validarse su tasa real de acierto con lecturas puras de campo/simulación y reentrenar si es necesario.

---

## 3. Hallazgos Prohibidos (Hardcoding Sensorial y Atajos de Simulación)

### 3.1 `[PROHIBIDO - ATAJO SIMULACIÓN]` (Uso de Pose GPS / `SALIDA_META` / Geometría Analítica)

| ID | Archivo | Líneas | Símbolo / Método | Regla o Mecanismo Detectado | Impacto | Acción Requerida (Fase B `v2.0.0`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **SIM-01** | `keiko-sim/controllers/robot_client/robot_client.py` | 57–95, 200–202, 260–264 | `detect_exit`, `try_connect` | Lee `EXIT_PLATFORM` vía `Supervisor` de Webots y envía `EVENT:SALIDA_META:<x>,<y>` al servidor al conectar. | Filtra al cerebro la coordenada exacta de la salida. Imposible en hardware real. | Dejar `detect_exit` exclusivamente para el juez local del simulador (auditoría de egreso); prohibir envío de `EVENT:SALIDA_META`. |
| **SIM-02** | `keiko-sim/controllers/robot_client/robot_client.py` | 496–500 | `main` (`telemetry_msg`) | Emite 10 campos en `TELEMETRY:` incluyendo `rx, ry, yaw` del `Supervisor` de Webots. | El servidor recibe pose exacta sin ruido en lugar de 7 campos de sensores reales. | Transmitir estrictamente 7 campos (`usL,usC,usR,lasL,lasR,ang,ts`), idéntico a `orquita_firmware.ino:1024-1038`. |
| **SIM-03** | `keiko-server/.../MensajeProtocolo.java` & `VectorSensores.java` | `MP`: 59–103; `VS`: 21–24, 40–50, 140–143 | `parsearTelemetria`, `hasPose`, `getPosX`, `getPosY`, `getTheta` | El protocolo desambigua y transporta `(posX, posY, theta)` dentro de `VectorSensores`. | Mantiene acoplamiento oculto a telemetría omnisciente de Webots. | Deprecar/eliminar soporte de pose en `TELEMETRY:` y retirar `hasPose`/`getPos*`/`getTheta` de la ruta de navegación. |
| **SIM-04** | `keiko-server/.../ManejadorCliente.java` | 259–270 | `procesarEvento` (`SALIDA_META:`) | Parsea `EVENT:SALIDA_META:` e invoca `planificador.establecerSalidaMeta(ex, ey)`. | Permite inyección de coordenadas de escape en el servidor. | Eliminar rama `SALIDA_META:` en `ManejadorCliente.java`. |
| **SIM-05** | `keiko-server/.../PlanificadorNavegacion.java` | 108–118 | `metaSalidaX`, `metaSalidaY`, `tieneMetaSalida`, `establecerSalidaMeta` | Almacena coordenadas de puerta (`0.0, 0.60` por defecto o inyectadas por `SALIDA_META`). | Acopla el cerebro a un mapa absoluto fijo de la arena. | Eliminar campos y método `establecerSalidaMeta`. |
| **SIM-06** | `keiko-server/.../PlanificadorNavegacion.java` | 248–251 | `evaluarPares` (retroceso) | Cancela retroceso si `sensores.hasPose() && getPosY() < -0.42 && Math.sin(getTheta()) > 0.0`. | Comportamiento distinto entre simulación y robot real (`hasPose() == false`). | Eliminar comprobación de pose; resolver con sensores traseros/estado FSM si aplica. |
| **SIM-07** | `keiko-server/.../PlanificadorNavegacion.java` | 326–332 | `evaluarPares` (giro comprometido) | Sustituye giro en el lugar por avance si `hasPose() && getPosY() < -0.42 && Math.sin(getTheta()) > 0.0 && getUsCenter() > 14.0`. | Combina pose GPS + umbral crudo para despegar cola. | Eliminar bloque dependiente de pose y umbral crudo. |
| **SIM-08** | `keiko-server/.../PlanificadorNavegacion.java` | 360–368 | `evaluarPares` (encierro total) | Calcula `colaCercaMuro` con `getPosX()` (`±0.62`), `getPosY()` (`-0.40`/`0.20`) y `getTheta()` para anular retroceso. | Solo funciona en Webots; en pista real sí retrocede. | Eliminar `colaCercaMuro` por pose. |
| **SIM-09** | `keiko-server/.../PlanificadorNavegacion.java` | 391–511 | `evaluarPares` (Modo `ESCAPE` por pose) | Navega hacia `metaSalidaX`/`metaSalidaY` por trigonometría analítica (`atan2`, `errorLateral`, `distBoca`, corredor de `0.32 m`), ignorando por completo la red neuronal cuando `hasPose()` es `true`. | **Atajo crítico:** en Webots la Orquita escapa por GPS; en hardware real (`hasPose() == false`) este bloque nunca corre. | Eliminar todo el bloque `if (sensores != null && sensores.hasPose())` del modo `ESCAPE`; escapar exclusivamente por clasificación neuronal (`SALIDA` / `BLOQUEO` / `ESPACIO_LIBRE`). |
| **SIM-10** | `keiko-server/.../PlanificadorNavegacion.java` | 780–790 | `evaluarPares` (Prioridad 5b anti-escape) | Usa `py > 0.35 && Math.abs(px) < 0.25 && Math.sin(th) > 0.0` para decidir si evadir una `SALIDA` detectada en `BUSQUEDA`. | Evita fuga prematura usando coordenadas absolutas de la puerta. | Eliminar rama `hasPose()` y confiar la detección `SALIDA` a Red 1 + FSM de `BUSQUEDA`. |
| **SIM-11** | `keiko-server/.../PlanificadorNavegacion.java` | 1033–1040, 1062–1063, 1116–1124 | `iniciarRetrocesoArcoYPivote`, `notificarCaptura`, `notificarColision` | Calculan proximidad trasera a paredes con `getPosX()`, `getPosY()`, `getTheta()` (`±0.55`, `±0.62`, `-0.40`, `-0.35`, `0.24`). | Divergencia cinemática directa entre Webots y hardware real. | Eliminar las tres guardas basadas en `hasPose()`. |
| **SIM-12** | `keiko-server/.../PlanificadorNavegacion.java` | 1242–1287 | `esPuntoEnPared`, `esLaserEnPared` | Proyecta el haz del VL53L0X al mapa global usando `hasPose()`, `getPosX()`, `getPosY()`, `getTheta()` contra ecuaciones analíticas de la pista (`0.95 × 1.25` o círculo `R=0.90`). | Filtra falsos positivos de canica usando geometría exacta conocida de la pista en simulación. | Eliminar `esPuntoEnPared` y `esLaserEnPared`; Red 2 debe distinguir canica vs pared por entrada sensorial pura. |
| **SIM-13** | `keiko-server/.../PlanificadorNavegacion.java` | 281–288, 1411–1526 | `verificarEscudoPerimetral` | Escudo analítico completo usando `hasPose()`, geometría de la bahía, distancia a `metaSalida` (`< 0.28`, `< 0.40`) y caso especial de spawn en esquina SO (1510–1518). | Conduce al robot lejos de los muros sin usar sensores ni red neuronal en Webots; en hardware real retorna `null`. | Eliminar `verificarEscudoPerimetral` por completo. |

### 3.2 `[PROHIBIDO - DECISIÓN SENSORIAL HARDCODEADA]` (Umbrales Crudos y Sobreescritura de Red Neuronal)

| ID | Archivo | Líneas | Símbolo / Método | Regla o Umbral Crudo Detectado | Impacto | Acción Requerida (Fase B `v2.0.0`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **HRD-01** | `keiko-server/.../ClasificadorNeuroph.java` | 189–200 | `clasificarCanicas` | Filtro cinemático post-Red 2: si `servoDeg >= 18.0`, anula `"LOBITO_IZQ"` (`usLeft < 38.0`), `"LOBITO_DER"` (`usRight < 38.0`) y siempre `"LOBITO_CENTRO"`. | Descarta predicciones de `modelo_canicas.nnet` con reglas `if` de umbral fijo. | Eliminar filtro; incorporar los ejemplos problemáticos (`servo >= 18°` frente a pared) al dataset de entrenamiento de Red 2. |
| **HRD-02** | `keiko-server/.../ClasificadorNeuroph.java` | 206–220 | `clasificarCanicas` | Si `usCenter >= 16.0`: fuerza `"LOBITO_CENTRO"` cuando `servoDeg <= -10.0 && dl <= 23.0 && dr <= 23.0 && |dl - dr| <= 3.8`; o reasigna `"LOBITO_CENTRO"` a `IZQ`/`DER` con `dl < dr - 3.5`. | Sobreescribe la salida softmax/MLP de Red 2 con comparaciones geométricas fijas. | Eliminar reglas de reasignación; delegar la discriminación `CENTRO`/`IZQ`/`DER` exclusivamente a Red 2. |
| **HRD-03** | `keiko-server/.../PlanificadorNavegacion.java` | 168–194 | `evaluarPares` | Cuando `|angServo| <= 50.0`, anula `"LOBITO"` de Red 2 usando `!esAnchoTicsCanicaValido(...)` o `esSombraObstaculoUltrasonico(...)`, y reescribe a `"BLOQUEO"` si `us < 22.0` o `dl/dr < 11.5` (`10.5` en centro). | Sustituye la clasificación neuronal por heurísticas de sombra y umbrales fijos (`22.0`, `11.5`, `10.5`). | Eliminar capa de sobreescritura; entrenar Red 2 con las lecturas US+Láser+Servo de cubos 6×6×6 y muros. |
| **HRD-04** | `keiko-server/.../PlanificadorNavegacion.java` | 197–203 | `evaluarPares` | "Reflejo anticolisión de canica": inyecta `"LOBITO"` sin consultar a Red 2 cuando los 3 US `> 36.0`, `|angServo| <= 15.0`, `dl <= 18.0 && dr <= 18.0 && |dl - dr| <= 3.2`. | Crea detecciones de presa 100% hardcodeadas por umbrales crudos. | Eliminar inyección directa; toda detección de `LOBITO` debe provenir de Red 2. |
| **HRD-05** | `keiko-server/.../PlanificadorNavegacion.java` | 207–212 | `evaluarPares` | Fuerza `"BLOQUEO"` en `izq`/`der` si `dl < 5.0` o `dr < 5.0`, sin intervención de Red 1. | Clasificación de bloqueo por umbral fijo en láser. | Eliminar reetiquetado; Red 1 (o red fusionada) debe clasificar `BLOQUEO`. |
| **HRD-06** | `keiko-server/.../PlanificadorNavegacion.java` | 225–238, 514–516 | `evaluarPares` (`veSalidaDirecta` y fallback `ESCAPE`) | Detecta `SALIDA` por umbral crudo `getUsLeft() > 160.0 \|\| getUsCenter() > 160.0 \|\| getUsRight() > 160.0` además de etiqueta `"SALIDA"`, y cancela retroceso con `getUsCenter() >= 14.0`. | Bypassea `modelo_ultrasonico.nnet` con umbral `> 160.0 cm`. | Eliminar comparación `> 160.0`; usar exclusivamente la etiqueta `"SALIDA"` emitida por Red 1. |
| **HRD-07** | `keiko-server/.../PlanificadorNavegacion.java` | 254, 265–274, 305–316, 348, 354–358 | `evaluarPares` (control de maniobras y encierro) | Múltiples cortes y disparos por distancias crudas: `usCenter < 8.0`, `us >= 10.0` para validar `veCanica`, fin/extensión de giro con `usCenter > 48.0 / < 25.0`, `usLat > 26.0 / < 17.0`, `laser > 18.0`, `pegadoFrontal` (`usC < 11.0`, `laser < 9.0`). | Decisiones tácticas de qué tan despejado o bloqueado está el entorno mediante 10+ umbrales fijos en lugar de la terna neuronal. | Basar inicio/fin de evasión y validación de estado en las etiquetas de red + contadores de pasos FSM. |
| **HRD-08** | `keiko-server/.../PlanificadorNavegacion.java` | 436–442, 478–500 | `evaluarPares` (evasión dentro de `ESCAPE` con pose) | Evalúa `bloqueoFrente` y proximidad a jambas con umbrales crudos (`< 18.0`, `< 13.0`, `< 11.0`, `< 10.0`, `< 8.0`, `< 7.0`, `< 6.0`). | Hardcoding sensorial anidado dentro del atajo de pose. | Se elimina junto con **SIM-09**. |
| **HRD-09** | `keiko-server/.../PlanificadorNavegacion.java` | 555–573, 616–767 | `evaluarPares` (Prioridad 5a: `APROXIMACION` y Stop-and-Scan) | Filtra `"LOBITO"` con `us >= 10.0`, `laser >= 2.0`; detecta `riesgoUltrasonico` (`usC < 16`, `usLat < 14`) y `bloqueoEnTrayectoria` (`usC < 12`, `usLat < 10`); dispara Stop-and-Scan con `dMinCand in (11, 23]`; refresca/centra objetivo a ciegas por asimetría láser (`< 28.0`, `< 25.0`, `< 12.0`, `<= 18.0`). | Mezcla lógica FSM legítima con clasificación/filtrado sensorial crudo y seguimiento sin salida neuronal activa. | Mantener solo la FSM de `APROXIMACION` sobre etiquetas `LOBITO_*` de Red 2; eliminar filtros `us >= 10` y detección/refresco por umbrales crudos. |
| **HRD-10** | `keiko-server/.../PlanificadorNavegacion.java` | 793–795, 848–872 | `evaluarPares` (Prioridades 5b y 5c) | `jambaCercana` con `usLat < 25.0`, `usC < 28.0`, `laser < 15.0`; evasión lateral con `laser < 7.0` / `< 10.0`. | Escala la severidad de evasión desde distancias crudas en vez de clases neuronales. | Actuar sobre la terna neuronal (`BLOQUEO`, `SALIDA`, `ESPACIO_LIBRE`) sin reglas por cm. |
| **HRD-11** | `keiko-server/.../PlanificadorNavegacion.java` | 889–922 | `evaluarPares` (Stop-and-Scan en exploración y centrado pasillo) | Detecta `anomaliaLaser` (`laser in [3, 24) && us >= laser + 7.0`) en zonas `us > 18 / > 16`; regula `sweep` y `vBase` por `cl < 32.0`, `us < 28.0`, `usC < 35.0`. | Inferencia implícita de objetos/pasillos usando diferencia US−Láser y umbrales fijos. | Eliminar detector de anomalía por diferencia cruda US−Láser; usar barrido continuo y salidas de red. |
| **HRD-12** | `keiko-server/.../PlanificadorNavegacion.java` | 1026–1030, 1062, 1093, 1141–1145 | `iniciarRetrocesoArcoYPivote`, `notificarCaptura`, `notificarColision`, `activarManiobraAntiBucle` | Evalúan `pegadoFrontal` (`usC < 11.0`, `laser < 9.0`), `usC < 25.0` y `frenteTransitable` (`usC > 10.5`, variable declarada y no utilizada). | Mezcla decisiones físicas de retroceso con umbrales arbitrarios en cm. | Delegar detección de bloqueo frontal a la etiqueta `BLOQUEO` de Red 1. |
| **HRD-13** | `keiko-server/.../PlanificadorNavegacion.java` | 1171–1193 | `calcularDireccionEvasiva` | Compara `usLeft`/`usRight`/`laserLeft`/`laserRight` contra `22.0 cm` y diferencia `>= 0.8 cm` para elegir sentido de giro. | Reemplaza las etiquetas neuronales laterales por comparación numérica directa de sensores. | Elegir sentido evasivo desde las clasificaciones neuronales `[IZQ, CEN, DER]` y la memoria de oscilación FSM. |
| **HRD-14** | `keiko-server/.../PlanificadorNavegacion.java` | 1196–1239, 1307–1374 | `registrarVentanaUltrasonica`, `esSombraObstaculoUltrasonico`, `actualizarVectorRadar`, `esAnchoTicsCanicaValido` | Heurística completa de rechazo de cubos 6×6×6 cm y paredes mediante ventana US de 20 ciclos y radar de 17 tics (`minLado <= dLaser + 8.5`, `minLado < 35 && dLaser < 26`, saltos `> 26.0`, ancho en tics `<= 7` o `<= 10`, alertas flanco `dl < 14.5`). | **Clasificador manual paralelo:** duplica y anula la responsabilidad de Red 2 mediante reglas geométricas hardcodeadas. | Eliminar este clasificador heurístico en Fase B; incorporar contexto temporal/ventana o características de barrido directamente al entrenamiento de Red 2 si se requiere discriminar cubos. |

### 3.3 Hallazgos Complementarios (Simulación vs Hardware Real y Seguridad Reglamentaria)

| ID | Archivo | Líneas | Aspecto Detectado | Impacto / Observación | Acción Requerida (Fase B `v2.0.0`) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **COMP-01** | `firmware/orquita_firmware/orquita_firmware.ino` vs `PlanificadorNavegacion.java` vs `robot_client.py` | `.ino`: 94–95; `PN`: 75–77, 168; `rc.py`: 210–211 | Desalineación de rango angular de servo | Firmware Mega barre `[-45°, +60°]` (coincide con normalización `[-45, +60]` en `ClasificadorNeuroph:170`), pero `PlanificadorNavegacion:168` ignora ángulos `> 50°` (`+55°`, `+60°`) y `robot_client.py` barre `[-15°, +35°]`. | Unificar rango de barrido y normalización única en `simulation_params.json` / `ConfiguracionServidor` para sim y firmware. |
| **COMP-02** | `keiko-sim/controllers/robot_client/robot_client.py` | 579–602 | `EVENT:COLISION` con cuadrante exacto | El simulador usa `ContactPoints` del Supervisor para informar cuadrante (`front_left`, `rear_right`, etc.). El robot físico Mega **no posee bumpers**, por lo que `notificarColision(lado)` en `PlanificadorNavegacion` nunca se dispara en pista real. | Desacoplar la navegación de eventos exclusivos de Webots o simular únicamente sensores presentes en el hardware real. |
| **COMP-03** | `firmware/orquita_firmware/orquita_firmware.ino` | 750–809, 837–846, 884–886 | Comandos de banco `TEST:<tecla>` y escenarios simulados `'1'..'6'` / `'c'` | Permiten inyectar vectores falsos o disparar captura manual por serie/USB. Útil en banco, pero riesgoso frente al reglamento de pista si quedara activo. | Envolver modos simulados y `TEST:` bajo bandera `#define HABILITAR_BANCO_PRUEBAS false` para compilación de competencia. |
| **COMP-04** | `firmware/orquita_firmware/orquita_firmware.ino` | 123, 519, 536, 554, 614, 646 | Contador local `canicasRecolectadas` en firmware | Desarma el ToF del estómago al contar 3 localmente. Aunque la transición oficial a `ESCAPE` ocurre solo en `keiko-server` vía `estomago.txt`, si el servidor descartara una captura por rebote/error de enlace el Arduino quedaría desarmado. | Hacer que el armado/desarmado dependa exclusivamente de `STATE_CHANGE:ESCAPE` del servidor (o rearmar por comando explícito). |
| **COMP-05** | `keiko-server/.../ClasificadorNeuroph.java` | 276–291 | Arbitraje simbólico Red 1 vs Red 2 | `LOBITO` de Red 2 solo pisa `terna[i]` si Red 1 no dijo `BLOQUEO` ni `SALIDA`. No usa umbrales crudos, pero si Red 1 sobre-predice `BLOQUEO`, oculta canicas. | Evaluar calibración conjunta en Fase B sin reglas de distancia cruda. |

---

## 4. Mecanismos Permitidos (`[PERMITIDO - MEMORIA FSM / SEGURIDAD ENLACE]`)

Los siguientes mecanismos cumplen con la arquitectura distribuida del Proyecto Orquitas y **deben conservarse** en Fase B:

| Archivo | Líneas | Mecanismo | Justificación Reglamentaria y Arquitectónica |
| :--- | :--- | :--- | :--- |
| `firmware/orquita_firmware/orquita_firmware.ino` | 131, 951–988 | Fail-Safe estricto `< 500 ms` (`FAILSAFE_TIMEOUT_MS = 500`) | Detiene tracción y picker automáticamente si pasan > 500 ms sin `COMMAND` ni `HEARTBEAT`. Obligatorio por PSR. |
| `keiko-sim/controllers/robot_client/robot_client.py` | 108, 564–571 | Fail-Safe `< 500 ms` en cliente Webots | Detiene los 4 motores ante pérdida de enlace o timeout > 0.500 s. |
| `keiko-server/.../GestorEstomago.java` | 25, 34–110 | Persistencia crítica en `estomago.txt` (`synchronized(bloqueoArchivo)`, `flush()`, `fos.getFD().sync()`) | Única fuente de verdad para "Panza Llena", protegida contra condición de carrera ("La Linareada") y pérdida de buffer. |
| `keiko-server/.../AuditorEstomago.java` | 14–92 | Hilo auditor independiente sondeando `estomago.txt` cada `<= 25 ms` | Emite transición formal a `ESCAPE` únicamente al confirmar 3 registros físicos válidos en disco. |
| `keiko-server/.../ManejadorCliente.java` | 146–164, 182–200, 226–246 | Heartbeat (`200 ms`), handshake `HELLO:<version>,<rig>` y ventana anti-rebote (`cooldownCapturaMs`) | Garantiza liveness del enlace Bluetooth SPP, compatibilidad de rig/versión y evita doble conteo mecánico de una misma canica. |
| `firmware/orquita_firmware/orquita_firmware.ino` | 153–182 | `FiltroUltrasonico` (mediana de 3 + EMA 0.65/0.35) | Validación y filtrado de ruido espurio en frontera del cliente antes de transmitir telemetría. |
| `keiko-sim/controllers/common/ultrasonic_physics.py` | 111–137 | `SensorBorderFilter` (mediana de 3 + EMA alpha=0.65) | Equivalente de simulación del filtro de frontera del firmware. |
| `keiko-server/.../PlanificadorNavegacion.java` | 45, 57–70, 928–983, 1377–1406 | Memoria temporal de FSM, `contadorOscilaciones`, `historialLados` y maniobras comprometidas por contador de pasos | Memoria conductual sobre etiquetas simbólicas (`BLOQUEO`, `LOBITO`, `SALIDA`, `ESCAPE`) para evitar ciclos límite en esquinas; **válida siempre que no dependa de distancias crudas ni pose GPS**. |
| `firmware/orquita_firmware/orquita_firmware.ino` | 1–1178 (excepto `COMP-03`/`COMP-04`) | Separación Cliente-Servidor sin lógica de navegación | El Arduino Mega actúa puramente como muestreador de sensores y actuador de `COMMAND:` / `STATE_CHANGE:`. |

---

## 5. Resultados Reales Observados de Pruebas y Verificación

Todas las pruebas se ejecutaron el **2026-11-21** sobre el estado exacto de `HEAD` (`f86830c`, versión `2.0.0-audit.1`):

### 5.1 Sincronización de Versiones (`./scripts/check_versions.sh`)
- **Resultado:** `exit 0` (**9/9 verificaciones `[OK]`**).
- Valida coincidencia de `2.0.0-audit.1` y `mega-3us-2laser` en `VERSION`, `keiko-server/pom.xml`, `ConfiguracionServidor.java`, `orquita_firmware.ino`, `keiko-sim/config/simulation_params.json` y `robot_client.py`.

### 5.2 Servidor Java (`cd keiko-server && mvn clean test`)
- **Resultado:** `exit 0` (`BUILD SUCCESS`, tiempo total: `2.505 s`).
- **Resumen exacto:** **36 tests ejecutados, 0 Failures, 0 Errors, 0 Skipped**:
  - `ProtocolParserTest`: 8/8 OK
  - `PlanificadorNavegacionTest`: 9/9 OK
  - `ConfiguracionServidorTest`: 3/3 OK
  - `HardwareSerialAndCaptureTest`: 3/3 OK
  - `TwoNetworksInferenceTest`: 4/4 OK
  - `ConcurrencyEstomagoTest`: 2/2 OK
  - `Hito2IntegrationTest`: 2/2 OK
  - `NeurophInferenceTest`: 5/5 OK

### 5.3 Simulador Python (`python3 -m unittest discover -s keiko-sim/tests -v`)
- **Resultado:** `exit 1` (**30 tests ejecutados en `12.173 s`: 23 OK, 3 FAIL, 4 ERROR**).
- Desglose exacto por suite:
  - `test_coaxial_neural_planner.py`: **15/15 OK**.
  - `test_collision_audit.py`: **5/5 OK**.
  - `test_meshes_and_arena.py`: **1 OK**, **3 FAIL**, **2 ERROR**:
    1. `FAIL` `test_chassis_collision_boxes_follow_cad_and_clear_wheels`: `build_worlds.py` genera 5 cajas de colisión CAD incluyendo parachoques frontales (`0.09875 ±0.0575 0.039`, `0.09875 0 0.0525`, etc.), mientras el test legacy espera 3 cajas (`0.095 0 0.048`, `-0.095 0 0.048`, `0 0 0.048`).
    2. `FAIL` `test_semi_ellipse_wall_segments_and_exit_gap`: `actual_exit_gap_m = 0.31000419332157125` frente a `0.30 ± 0.005` (`0.0100 m` de desvío, fuera de tolerancia).
    3. `FAIL` `test_webots_loads_new_world_without_vrml_errors`:
       - Busca `'Salida detectada: NORTH @ (0.00, 0.60)'` pero `bahia_de_caza.wbt` reporta `NORTH @ (0.00, 0.58)` y no tiene sensores coaxiales `us_low_*` (0 transductores encontrados para `live_visualizer.py`).
       - **Bug activo en `keiko-sim/controllers/live_visualizer/live_visualizer.py:231`:** llama a `us_physics.apply(raw_readings, robot_pose=(rx, ry, yaw), marble_positions=active_marbles_xy)`, lanzando `TypeError: UltrasonicPhysicsModel.apply() got an unexpected keyword argument 'marble_positions'` porque `UltrasonicPhysicsModel.apply` (`ultrasonic_physics.py:61`) espera `active_obstacles_xy=None` en lugar de `marble_positions`.
    4. `ERROR` `test_generated_world_uses_meshes_and_has_no_chamfers_or_exit_tunnel`: `TypeError: generate_world() got multiple values for argument 'include_capote'` (en `build_worlds.py:1110`, la firma actual `generate_world(controller_name, map_name="standard", exit_wall="north", include_capote=None)` ya no recibe el argumento posicional `sensor_config`).
    5. `ERROR` `test_marbles_and_obstacles_clearance`: `KeyError: 'labyrinth'` en `build_worlds.MAP_CONFIGS`.
  - `test_ultrasonic_physics.py`: **2 OK**, **2 ERROR**:
    6. `ERROR` `test_marble_acoustic_visibility_limit`: `TypeError: UltrasonicPhysicsModel.apply() got an unexpected keyword argument 'marble_positions'` (test legacy desactualizado respecto a `ultrasonic_physics.py:61`).
    7. `ERROR` `test_trisector_front_6_is_default_layout_and_preserves_other_configs`: `AttributeError: module 'build_worlds' has no attribute 'SENSOR_LAYOUTS'`.

---

## 6. Hoja de Ruta Puntual para Fase B (`v2.0.0`)

1. **Erradicar por completo el atajo de pose GPS (`SIM-01`..`SIM-13`):**
   - Retirar `(rx, ry, yaw)` de `TELEMETRY:` en `robot_client.py` y eliminar `EVENT:SALIDA_META` del enlace cliente-servidor.
   - Eliminar `hasPose`, `getPosX`, `getPosY`, `getTheta`, `establecerSalidaMeta`, `verificarEscudoPerimetral`, `esPuntoEnPared`, `esLaserEnPared` y el modo `ESCAPE` trigonométrico de `PlanificadorNavegacion.java` y `ManejadorCliente.java`.
2. **Eliminar hardcoding sensorial (`HRD-01`..`HRD-14`) y reentrenar Neuroph 2.98 si es necesario:**
   - Dejar `ClasificadorNeuroph` como inferencia pura: entrada normalizada -> `NeuralNetwork.calculate()` -> argmax -> etiqueta simbólica, sin filtros `if` por cm/grados.
   - Reducir `PlanificadorNavegacion.java` a una FSM pura sobre las ternas `[IZQ, CEN, DER]` de Red 1 + Red 2 y memoria temporal de maniobras/anti-bucle.
   - Si al quitar `esSombraObstaculoUltrasonico` y los filtros de `ClasificadorNeuroph` baja la precisión con cubos 6×6×6 o muros oblicuos, incorporar lecturas reales y de simulación limpias (o ventana temporal de sensores) al entrenamiento en `neuroph-train/` y regenerar `.nnet`.
3. **Unificar parámetros de rig y blindar firmware (`COMP-01`..`COMP-04`):**
   - Alinear ángulo de barrido y normalización entre `orquita_firmware.ino`, `robot_client.py`, `VectorSensores.java` y `ClasificadorNeuroph.java`.
   - Desactivar `TEST:` y escenarios `'1'..'6'` bajo flag de compilación de competencia y vincular el rearmado del estómago al estado del servidor.
4. **Reparar `keiko-sim` y su suite de pruebas (`30/30` en verde):**
   - Corregir `live_visualizer.py:231` (`marble_positions` -> `active_obstacles_xy` o retiro del controlador coaxial si queda archivado).
   - Actualizar `test_meshes_and_arena.py` y `test_ultrasonic_physics.py` a la geometría y API actual de `build_worlds.py` / `ultrasonic_physics.py`, y ajustar el gap de salida en `build_worlds.py` para cumplir exactamente `0.30 ± 0.005 m`.
   - Etiquetar release final **`v2.0.0`** una vez que pruebas Java, pruebas Python y pruebas de hardware real en pista pasen sin atajos de simulación ni hardcoding sensorial.
