# Diseño de Reorganización, Versionado Sincronizado y Auditoría del Repositorio Keiko

**Fecha:** 2026-11-21
**Rama de trabajo:** `chore/repo-reorg-and-audit` (creada desde `main`)
**Estado:** Pendiente de revisión de especificación

---

## 1. Contexto y Diagnóstico del Problema

Al inspeccionar el repositorio remoto (`origin`) y el árbol local se identificaron cuatro causas raíz que impiden saber qué versión funciona en el robot real y en la simulación:

1. **Ramas divergentes y desfasadas respecto a `main`:**
   - `main` (`25e2600`) contiene los modelos 3D actualizados (`#15`) pero conserva en `firmware/orquita_firmware/orquita_firmware.ino` el sketch antiguo de **Arduino UNO + L298N + 4 HC-SR04** y en `keiko-server/` el clasificador coaxial antiguo.
   - `origin/feat/hw-field-ready` (`365b998`) migró firmware y servidor al hardware real (**Arduino Mega 2560 + L293D + PCA9685 + 3 HC-SR04 + 2 VL53L0X frontales + 1 VL53L0X en estómago**) y agregó transporte serie/Bluetooth (`ReceptorSerie.java`, `bt_rfcomm_bridge.py`, `jSerialComm`).
   - `origin/tests-emi` (`83f86bf`, 20 commits sobre `cf68cb2`) evolucionó el firmware Mega (pines `{24,27,29}` / `{25,26,28}`, `999.0f` fuera de rango, `PWM_DEADBAND = 105`, motor DC auxiliar del picker), incorporó mejoras de empaquetado/concurrencia en `keiko-server`, nuevos escenarios Webots (`scen_1..14`, `circular`), datasets v2 y redes `.nnet`, pero **no** incluye `ReceptorSerie.java` ni `bahia-keiko.stl`, y dejó scripts sueltos en la raíz (`iniciar_simulacion.sh`, `iterar_simulacion.sh`).
   - `origin/feat/sim-3us-2laser` (`c65b9f9`) y `exp/sim-3us-2laser` quedaron como ramas sueltas ya mergeadas en `#14`.
2. **Ausencia de versionado unificado:** no existe número de versión compartido ni handshake de compatibilidad entre Servidor Central, Firmware y Simulador/Modelos `.nnet`.
3. **Código generado por IA sin auditar:** `PlanificadorNavegacion.java` (1.527 líneas en `tests-emi`) y `ClasificadorNeuroph.java` incorporaron umbrales fijos sobre distancias crudas e incluso lógica dependiente de pose de simulación (`hasPose()`, `posY`, `theta`), violando la regla de que las decisiones sensoriales pasen por la red neuronal Neuroph 2.98.
4. **Desorden documental y de experimentos:** `pruebas/` mezcla sketches activos del hardware Mega con experimentos descartados (6-US, lidar, visión), `HITO3/` convive sin trackear con copias viejas de `.java`, `docs/uml/` está obsoleto frente a draw.io externo, y `AGENTS.md` + `README.md` mantienen especificaciones contradictorias de Arduino UNO, arenas de `2.4x2.4 m` y clasificadores de 4/5 clases.

---

## 2. Estrategia de Fases, Git e Historial

### 2.1 Preservación total del historial
Antes de modificar o eliminar ramas remotas, se crean tags de archivo inmutables apuntando a cada punta histórica:
- `archive/main-pre-reorg` -> `25e2600`
- `archive/feat-hw-field-ready` -> `365b998`
- `archive/tests-emi` -> `83f86bf`
- `archive/feat-sim-3us-2laser` -> `c65b9f9`

Una vez mergeada la Fase A vía Pull Request a `main`, se eliminan las ramas sueltas (`feat/sim-3us-2laser`, `exp/sim-3us-2laser`, `feat/hw-field-ready`, `tests-emi`).

### 2.2 Trabajo en dos fases
1. **Fase A — Consolidación, Reorganización y Auditoría (`v2.0.0-audit.1`):**
   - Unifica `main`, `origin/tests-emi` y `origin/feat/hw-field-ready` en la rama `chore/repo-reorg-and-audit`.
   - Implementa el esquema de versionado sincronizado, `CHANGELOG.md` y el handshake `HELLO`.
   - Reorganiza `pruebas/`, `archive/`, `docs/` y limpia artefactos residuales.
   - Reescribe `AGENTS.md` y alinea los `README.md`.
   - Emite el informe formal `docs/AUDITORIA_CONSOLIDACION.md` detallando cada regla hardcodeada, atajo de simulación (`hasPose`) y estado real de los tests.
2. **Fase B — Depuración de Hardcodeos y Validación Final (`v2.0.0`):** *(iteración posterior tras revisar la auditoría)*
   - Elimina de `PlanificadorNavegacion` y `ClasificadorNeuroph` los umbrales crudos y dependencias de `hasPose()` listados en la auditoría.
   - Re-entrena o ajusta modelos `.nnet` si los tests de simulación/campo detectan huecos de cobertura.
   - Valida en simulador y robot físico y libera el tag `v2.0.0`.

---

## 3. Versionado Sincronizado y Handshake (`VERSION` + `HELLO`)

### 3.1 Fuente única de verdad
- Archivo raíz `VERSION` con formato SemVer: `2.0.0-audit.1` en Fase A (y `2.0.0` en Fase B).
- Identificador de arquitectura física/sensorial: `mega-3us-2laser`.

### 3.2 Propagación en los 3 subsistemas
1. **`keiko-server/`:**
   - `<version>` en `keiko-server/pom.xml` sincronizado con `VERSION` (ej. `2.0.0-audit.1`).
   - Constantes `VERSION_SISTEMA` y `RIG_HARDWARE` en `ConfiguracionServidor.java`, impresas en el log de arranque de `ServidorCentral.java`.
2. **`firmware/orquita_firmware/orquita_firmware.ino`:**
   - `#define KEIKO_VERSION "2.0.0-audit.1"`
   - `#define KEIKO_RIG "mega-3us-2laser"`
3. **`keiko-sim/`:**
   - Campo `"version": "2.0.0-audit.1"` y `"rig": "mega-3us-2laser"` en `keiko-sim/config/simulation_params.json`.
   - Leído (con fallback explícito a la constante sincronizada) por `keiko-sim/controllers/robot_client/robot_client.py`.

### 3.3 Handshake de protocolo `HELLO`
- Nuevo prefijo en `MensajeProtocolo.java`: `HELLO:<version>,<rig>` (opcional para mantener compatibilidad con clientes legacy durante la auditoría).
- Tanto `orquita_firmware.ino` (al inicializar o recibir conexión/primer ciclo) como `robot_client.py` (inmediatamente al abrir el socket TCP) envían `HELLO:<version>,<rig>\n`.
- `ManejadorCliente.java` y `ReceptorSerie.java` parsean `HELLO:` mediante `MensajeProtocolo.parsearHello(String)`:
  - Registran en log versión y rig del cliente conectado.
  - Comparan `MAJOR.MINOR` contra `ConfiguracionServidor.VERSION_SISTEMA` y `RIG_HARDWARE`.
  - Si no coinciden, registran una alerta clara `[VERSION MISMATCH]` indicando versión esperada vs recibida (sin abortar la conexión en Fase A; en Fase B puede endurecerse si se requiere).

### 3.4 Verificación automática y `CHANGELOG.md`
- Script ejecutable `scripts/check_versions.sh` que lee `VERSION` y valida coincidencia exacta en `keiko-server/pom.xml`, `ConfiguracionServidor.java`, `orquita_firmware.ino`, `simulation_params.json` y `robot_client.py`. Falla con código distinto de 0 ante cualquier desfasaje.
- `CHANGELOG.md` en la raíz con historial cronológico por versión, registrando: cambios por módulo (`Server`, `Firmware`, `Sim/IA`, `Docs/Repo`), matriz de compatibilidad (`Server <-> Firmware <-> Sim <-> Modelos .nnet`), motivo del cambio y resumen de pruebas ejecutadas.

---

## 4. Unificación Técnica de Código (Fase A)

### 4.1 `firmware/`
- **`firmware/orquita_firmware/orquita_firmware.ino`:** se toma la versión más reciente verificada físicamente de `origin/tests-emi` (`7814e0b`: Mega 2560, shield L293D, PCA9685, pines US `{24,27,29}`/`{25,26,28}`, `TOF_SIN_OBJETO_CM = 999.0f`, `PWM_DEADBAND = 105`, motor DC auxiliar L298N, barrido `-45..60°`, estómago ToF `0x32`, HC-05 en `Serial1`) y se agrega la emisión del mensaje `HELLO:<KEIKO_VERSION>,<KEIKO_RIG>`.
- El firmware antiguo de Arduino UNO + L298N + 4 HC-SR04 actualmente en `main` se preserva en `archive/firmware-uno/orquita_firmware_uno.ino`.

### 4.2 `keiko-server/`
- Se toma la base funcional de `origin/tests-emi` (`83f86bf`: rango hasta 1000 cm en `VectorSensores.java`, fix de copia defensiva en `ReceptorConexiones.java`, `ClasificadorNeuroph.java` dual, `PlanificadorNavegacion.java`, `TwoNetworksInferenceTest.java`, configuración de empaquetado en `pom.xml`).
- Se integran desde `origin/feat/hw-field-ready` (`989f785`):
  - `ReceptorSerie.java` y dependencia `com.fazecast:jSerialComm` en `pom.xml`.
  - Soporte CLI/env `--serial`, `--baud`, `--list-ports` en `ConfiguracionServidor.java` y `ServidorCentral.java`.
  - `keiko-server/scripts/bt_rfcomm_bridge.py`, `keiko-server/.env.example` actualizado y `HardwareSerialAndCaptureTest.java`.
- Se unifican los modelos `.nnet` embebidos en `keiko-server/src/main/resources/models/` y `keiko-server/models/` tomando los últimos artefactos de `tests-emi` (`keiko-sim/data/models/modelo_ultrasonico.nnet` y `modelo_canicas.nnet`), dejando registrada en `docs/AUDITORIA_CONSOLIDACION.md` la diferencia detectada con los binarios de `feat/hw-field-ready`.
- Se elimina la carpeta residual local `keiko-server/keiko-server/`.

### 4.3 `keiko-sim/` y `modelos-3d/`
- Se incorporan desde `origin/tests-emi`: mundos `scen_1..14`, `circular`, `archived/`, `meshes/bahia_profesor.stl`, `ultrasonic_physics.py`, `laser_physics.py`, `dataset_recorder.py`, `robot_client.py`, `build_worlds.py`, `generate_datasets.py`, `consolidar_dataset_v2.py`, `entrenamiento_continuo.py`, datasets v2 (`backup_v1_pre_simulaciones/` + consolidados) y modelos `.nnet`.
- Los scripts `iniciar_simulacion.sh` e `iterar_simulacion.sh` se ubican dentro de `keiko-sim/scripts/` (ajustando sus rutas relativas al root del repo) para mantener limpia la raíz.
- Se conservan intactos `modelos-3d/Assembly_Main.FCStd` y `modelos-3d/Piezas/bahia-keiko.stl` de `main` (`#15`).

---

## 5. Estructura de Carpetas, `archive/` y Limpieza

```text
keiko/
├── VERSION
├── CHANGELOG.md
├── AGENTS.md
├── README.md
├── scripts/
│   └── check_versions.sh
├── firmware/
│   └── orquita_firmware/orquita_firmware.ino
├── keiko-server/
├── keiko-sim/
│   └── scripts/{iniciar_simulacion.sh, iterar_simulacion.sh, ...}
├── neuroph-train/
├── modelos-3d/
├── pruebas/
│   ├── README.md                  <- Índice de tests activos de Mega y simulaciones analíticas
│   ├── detector-mixto/
│   ├── prueba-hc05/, hc05-at/
│   ├── prueba-i2c/, prueba-xshut/, prueba-vl53l0x/
│   ├── prueba-servos/, prueba-servo-360/
│   ├── prueba-traccion/, prueba-picker/
│   ├── picker-girando/, picker-estomago/, picker-destrabe/
│   ├── simulacion/
│   ├── fotos_auto/, fotos_pista/
│   ├── PICKER-ESTOMAGO-Y-ALIMENTACION.md, SIMULACION-UNITY.md
│   └── bitacora-01-*.docx, bitacora-02-*.docx
├── docs/
│   ├── AUDITORIA_CONSOLIDACION.md
│   ├── ITERACION_SIMULACION_3US_2LASER.md
│   ├── consignas/
│   ├── evidencias/
│   ├── superpowers/{specs,plans}/
│   └── ... (informes activos)
└── archive/
    ├── README.md                  <- Catálogo de todo lo archivado y por qué se archivó
    ├── hitos/                     <- HITO3/ completo (incl. codigo/, DCU, drawio) + docs Hito 2
    ├── firmware-uno/              <- Sketch Arduino UNO/L298N + circuito_electronico.md viejo
    ├── sensores-legacy/           <- configuracion_sensores_ia.md y ESTUDIO_SENSORES_CONFIGURACIONES.md
    └── pruebas-experimentales/    <- detector-6-ultrasonidos/, lidar_scanner/, vision/, marble_test/, prueba-trabado-vcc/
```

**Eliminaciones explícitas:**
- `docs/uml/` (continuado externamente en draw.io; versión desactualizada).
- Artefactos locales residuales: `TestPlanner.class`, `TestQuery.class`, `keiko-server/keiko-server/`, `client_test*.log`, `server_test*.log` en raíz.

---

## 6. Informe de Auditoría Formal (`docs/AUDITORIA_CONSOLIDACION.md`)

El informe cubrirá de manera verificable:
1. **Origen e integración de ramas:** tabla de commits y conflictos resueltos entre `main`, `feat/hw-field-ready` y `tests-emi`, incluyendo diferencias de modelos `.nnet`.
2. **Auditoría anti-hardcodeo y anti-atajos de simulación:**
   - Inventario por archivo, símbolo y número de línea de toda lógica en `PlanificadorNavegacion.java`, `ClasificadorNeuroph.java` y `robot_client.py` que tome decisiones con umbrales de distancia crudos (`getUs*`, `getLaser*`, `angServo`) o con pose de simulación (`hasPose()`, `posX`, `posY`, `theta`).
   - Clasificación de cada hallazgo: `[PROHIBIDO - DECISIÓN SENSORIAL HARDCODEADA]`, `[PROHIBIDO - ATAJO SIMULACIÓN]`, `[PERMITIDO - MEMORIA FSM/SEGURIDAD ENLACE]` (por ejemplo Watchdog `< 500 ms` o contadores internos de ciclos sin umbrales sensoriales).
3. **Resultados de pruebas en `v2.0.0-audit.1`:** salida real de `mvn clean test` en `keiko-server`, tests Python en `keiko-sim` y `./scripts/check_versions.sh`, indicando qué pasa, qué falla y el plan concreto para Fase B (`v2.0.0`).

---

## 7. Reescritura de `AGENTS.md` y Alineación de `README.md`

### 7.1 `AGENTS.md`
Se reescribirá desde cero como documento vivo, conciso y sin contradicciones:
- **Reglas Git obligatorias:** flujo por ramas + PR a `main`, commits atómicos Conventional Commits, prohibido force-push.
- **Regla de Versionado y Changelog:** todo cambio funcional en código, modelos `.nnet` o simulación debe mantener sincronizado `VERSION` (`./scripts/check_versions.sh`) y registrar qué cambió, por qué y qué se probó en `CHANGELOG.md`.
- **Arquitectura Física Oficial Actual:** Arduino Mega 2560 + shield L293D + PCA9685 + 3 HC-SR04 (superiores) + 2 VL53L0X en servos SG90 (inferiores) + 1 VL53L0X en estómago + picker + HC-05 (`Serial1`).
- **Pipeline IA y Regla Anti-Hardcodeo (Crítica):** red dual Neuroph 2.98 (`modelo_ultrasonico.nnet` + `modelo_canicas.nnet`); prohibido decidir navegación por umbrales crudos de sensores o usar `hasPose()`/`posX`/`posY`/`theta` fuera de logging/simulador.
- **Protocolo y Persistencia:** mensajes `HELLO`, `TELEMETRY`, `COMMAND`, `EVENT`, `STATE_CHANGE`, `HEARTBEAT`; `estomago.txt` con `ReentrantReadWriteLock` + `FileDescriptor.sync()` + Hilo Auditor; Fail-Safe `< 500 ms`.
- **Enrutador actualizado del repositorio:** incluyendo `modelos-3d/`, `pruebas/`, `archive/` y eliminando referencias a `docs/uml/` y entregas Hito 1-3.

### 7.2 `README.md` raíz y de subproyectos
- Actualizar `README.md`, `keiko-server/README.md` y `keiko-sim/README.md` para eliminar referencias a Arduino UNO/L298N, arena `2.4x2.4 m` y clasificadores de 4/5 clases, documentando los nuevos scripts en `keiko-sim/scripts/` y el flujo de versiones.
