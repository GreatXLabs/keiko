# Informe Técnico: Incidencias Detectadas, Acciones Correctivas y Evidencias de Pruebas y Entrenamiento

**Proyecto:** Orquitas (Keiko) — Programación Sobre Redes y Desarrollo de Sistemas (Ciclo 2026)  
**Entregable:** Hito 3 (Entrega Final) — Historial de incidencias resueltas, pruebas efectuadas y evidencias de entrenamiento (Neuroph 2.98)  
**Versión de cierre documentada:** `2.0.9` | **Rig oficial:** `mega-3us-2laser` | **Fecha de corte:** `2026-11-25`

> [!NOTE]
> Este documento consolida únicamente hechos, métricas, registros de entrenamiento y logs verificados en el repositorio, pruebas de banco físicas, simulaciones en **Webots 3D** y corridas reales sobre la Bahía de Caza hasta la versión **`2.0.9`**.

---

## Parte I — Informe de Incidencias Detectadas y Acciones Correctivas Implementadas

### 1. Resumen Evolutivo por Etapa

| Etapa | Versiones / PRs | Alcance Técnico | Foco Principal de Remediación |
| :--- | :---: | :--- | :--- |
| **1. Línea base en simulación 3D** | `1.x` (`EXP-001..021`) | Webots 3D (`6–8 US` → migración `3 US + 2 Láser`) + Neuroph 2.98 | Cobertura lateral, eliminación de encoders/GPS y reducción de colisiones en simulación. |
| **2. Consolidación y Anti-Hardcoding** | `2.0.0-audit.1/2` → `2.0.0` (`#16–#18`) | Unificación de ramas, auditoría `SIM/HRD/COMP`, handshake versionado | Eliminación total de atajos de pose (`hasPose`, `SALIDA_META`) y reglas sensoriales crudas. |
| **3. Banco físico Arduino Mega 2560** | `2.0.0-audit.2` (`#17`, `pruebas/`) | Hardware real: L293D, PCA9685, 3× HC-SR04, 3× VL53L0X, HC-05, XL4015 | Pinout real, alimentación de servos, I2C/XSHUT, enlace Bluetooth-only y estómago ToF. |
| **4. Operación y calibración en pista** | `2.0.1` → `2.0.9` (`#19–#32`) | TUI ANSI 1-tecla, captura real CSV, reentrenamiento en vivo, navegación física | Sentido real de servos, filtro anti-vacío en dataset, prioridad FSM, detección por señal y paredes diagonales. |

---

### 2. Incidencias de Arquitectura, Simulación Webots y Protocolo (`1.x` → `v2.0.0`)

| ID | Incidencia Detectada | Causa Raíz | Acción Correctiva Implementada | Verificación / Versión |
| :--- | :--- | :--- | :--- | :--- |
| **`INC-01`** | **Desincronización entre servidor, firmware y simulador** | Ramas divergentes (`main`, `feat/hw-field-ready`, `tests-emi`) sin control de versión compartida ni verificación de compatibilidad al conectar. | Archivo único `VERSION`, scripts [`check_versions.sh`](../scripts/check_versions.sh) (9 puntos) y [`bump_version.sh`](../scripts/bump_version.sh), y validación de `HELLO:<version>,<rig>` (`major.minor` + `mega-3us-2laser`) en [`ManejadorCliente.java`](../keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java). | `9/9 [OK]` desde `v2.0.0-audit.1` (`#16`, `#24`). |
| **`INC-02`** | **Atajos de pose en simulación (`SIM-01..13`) y umbrales sensoriales hardcodeados (`HRD-01..14`)** | `robot_client.py` enviaba `(rx, ry, yaw)` y `EVENT:SALIDA_META`; `ClasificadorNeuroph` sobrescribía salidas por ángulo/distancia; `PlanificadorNavegacion` leía distancias crudas en cm. | Erradicados en Fase B (`v2.0.0`): telemetría estricta de 7 campos idéntica entre Webots y Mega; `ClasificadorNeuroph` reducido a `NeuralNetwork.calculate()` + `argMax`; `PlanificadorNavegacion` reescrito como FSM puramente simbólica sobre la terna `[IZQ, CEN, DER]`. | `v2.0.0` (`#18`), cierre en [`AUDITORIA_CONSOLIDACION.md`](AUDITORIA_CONSOLIDACION.md). |
| **`INC-03`** | **Desalineación física de rig y errores en `keiko-sim` (`COMP-01..05`)** | Barrido desfasado entre firmware (`[-45°, +60°]`), Java (`<=50°`) y Webots (`[-15°, +35°]`); gap de salida en `0.310 m`; `TypeError` en `live_visualizer.py:231`; control manual activo en firmware. | Barrido unificado `[-45°, +60°]` en [`simulation_params.json`](../keiko-sim/config/simulation_params.json) y clientes; salida ajustada a `0.300 m` en [`build_worlds.py`](../keiko-sim/scripts/build_worlds.py); corregido `active_obstacles_xy`; `#define PERMITIR_MANUAL 0` por defecto en [`orquita_firmware.ino`](../firmware/orquita_firmware/orquita_firmware.ino). | `33/33` `unittest` en `v2.0.0` (`#18`). |

---

### 3. Incidencias de Hardware, Alimentación y Banco Físico (`Mega 2560`)

| ID | Incidencia Detectada | Causa Raíz | Acción Correctiva Implementada | Verificación / Referencia |
| :--- | :--- | :--- | :--- | :--- |
| **`INC-04`** | **Incompatibilidad de pines al migrar de UNO a Mega 2560 y tracción invertida** | El shield L293D cubre `A0–A5`; en Mega el bus I2C está en `D20/D21`; canales US medio/derecho y los 4 motores DC estaban cableados invertidos. | Bus I2C en `D20/D21`, `XSHUT` en `A8/A9/A10` (`0x30`, `0x31`, `0x32`), HC-SR04 reasignados a `TRIG {24,26,28}` / `ECHO {25,27,29}` y `INVERTIDO = {true,true,true,true}` validados con `prueba-traccion` y `prueba-ultrasonidos-mega`. | Validado en banco (`2026-11-03` / `2026-11-19`) e integrado en `v2.0.0-audit.2` (`#17`). |
| **`INC-05`** | **Bornera del PCA9685 sin tensión en `V+` y reinicios por caída de tensión (brownout)** | La protección de polaridad/soldadura de la bornera del PCA9685 entregaba `0 V` en `V+`; picos de arranque de servos desde USB o caídas de `VCC` dormían el PCA9685 (`bit SLEEP` de `MODE1`). | Alimentación directa desde `OUT+` del step-down XL4015 (`5.0–5.1 V`) al pin `V+` del PCA9685 con capacitor `470–1000 µF`; verificación periódica `revisarPca()` cada `500 ms` y arranque suave en firmware. | Validado en [`PICKER-ESTOMAGO-Y-ALIMENTACION.md`](../pruebas/PICKER-ESTOMAGO-Y-ALIMENTACION.md) y `prueba-todo-junto`. |
| **`INC-06`** | **Pin `STATE` del HC-05 inoperante y soporte de transporte dual residual** | El módulo HC-05 clon mantiene `STATE` (`D30`) fijo en `LOW` con o sin conexión; además persistía soporte TCP en el servidor fuera del perfil oficial Bluetooth. | Fail-Safe `< 500 ms` apoyado exclusivamente en `HEARTBEAT` (`200 ms`) y `COMMAND`; eliminado `ReceptorConexiones`/TCP dejando [`ReceptorSerie.java`](../keiko-server/src/main/java/com/orquitas/server/ReceptorSerie.java) exclusivo RFCOMM/SPP vía `jSerialComm`. | Integrado en `#20` (`v2.0.0`, `2026-11-23`). |
| **`INC-07`** | **Conteo errático del estómago VL53L0X y baja detección en distancia sobre canicas de vidrio** | Contar cruces de distancia generó `7` eventos para `3` canicas por rebotes internos; además, en pista las canicas de vidrio casi no desplazan la distancia en `mm`, pero alteran `30–60%` la señal de retorno. | Primero se implementó detector armado por línea base (`picker-estomago` / `v2.0.0-audit.2`); luego en `v2.0.7` se reemplazó por lectura **no bloqueante** de tasa de retorno del registro `0x14` (`estomagoSenal()`, `MUESTRAS_BASE = 10`, `UMBRAL_SENAL_PCT = 20`, cooldown `3000 ms`) y `PICKER_EXTRA_MS` reducido de `4000` a `2500 ms`. | Captura real `3/3` verificada en pista (`v2.0.6`/`v2.0.7`) y `HardwareSerialAndCaptureTest`. |

---

### 4. Incidencias en Pruebas Reales de Pista, TUI, Red Neuronal y Navegación (`v2.0.1` → `v2.0.9`)

| ID | Incidencia Detectada | Evidencia / Impacto Observado | Acción Correctiva Implementada | PR / Versión |
| :--- | :--- | :--- | :--- | :---: |
| **`INC-08`** | **Signo físico invertido de los servos láser (`SG90`)** | Con el robot real en banco, cuando el servidor indicaba `-45°` ambos láseres apuntaban hacia afuera en lugar de converger al centro, invirtiendo las detecciones de Red 2 (`LOBITO_IZQ/DER` vs `NINGUNA`). | Invertido `LASER_SENTIDO[2] = {-1, 1}` en `orquita_firmware.ino` y agregado comando `laser <grados>` para validar `-45°` (centro), `0°` (frente) y `+60°` (afuera). | `#24` (`v2.0.1`) |
| **`INC-09`** | **Desborde de TX `Serial1` @ `9600` bps y tramas truncadas** | Escribir `DBG:` + `TELEMETRY:` + `HEARTBEAT` en un mismo ciclo saturaba el buffer AVR de `64 B`, causando tramas de 4 o 6 tokens donde el `timestamp` entraba como `angulo_servo`. | Emisión limitada a **1 trama por iteración del `loop()`** validando `availableForWrite()`, más validación estricta de `7` tokens (`parsearTelemetria(linea, 6, true)`) en [`MensajeProtocolo.java`](../keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java). | `#24` (`v2.0.1`) |
| **`INC-10`** | **`LASER:SWEEP` bloqueado en reposo tras una corrida en `ESCAPE`** | Al detener una corrida (`CMD:STOP`), `estado` permanecía en `"ESCAPE"`, bloqueando `atenderBarrido()` en reposo aunque `barridoEnReposo == true`. | `terminarCorrida()` restablece `estado = "BUSQUEDA"`, prioriza `barridoEnReposo` cuando `!corrida` y reporta `ERROR:PCA9685_NO_RESPONDE` si falla I2C. | `#23` (`PR-C`) |
| **`INC-11`** | **Desbordes de márgenes en TUI y archivo de log de sesión vacío** | En terminales de `~108` cols la TUI perdía bordes/teclas; en `v2.0.7`, el archivo `logs/corrida_*.log` quedaba vacío salvo el cierre porque `ConsolaTui` fijaba `setUseParentHandlers(false)` sobre el logger raíz. | Cálculo exacto de filas (`alto - lineas - 4`), `ANCHO_DETALLADO = 106` cols + modo compacto automático, y registro del `FileHandler` directamente sobre el logger `com.orquitas.server`. | `#24` (`v2.0.1`), `#30` (`v2.0.7`), `#31` (`v2.0.8`) |
| **`INC-12`** | **"Canicas fantasma" masivas al reentrenar con barrido guiado (`v2.0.4`)** | En `corrida_pista.log` (`2 130` líneas, snapshot `20261124-184400`), el robot pasó **`95%` (`978/1 028` ciclos)** en `APROXIMACION` persiguiendo aire vacío y disparó `ERROR:PICKER_TOPE_10S`: `~80%` de las `65` muestras de cada paso `LOBITO_*` miraban fondo vacío (`45–73 cm`), y en `2.0.3` la banda negativa sintética `22–36 cm` pisaba las canicas reales de vidrio. | 1) Exclusión de la banda negativa sintética `22–36 cm` cuando hay CSV reales (`GestorModelosYEntrenamiento`). 2) Auto-archivado de CSV previos a `data/real/archivo/<ts>/` al abrir `[w]`. 3) **Filtro anti-vacío exclusivo en captura guiada `LOBITO_*`** conservando solo impactos con caída láser real (`(vacio:N)` en TUI) y ajuste a `23` pasos reales. | `#26` (`v2.0.3`), `#28` (`v2.0.5`) |
| **`INC-13`** | **Prioridad errónea en FSM, interferencia de Red 2 en `ESCAPE` y enganches en `SALIDA`** | En `corrida_pista.log`, un láser contra pared a `2 cm` disparó `LOBITO_IZQ` antes que `BLOQUEO` y embistió el muro; en `ESCAPE`, Red 2 reemplazaba sectores; al bajar velocidad a `40%`, la evasión suave enganchaba el chasis en el cartón de salida. | 1) En `ESCAPE` se omite Red 2 (`clasificarPares(sensores, false)`). 2) En `BUSQUEDA`, **0 pasos de reversa** ante `SALIDA` sin bloqueo. 3) Prioridad estricta **`SALIDA > BLOQUEO > LOBITO`** y limpieza inmediata de persistencia ante bloqueo/salida. 4) Pivote firme en `SALIDA` (`(3.8, -2.2)`), giros asimétricos `{13,17,21}` y exploración cruzando el centro (`/ 22 ± 0.25`). | `#25` (`v2.0.2`), `#29` (`v2.0.6`), `#30` (`v2.0.7`) |
| **`INC-14`** | **Loop de reconexión Bluetooth post-STOP (`9.5 h`) y doble `CMD:START`** | En `corrida_pista_2.log`, tras `CMD:STOP` el puerto fantasma `/dev/rfcomm0` acumuló `1 093` ciclos conectar/cerrar; además `--auto-start` reenviaba `CMD:START` tras arranque manual. | Backoff progresivo (`1500 → 3000 → 5000 ms`) en `ReceptorSerie` cuando el puerto cierra con `0` tramas válidas, `marcarStartManual()` en `ManejadorCliente` y guarda `if (corrida) return;` en firmware. | `#30` (`v2.0.7`) |
| **`INC-15`** | **Choque en pared diagonal (`>45°`), falso `LOBITO` paralelo e inercia sobre `pwmZonaMuerta=105`** | En corridas físicas (`2026-11-25`), los `HC-SR04` paralelos perdían eco contra muros a `>45°`; al quedar paralelo a la pared, el ToF corto + US largo disparaban `LOBITO` en bucle, y el barrido (`~3.15 s`) no completaba lecturas aun al `20%` de velocidad por el escalón de arranque `PWM 105`. | **Hardware:** curvatura física de los `HC-SR04` laterales hacia afuera. **Firmware + Servidor (`v2.0.9`):** barrido acelerado (`BARRIDO_PASO=2` / `28 ms`, `~1.48 s`), marcha pulsada en `BUSQUEDA` (`4` ON / `6` OFF) y centrado suave en el lugar antes de avanzar en `APROXIMACION`, corte + cooldown `2.5 s` tras `26` ciclos sin `LOBITO_CENTRO`, y asistente `[w]` ampliado a **32 poses** con captura por canal `US` (`CEN`/`IZQ`/`DER`). | `#32` (`v2.0.9`) |

---

## Parte II — Pruebas Efectuadas y Evidencias de Entrenamiento

### 1. Pruebas de Banco Hardware (`Arduino Mega 2560`, `pruebas/`)

| Sketch / Prueba | Subsistema Validado | Resultado Observado |
| :--- | :--- | :--- |
| [`prueba-traccion.ino`](../pruebas/prueba-traccion/prueba-traccion.ino) | 4 motores DC en L293D (`M1..M4`) + `Serial`/`Serial1` + deadman `450 ms` | Confirmada inversión física de los 4 motores (`INVERTIDO = {true,true,true,true}`) y respuesta diferencial limpia (`2026-11-03`). |
| [`prueba-ultrasonidos-mega.ino`](../pruebas/prueba-ultrasonidos-mega/prueba-ultrasonidos-mega.ino) | 3× HC-SR04 en `D24..D29` | Detectó y corrigió cruce previo de canales medio/derecho; calibración lineal `US_A = 1.04`, `US_B = {0.25, 0.77, 0.57}` (`2026-11-19`). |
| [`prueba-laser-mega.ino`](../pruebas/prueba-laser-mega/prueba-laser-mega.ino) + [`prueba-i2c.ino`](../pruebas/prueba-i2c/prueba-i2c.ino) | Bus I2C `D20/D21`, PCA9685 (`0x40`) y 3× VL53L0X (`A8/A9/A10` → `0x30..0x32`) | Lectura simultánea estable de los 3 ToF sin colisión de direcciones; calibración `LASER_B_CM = {-2.5, -4.0}`. |
| [`prueba-servos.ino`](../pruebas/prueba-servos/prueba-servos.ino) + [`prueba-servo-360.ino`](../pruebas/prueba-servo-360/prueba-servo-360.ino) | 2× SG90 barrido (`0,1`) y 2× SG90 360° picker (`2,3`) | Neutro medido `{1485, 1500} µs` (`2026-11-19`), `VEL_US = {360, 300}` y sentido espejo `{-1, 1}` en `[-45°, +60°]`. |
| [`prueba-hc05.ino`](../pruebas/prueba-hc05/prueba-hc05.ino) + [`hc05-at.ino`](../pruebas/hc05-at/hc05-at.ino) | Enlace Bluetooth HC-05 (`Serial1` @ `9600` bps) | Módulo configurado (`keiko-greatx`, `ROLE:0`, `9600,0,0`) el `2026-11-04`; `PING` bidireccional operativo; `STATE` inoperante confirmado. |
| [`picker-estomago.ino`](../pruebas/picker-estomago/picker-estomago.ino) + [`prueba-todo-junto.ino`](../pruebas/prueba-todo-junto/prueba-todo-junto.ino) | Picker + estómago VL53L0X + estrés conjunto (brownout/EEPROM) | Estómago vacío `53–60 mm`, con canica `30–40 mm` (luego migrado a tasa de señal `0x14` para vidrio); validada estabilidad con XL4015 en `V+`. |

---

### 2. Pruebas en Simulación 3D y Benchmark HIL (Webots)

> La simulación física se ejecutó exclusivamente en **Webots 3D** (el archivo histórico `SIMULACION-UNITY.md` contiene mediciones de chasis y fórmulas físicas usadas como referencia geométrica; no se utilizó el motor Unity).

1. **Etapa `1.x` (`docs/EXPERIMENTS.md`, `EXP-001..021`):** validación evolutiva de arquitecturas `4/6/8/10` sensores (`orquitas_production_champion.nnet` `6→24→5`), migración a rig definitivo `3 US + 2 Láser` con boca libre de `7.0 cm` (`SCEN-1` en `45.6 s`, `0` colisiones, `+90 pts`; `SCEN-6` en `106.2 s`, `1` colisión, `+80 pts` bajo la arquitectura previa con asistencia de simulación).
2. **Etapa `v2.0.0` — Benchmark HIL Webots Headless 100% Neuroph 2.98 puro (`scen_1..8`, `0` atajos de pose):**
   - **Pre-Fase B → Post-reentrenamiento (`964d654`):** reducción de colisiones en `~52%` (`165 → 79` en `180 s`; `123 → 44` en `90 s`), con `scen_4` logrando `3/3` capturas + `ESCAPE` verificado en `169.2 s` en la corrida `v3-cb`.
   - **Tras breakout simbólico de perímetro (`4e51a59`, `benchmark_hil.py --scenarios 1-8 --port 8888`):** **`7/24` capturas** (`+133%` vs `3/24`) y **`58` colisiones** (`-26.6%` vs `79`), alcanzando `5/8` escenarios en estado `PARTIAL` (`SCEN-1` `2/3`, `SCEN-4` `1/3`, `SCEN-5` `2/3`, `SCEN-7` `1/3`, `SCEN-8` `1/3`).

---

### 3. Evidencias de Entrenamiento Neuroph 2.98 (`.nnet` Sintéticos y Reales)

#### 3.1 Evolución Verificada de Modelos (`modelo_ultrasonico.nnet` `1→8→3` y `modelo_canicas.nnet` `6→32→16→4`)

| Snapshot / Versión | Origen del Dataset | `Red 1 (US)` Acc. | `Red 2 (Canicas)` Acc. | Muestras Reales (`US / Can`) | SHA-256 cortos (`US` / `Can`) | Observación Técnica |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **`v2.0.0`** (`2026-11-23`) | Sintético Webots (`6 000` / `8 000` filas) | **`99.17%`** (F1 `0.9917`, RMSE `0.05593`) | **`97.29%`** (F1 `0.9730`, RMSE `0.09970`) | `0 / 0` | `9e932230e84a` / `7fb3ed7d2c46` | Línea base pura Neuroph 2.98 (`SALIDA > 185 cm`). |
| **`v2.0.3`** (`2026-11-24`) | Sintético ajustado (`SALIDA > 130 cm`) | **`99.28%`** (F1 `0.9928`) | `97.29%` (base) | `0 / 0` | `44b112eaa1a7` / `7fb3ed7d2c46` | Umbral `SALIDA` bajado para detección dentro de la Bahía. |
| **`20261124-184400`** (`v2.0.4`) | Sintético + captura real sin filtro | `91.46%` | `86.83%` | `293 / 845` | `a4c90abfcb01` / `dcff42a9df1e` | **Degradado:** `~80%` de muestras `LOBITO_*` contenían fondo vacío (`INC-12`). |
| **`20261124-214931`** (`v2.0.4`) | Reentreno con mismo CSV | `91.46%` | `87.84%` | `293 / 845` | `dbea6f2a43f0` / `171f789a6688` | Misma fuga de vacío en `dataset_canicas_real.csv`. |
| **`20261124-232651`** (`v2.0.5`) | Sintético + captura real **filtrada (`23` pasos)** | **`98.06%`** | **`95.90%`** | `200 / 657` | `8ffa7b0de06e` / `eb6b2670b13f` | **Salto real:** `0` falsos positivos en pista vacía, paredes rectas/diagonales y cubos `6×6`. |

#### 3.2 Composición del Dataset Real Validado (`20261124-232651`)

- **`dataset_ultrasonico_real.csv` (`200` filas):** `BLOQUEO = 100` (`50.0%`), `ESPACIO_LIBRE = 75` (`37.5%`), `SALIDA = 25` (`12.5%`).
- **`dataset_canicas_real.csv` (`657` filas):** `NINGUNA = 585` (`89.0%`), `LOBITO_CENTRO = 24` (`3.65%`), `LOBITO_IZQ = 24` (`3.65%`), `LOBITO_DER = 24` (`3.65%`).
- **Validación directa sobre el `.nnet` `20261124-232651` contra `ClasificadorNeuroph`:**
  - Pista vacía (`80 cm` / `45 cm` en `-35°`, `0°`, `+35°`): **`NINGUNA` (`98–100%`)**.
  - Pared recta, paralela `IZQ/DER`, diagonal `IZQ/DER`, esquina y cubo `6×6 cm`: **`NINGUNA` (`100%`)**.
  - Muestras de `LOBITO_IZQ` y `LOBITO_DER`: **`100%`** acierto; en `LOBITO_CENTRO` una muestra activó `LOBITO_IZQ (82%)` por asimetría física puntual del láser derecho (`43..999.0 cm`).

---

### 4. Pruebas de Campo en Pista Física (Corridas Reales)

| Corrida / Fecha | Versión / Modelo | Métrica del Log / Observación en Pista | Resultado y Conclusión |
| :--- | :---: | :--- | :--- |
| **`corrida_pista.log`** (`2026-11-24`) | `v2.0.4` / `20261124-184400` | `2 130` líneas (`1 028` ciclos `[NAV]`): `978` en `APROXIMACION` (`95%`), `50` en `BUSQUEDA`, `920` ternas con `LOBITO` (`89.5%`), `ERROR:PICKER_TOPE_10S` en vacío. | **`0/3` capturas.** Demostró el bug de contaminación por fondo vacío en el wizard `v2.0.4` (`INC-12`). |
| **`corrida_pista_1.log`** (`2026-11-24`) | `v2.0.6` / `20261124-232651` | `3 471` líneas (`1 696` `[NAV]`): `1 610` `BUSQUEDA`, `86` `APROXIMACION`, `1 230` `ESPACIO_LIBRE x3`, `175` `BLOQUEO x3`, `~53` `LOBITO`. Velocidad bajada manualmente `90% → 40%`. | **`1/3` capturas** a los `45 s` (usando 2 canicas blancas de leche + 1 común fuera de entrenamiento); detenida a mano a los `~115 s`. En corrida paralela de campo sin log adjunto, el equipo reportó captura completa del picker, entrada a `ESCAPE` y salida de la Bahía. |
| **`corrida_pista_2.log`** (`2026-11-24`–`25`) | `v2.0.6` / `20261124-232651` | `13 711` líneas (`4 608` `[NAV]`): `3 666` `BUSQUEDA`, `389` `APROXIMACION`, `553` `ESCAPE` (`~57 s`). `5` avisos `ERROR:PICKER_TOPE_10S` y `5` tramas truncadas; loop BT posterior (`1 093` reconexiones). | **`3/3` capturas reales confirmadas** (`t+26 s`, `t+6 min 10 s`, `t+6 min 44 s`), persistencia en `estomago.txt`, **mutación a `ESCAPE` verificada por `AuditorEstomago`**; no egresó en `57 s` al `40%` (`INC-13`, `INC-14`). |
| **Pruebas en pista (`2026-11-25`)** | `v2.0.7` / `v2.0.8` | Validado `logs/corrida_*.log` con TUI activa (`v2.0.8`); en pista se identificó choque diagonal `>45°`, falso `LOBITO` paralelo e inercia sobre `pwmZonaMuerta=105`. | Motivó curvatura física de `HC-SR04` laterales + refactorización `v2.0.9` (`#32`). |

---

### 5. Verificación Automatizada Vigente (`v2.0.9`, ejecutada `2026-11-25`)

| Verificación | Comando Ejecutado | Resultado Exacto Observado | Alcance |
| :--- | :--- | :--- | :--- |
| **Sincronización de versión y rig** | `./scripts/check_versions.sh` | **`9/9 [OK]`** (`exit 0`, `2.0.9` / `mega-3us-2laser`) | `VERSION`, `pom.xml`, `ConfiguracionServidor.java`, `orquita_firmware.ino`, `simulation_params.json`, `robot_client.py`. |
| **Suite Java (`keiko-server`)** | `cd keiko-server && mvn test` | **`68/68 OK`** (`Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`) | 11 clases JUnit 5: `ProtocolParserTest` (`10`), `PlanificadorNavegacionTest` (`19`), `RealDatasetAndRetrainTest` (`4`), `ServidorCentralControlTest` (`2`), `ConsolaTuiTest` (`6`), `ConfiguracionServidorTest` (`6`), `HardwareSerialAndCaptureTest` (`7`), `TwoNetworksInferenceTest` (`5`), `ConcurrencyEstomagoTest` (`2`), `Hito2IntegrationTest` (`2`), `NeurophInferenceTest` (`5`). |
| **Suite Python / Webots (`keiko-sim`)** | `python3 -m unittest discover -s keiko-sim/tests -q` | **`33/33 OK`** (`7.847 s`) | Incluye `TestWebotsHeadlessLoad`, física ultrasónica, protocolo `7` campos y colisiones. |
| **Firmware `2.0.9` + Pista física** | Pendiente de ejecución en pista | *Implementado en `main` (`fd54dd0`)* | Resta flashear `orquita_firmware.ino` `2.0.9` en Mega, completar wizard `[w]` de `32` poses con los `HC-SR04` ya curvados, reentrenar `[t]` y registrar la corrida completa en `logs/corrida_<timestamp>.log`. |
