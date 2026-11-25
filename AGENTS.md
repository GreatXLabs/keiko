# Contexto Técnico del Proyecto: Orquitas (Keiko)

Especificación técnica de ingeniería, enrutador de documentación y reglas operativas para el ecosistema robótico autónomo distribuido **"Proyecto Orquitas"** (Programación Sobre Redes y Desarrollo de Sistemas, Ciclo 2026).

---

## 0. Flujo de Trabajo en Git (Obligatorio)

- **Prohibido pushear directamente a `main`** — aplica a todos los integrantes y agentes.
- Todo cambio nace en una **rama propia**: `feat/<tema>`, `fix/<tema>`, `docs/<tema>`, `chore/<tema>`.
- Los commits son **atómicos** y siguen **Conventional Commits** (descripciones en inglés).
- La integración a `main` es **exclusivamente vía Pull Request** (merge squash), idealmente con revisión de otro integrante.
- Prohibido `git push --force` sobre `main` o ramas compartidas.
- **Privacidad y Cero PII:** prohibido incluir nombres personales, correos, direcciones IP privadas o rutas locales absolutas del sistema de archivos en código, documentación o mensajes de commit.

---

## 1. Regla Obligatoria de Versionado y Changelog

- **Fuente única de verdad:** el archivo raíz [`VERSION`](VERSION) define la versión activa del ecosistema (actualmente `2.0.9`, rig `mega-3us-2laser`).
- **Sincronización obligatoria entre módulos:** cualquier cambio en código, modelos `.nnet`, protocolo serie/TCP o parámetros de simulación debe mantener sincronizados `VERSION`, `keiko-server/pom.xml`, `ConfiguracionServidor.java`, `orquita_firmware.ino`, `keiko-sim/config/simulation_params.json` y `robot_client.py`.
- **Validación automática:** antes de finalizar cambios o abrir PR, ejecutar:
  ```bash
  ./scripts/check_versions.sh
  ```
  Debe finalizar con `exit 0` (**9/9 `[OK]`**).
- **Preservación de Handshake:** tanto el firmware físico como el cliente de simulación deben emitir `HELLO:<version>,<rig>` al conectar, y `keiko-server` debe validar compatibilidad `major.minor` y `rig`.
- **Actualización de [`CHANGELOG.md`](CHANGELOG.md):** todo cambio funcional en código, modelos `.nnet` o simulación debe agregar o actualizar su entrada correspondiente en `CHANGELOG.md` indicando **qué cambió**, **por qué** y **evidencia exacta de pruebas ejecutadas**.
- **Actualización de la Wiki Visual de TUI ([`keiko-server/README.md`](keiko-server/README.md)):** cualquier cambio que agregue, modifique o retire paneles, métricas o atajos de `ConsolaTui` debe actualizar obligatoriamente la sección **"Manual Visual de la TUI y Entrenamiento en Pista"** en `keiko-server/README.md`.

---

## 2. Enrutador de Documentación y Mapa del Repositorio

Los agentes deben consultar únicamente los archivos vigentes de esta tabla (los materiales legacy de `1.x`, Hito 2/3 y experimentos descartados viven aislados en [`archive/`](archive/README.md)):

| Información Buscada | Archivo / Directorio de Referencia | Contenido Principal |
| :--- | :--- | :--- |
| **Versión y Compatibilidad** | [`VERSION`](VERSION), [`CHANGELOG.md`](CHANGELOG.md), [`scripts/check_versions.sh`](scripts/check_versions.sh) | Versión activa (`2.0.9`), matriz de compatibilidad y verificador cruzado de 9 puntos. |
| **Auditoría de Consolidación y Resolución Fase B** | [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md) | Auditoría histórica Fase A y cierre `v2.0.0` (`SIM-01..13`, `HRD-01..14`, `COMP-01..05` resueltos), hashes `.nnet` y evidencia de pruebas. |
| **Reglamento, Pista y Puntuación** | [`docs/consignas/La Competencia.md`](docs/consignas/La%20Competencia.md) | Geometría de la Bahía (~1.80 m base, ~1.20 m profundidad, ~1.70 m²), salida de 30 cm, 2 obstáculos 3D (6×6 cm, alt. mín. 6 cm), 3 canicas (1.6 cm), etapas y puntuación. |
| **Consigna General e Hitos** | [`docs/consignas/Consigna y Alcances.md`](docs/consignas/Consigna%20y%20Alcances.md) | Alcance integral del proyecto (PSR + DS), metodología SCRUM y criterios globales de evaluación. |
| **Redes, Protocolo y Persistencia** | [`docs/consignas/Reglas Técnicas de Programación Sobre Redes.md`](docs/consignas/Reglas%20Técnicas%20de%20Programación%20Sobre%20Redes.md) | Especificación PSR: Cliente vs. Servidor, tramas Bluetooth RFCOMM/SPP, condición de carrera en `estomago.txt`, hilo auditor y Fail-Safe. |
| **Informes Técnicos e IA** | [`docs/EXPLICACION_SISTEMA_HUMANO.md`](docs/EXPLICACION_SISTEMA_HUMANO.md), [`docs/INFORME_INFERENCIA_RED_NEURONAL_SENSORES.md`](docs/INFORME_INFERENCIA_RED_NEURONAL_SENSORES.md), [`docs/INFORME_NEUROPH_LECTURAS_CAMPO.md`](docs/INFORME_NEUROPH_LECTURAS_CAMPO.md), [`docs/INFORME_EJECUTIVO_LECTURAS_CAMPO_NEUROPH.md`](docs/INFORME_EJECUTIVO_LECTURAS_CAMPO_NEUROPH.md), [`docs/ITERACION_SIMULACION_3US_2LASER.md`](docs/ITERACION_SIMULACION_3US_2LASER.md), [`docs/EXPERIMENTS.md`](docs/EXPERIMENTS.md) | Fundamento físico-acústico, lecturas de campo, iteración `3US + 2Laser` y bitácora de experimentos. |
| **Firmware del Robot (Cliente)** | [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) | Sketch oficial para Arduino Mega 2560 (`mega-3us-2laser`). |
| **Servidor Central (Cerebro)** | [`keiko-server/`](keiko-server/README.md) | Proyecto Java 21 / Maven / NetBeans (`ServidorCentral`, Neuroph 2.98 dual, `ReceptorSerie` Bluetooth-only, `ConsolaTui`, `GestorEstomago`, `AuditorEstomago`). |
| **Simulador 3D (Webots)** | [`keiko-sim/`](keiko-sim/README.md) | Escenas `scen_1..14` y `circular`, `build_worlds.py`, `robot_client.py`, datasets/modelos y scripts `iniciar_simulacion.sh` / `iterar_simulacion.sh`. |
| **Entrenamiento Distribuido** | [`neuroph-train/`](neuroph-train/README.md) | Cliente/Servidor Swing para entrenamiento y validación de redes `.nnet` en Neuroph 2.98 (`neuroph.aguilucho.ar:443`). |
| **Modelos CAD 3D** | `modelos-3d/` | Ensamblajes FreeCAD (`.FCStd`), STL exportados (Git LFS) y planos técnicos. |
| **Pruebas Físicas y Pinout Mega** | [`pruebas/README.md`](pruebas/README.md), [`pruebas/PICKER-ESTOMAGO-Y-ALIMENTACION.md`](pruebas/PICKER-ESTOMAGO-Y-ALIMENTACION.md), [`pruebas/SIMULACION-UNITY.md`](pruebas/SIMULACION-UNITY.md) | Bitácoras de banco, pinout verificado del Mega, calibración de sensores y simulaciones físicas en Python. |
| **Archivo Histórico** | [`archive/README.md`](archive/README.md) | Firmware UNO legacy, sensores coaxiales previos, entregas Hito 2/3 y experimentos descartados. |

---

## 3. Arquitectura Física Oficial del Hardware (`mega-3us-2laser`)

- **Microcontrolador:** Arduino Mega 2560.
- **Tracción 4WD Skid-Steer:** Shield **L293D** (`AFMotor`, bornes `M1..M4`, inversión de sentido configurada en firmware para los 4 motores).
- **Driver PWM / Servos:** **PCA9685** en dirección I2C `0x40` (bus I2C del Mega: `D20 = SDA`, `D21 = SCL`).
- **Fila Sensorial Superior (Obstáculos y Salida):** **3× HC-SR04** fijos (Izquierda, Centro, Derecha): `TRIG {24, 26, 28}`, `ECHO {25, 27, 29}` (validado en robot real el 2026-11-19).
- **Fila Sensorial Inferior (Detección de Canicas):** **2× VL53L0X** frontales (`0x30` con `XSHUT = A8`, `0x31` con `XSHUT = A9`) montados sobre **2× servos SG90** en canales `0` y `1` del PCA9685 realizando barrido en espejo (`LASER_SENTIDO = {1, -1}`, rango `-45°..+60°`).
- **Picker y Sensor de Estómago:** **2× servos SG90 modificados a 360°** en canales `2` y `3` del PCA9685 (`PICKER_NEUTRO = {1485, 1500}`, `PICKER_VEL_US = {360, 300}`, tope de giro `10 s` y corte al capturar) y **1× VL53L0X** interno de estómago (`0x32` con `XSHUT = A10`) para confirmación física de captura.
- **Enlace de Comunicación:** módulo Bluetooth **HC-05** en `Serial1` (`D18`/`D19` a `9600` bps); USB (`Serial`, `115200` bps) libre para diagnóstico.
- **Restricción del Cliente:** el microcontrolador solo muestrea en bucle no bloqueante, actúa sobre motores/servos/picker (arranca en reposo hasta recibir `CMD:START` o `'k'` si `PERMITIR_MANUAL` está activo; por defecto en competencia `#define PERMITIR_MANUAL 0`) y ejecuta el Fail-Safe (`< 500 ms`); no contiene lógica de navegación ni clasificación.

---

## 4. Pipeline Dual de IA (Neuroph 2.98) y Regla Anti-Hardcoding

- **Motor de IA:** exclusivamente **Neuroph 2.98** (`Java 21 LTS`, compatible con Maven y Apache NetBeans).
- **Arquitectura de Dos Redes Neuronales:**
  1. **Red 1 — [`modelo_ultrasonico.nnet`](keiko-server/models/modelo_ultrasonico.nnet):** recibe 1 lectura ultrasónica normalizada y clasifica cada sector `[IZQ, CEN, DER]` en `{BLOQUEO, ESPACIO_LIBRE, SALIDA}`.
  2. **Red 2 — [`modelo_canicas.nnet`](keiko-server/models/modelo_canicas.nnet):** recibe 6 entradas `[us_izq, us_cen, us_der, laser_izq, laser_der, angulo_servo]` y clasifica en `{LOBITO_CENTRO, LOBITO_DER, LOBITO_IZQ, NINGUNA}`.

### Regla Estricta Anti-Hardcoding y Anti-Atajos de Simulación
- **Prohibido hardcodear decisiones de navegación con umbrales crudos:** queda terminantemente prohibido reemplazar, corregir o anular la inferencia de Neuroph 2.98 mediante condicionales sobre distancias crudas en cm o ángulos de servo (`if (us < X)`, filtros de sombra, inyección directa de `"LOBITO"` o `"SALIDA"` por distancia). Toda clasificación perceptual debe emanar de las redes neuronales `.nnet`.
- **Prohibido usar pose de simulación en navegación o inferencia:** queda terminantemente prohibido leer o transmitir `hasPose()`, `posX`, `posY`, `theta` o `EVENT:SALIDA_META` en `keiko-server` o `robot_client.py`. El nodo `Supervisor` de Webots usa la pose únicamente para auditoría local del juez de simulación (colisiones, egreso y logging).
- **Cumplimiento verificado en `2.0.0`:** todos los hallazgos `SIM-01..13`, `HRD-01..14` y `COMP-01..05` fueron erradicados; `ClasificadorNeuroph` y `PlanificadorNavegacion` operan 100% con inferencia Neuroph 2.98 y FSM simbólica (ver cierre en [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md)).

---

## 5. Escenario, Protocolo y Persistencia Crítica

### 5.1 Escenario ("La Bahía de Caza")
- **Geometría:** semielipse contra pared plana (~1.80 m de base, ~1.20 m de profundidad, ~1.70 m² de área), muros de cartón de 5 mm y 15–20 cm de altura, abertura única de salida de **30 cm** sin marcos ni túneles.
- **Elementos:** **2 obstáculos** 3D (base 6×6 cm, altura ≥ 6 cm) y **3 Lobitos** (canicas físicas de 1.6 cm de diámetro).
- **Ciclo biológico:** `BUSQUEDA` → `APROXIMACION` → `CAPTURA` (hasta 3) → `ESCAPE` (al verificar 3 registros en disco) → `FINALIZACION` (o `ERROR_SEGURO` ante timeout).

### 5.2 Protocolo (Bluetooth RFCOMM/SPP exclusivo en `keiko-server`, tramas ASCII terminadas en `\n`)

| Mensaje | Dirección | Formato / Propósito |
| :--- | :---: | :--- |
| `HELLO` | Cliente → Servidor | `HELLO:<version>,<rig>\n` — verificación de versión `major.minor` y rig (`mega-3us-2laser`). |
| `TELEMETRY` | Cliente → Servidor | `TELEMETRY:<us_izq>,<us_cen>,<us_der>,<laser_izq>,<laser_der>,<ang_servo>,<ts_ms>\n` (estrictamente 7 campos idénticos entre hardware real y Webots). |
| `COMMAND` | Servidor → Cliente | `COMMAND:<v_left>,<v_right>,<estado>\n` — velocidades diferenciales y estado FSM activo. |
| `EVENT` | Cliente → Servidor | `EVENT:CAPTURA` / `EVENT:CAPTURA_LOBITO_<n>` — notificación física de captura en estómago. |
| `STATE_CHANGE` | Servidor → Cliente | `STATE_CHANGE:<estado>\n` — conmutación formal de estado (ej. `ESCAPE`). |
| `CMD:START` / `CMD:STOP` | Servidor → Cliente | Arranque o detención de la corrida autónoma en el firmware Mega (`--auto-start` o consola `start`/`stop`). |
| `HEARTBEAT` | Bidireccional | `HEARTBEAT\n` — liveness de enlace (`INTERVALO_HEARTBEAT_MS = 200 ms`). |

- **Fail-Safe Obligatorio (`< 500 ms`):** si transcurren más de **500 ms** sin recibir `COMMAND` ni `HEARTBEAT`, el cliente (Mega y Webots) debe detener de inmediato tracción y picker.

### 5.3 Persistencia y Concurrencia ("El Estómago" / "La Linareada")
- **Archivo físico único:** `estomago.txt`.
- **Prohibición de estado volátil:** prohibido usar variables o contadores en memoria para decidir "Panza Llena"; tras un reinicio del servidor, el estado se reconstruye leyendo `estomago.txt`.
- **Sección crítica y volcado físico:** `GestorEstomago` sincroniza toda lectura/escritura sobre `estomago.txt` (`synchronized(bloqueoArchivo)`) y ejecuta `flush()` + `FileDescriptor.sync()` tras cada registro.
- **Hilo Auditor (`AuditorEstomago`):** sondea `estomago.txt` en segundo plano (`<= 25 ms`) y emite la transición formal a `ESCAPE` únicamente cuando existen **3 capturas físicas válidas** en disco.

---

## 6. Reglamento Operativo y Puntuación

- **Etapas:** Clasificación (7 min), Semifinales, Final y La Recontrafinalísima (10 min).
- **Puntajes:** 1.er Lobito (`+5`), 2.º Lobito (`+10`), 3.er Lobito (`+15`), activación verificada de Modo Escape (`+15`), abandono completo de la Bahía (`+30`), bonificación por tiempo (`+15` en 0:00–1:00, `+10` en 1:01–4:00, `+5` en 4:01–10:00). Penalizaciones: colisión con muro u obstáculo (`-5`), pérdida de Lobito (`-5`).
- **Restricciones en pista:** autonomía absoluta (sin asistencia física ni inyección manual por consola o edición de `estomago.txt`). Inactividad continua **> 10 segundos** finaliza automáticamente la pasada.
