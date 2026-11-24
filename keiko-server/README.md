# Servidor Central (Cerebro) — Proyecto Orquitas (Keiko)

Módulo de procesamiento distribuido, inferencia dual con **Neuroph 2.98**, conectividad exclusiva por **Bluetooth RFCOMM/SPP** (`jSerialComm`), monitor TUI ANSI en vivo, persistencia atómica y concurrencia en **Java 21 LTS** para el ecosistema **Orquitas (`2.0.3` | `mega-3us-2laser`)**.

---

## 1. Arquitectura Técnica y Concurrencia

```text
[Cliente: La Orquita (Arduino Mega 2560 + HC-05 Serial1 @ 9600 bps)]
         |
         | (Bluetooth RFCOMM/SPP exclusivo vía jSerialComm: /dev/rfcomm0 o COMx)
         v
+-----------------------------------------------------------------------------+
|                    SERVIDOR CENTRAL (PC — Bluetooth Only)                   |
|                                                                             |
|  [ReceptorSerie (jSerialComm) -> ManejadorCliente (InputStream/OutputStream)]|
|    - Valida handshake HELLO:<version>,<rig> (major.minor + rig)             |
|    - Lee tramas ASCII '\n' (TELEMETRY, EVENT, HEARTBEAT, DBG, ERROR)        |
|    - Emite HEARTBEAT periódico (200 ms por defecto, Fail-Safe < 500 ms)     |
|                                                                             |
|  [ConsolaTui + EstadoMonitorTui]                                            |
|    - TUI ANSI fluida (ancho/alto dinámico vía stty size, sin dependencias)  |
|    - Tecla única sin Enter en TTY Unix (k/x, 1/2/3, p, b, +/-, [/], r, v, c)|
|                                                                             |
|  [ClasificadorNeuroph — Arquitectura Dual Neuroph 2.98 (3US + 2Laser)]      |
|    - Red 1 (modelo_ultrasonico.nnet): 1 entrada -> {BLOQUEO, ESPACIO_LIBRE, |
|      SALIDA} evaluada en los 3 sectores [IZQ, CEN, DER]                     |
|    - Red 2 (modelo_canicas.nnet): 6 entradas [usL, usC, usR, lasL, lasR,    |
|      angServo] -> {LOBITO_CENTRO, LOBITO_DER, LOBITO_IZQ, NINGUNA}          |
|                                                                             |
|  [GestorEstomago — Hilo Escritor]                                           |
|    - Sección crítica synchronized(bloqueoArchivo) sobre estomago.txt        |
|    - Escritura secuencial con flush() y FileDescriptor.sync() a disco       |
|                                                                             |
|  [AuditorEstomago — Hilo Lector ("La Linareada")]                           |
|    - Sondeo periódico cada <= 25 ms sobre estomago.txt                      |
|    - Activa MODO ESCAPE únicamente al validar 3 capturas físicas en disco   |
|                                                                             |
|  [PlanificadorNavegacion — FSM]                                             |
|    - BUSQUEDA -> APROXIMACION -> CAPTURA -> ESCAPE -> FINALIZACION          |
+-----------------------------------------------------------------------------+
```

---

## 2. Requisitos y Compatibilidad con Apache NetBeans

- **JDK:** Java 21 LTS (OpenJDK / Temurin).
- **Build:** Apache Maven 3.9+.
- **Dependencias principales ([`pom.xml`](pom.xml)):**
  - `com.github.neuroph:neuroph-core:2.98`
  - `com.fazecast:jSerialComm:2.11.0`
  - `org.junit.jupiter:junit-jupiter-*:5.10.2`
- **Apache NetBeans:** **File -> Open Project... -> `keiko-server/`**. Detecta automáticamente `pom.xml`, permite **Run**, **Debug** y **Test** con `ServidorCentral` como clase principal.

---

## 3. Comandos Maven y Suite de Pruebas

### Compilar y ejecutar pruebas automatizadas
```bash
cd keiko-server
mvn clean test
```
Ejecuta las **11 clases de prueba (63 tests JUnit 5)**:
- `ProtocolParserTest` (10 tests): parseo de `TELEMETRY` (7 campos sin pose), `HELLO`, `CMD:START`/`CMD:STOP`, `CMD:PWM:`, `LASER:`, `STATE_CHANGE:`, `PICKER:ON/OFF`, `DBG:estomago=...` y rechazo de tramas corruptas.
- `PlanificadorNavegacionTest` (17 tests): transiciones FSM simbólicas dependientes exclusivamente de la terna neuronal `[IZQ, CEN, DER]`, pivote sin reversa frente a `SALIDA`, pasos de reversa ajustables (`0..15`), anti-bucle, modo `ESCAPE` y modos forzados (`BUSQUEDA`, `ESCAPE`, `CAPTURA`).
- `RealDatasetAndRetrainTest` (4 tests): captura de datasets reales en marcha y en reposo (preservando `999.0` sin eco), aprendizaje fiel de canica real en `~25 cm`, reentrenamiento Neuroph 2.98, historial inmutable (`models/historial/`) y rollback en caliente.
- `ServidorCentralControlTest` (1 test): controles 1-tecla (`k`, `1`, `2`, `3`, `p`, `b`, `+`/`=`, `-`/`_`, `[`/`]`, `r`) y activación de modos forzados.
- `ConsolaTuiTest` (5 tests): renderizado fluido ancho/alto completo, modos detallado (`112+` cols) y compacto (`92+` cols) del monitor ANSI Bluetooth (incluyendo `Vel`/`PWM`, `LSR-EST`, barra visual adaptativa y parseo `stty size`), cálculo de Hz, Fail-Safe `< 500 ms` y reset.
- `ConfiguracionServidorTest` (6 tests): carga de `.env`, argumentos CLI (`--auto-start`, `--no-tui`, `--tui`, `--speed-scale`, `--pwm-min`/`--pwm-max`) y resolución de rutas.
- `HardwareSerialAndCaptureTest` (6 tests): monitoreo pasivo en reposo, escala de velocidad, cooldown anti-rebote de capturas, `EVENT:CAPTURA`, `HELLO`, `DBG:`, `--auto-start` e ignorado de eventos heredados.
- `TwoNetworksInferenceTest` (5 tests): inferencia dual pura con `modelo_ultrasonico.nnet` + `modelo_canicas.nnet`.
- `ConcurrencyEstomagoTest` (2 tests): estrés concurrente entre múltiples hilos escritores y `AuditorEstomago`.
- `Hito2IntegrationTest` (2 tests): integración de persistencia concurrente e inferencia dual Neuroph + FSM.
- `NeurophInferenceTest` (5 tests): compatibilidad y carga de modelos `.nnet`.

### Empaquetar Fat JAR ejecutable
```bash
mvn package -DskipTests=true
```
Genera `target/keiko-server.jar` con `com.orquitas.server.ServidorCentral` como `Main-Class`.

---

## 4. Ejecución, Flags CLI y Configuración (`.env`)

`ConfiguracionServidor` lee variables de entorno, archivo `.env` (ver [`.env.example`](.env.example)) y argumentos CLI (estos últimos tienen precedencia):

| Flag CLI | Variable `.env` | Valor por defecto | Descripción |
| :--- | :--- | :--- | :--- |
| `--serial <dev>` | `SERIAL_PORT` | `/dev/rfcomm0` (Unix) / `COM5` (Win) | Puerto serie Bluetooth SPP/USB (`COM5`, `/dev/rfcomm0`, `/dev/ttyUSB0`, `/dev/ttyACM0`) |
| `--baud <bps>` | `SERIAL_BAUD` | `9600` | Velocidad serie (`9600` para HC-05, `115200` para USB Mega) |
| `--model <ruta>` | `MODEL_FILE_PATH` | `models/modelo_ultrasonico.nnet` | Ruta al modelo `.nnet` primario (`ClasificadorNeuroph` carga también `modelo_canicas.nnet` del mismo directorio) |
| `--estomago <ruta>` | `ESTOMAGO_FILE_PATH` | `estomago.txt` | Ruta al archivo de persistencia física |
| `--reset` | — | `false` | Vacía `estomago.txt` al iniciar una nueva pasada oficial |
| `--list-ports` | — | `false` | Lista puertos serie/Bluetooth detectados por `jSerialComm` y finaliza |
| `--auto-start` | `AUTO_START` | `false` | Envía `CMD:START` automáticamente al recibir `HELLO:` (también disponible `start`/`stop` por consola) |
| `--no-tui` | `NO_TUI` | `false` | Desactiva la TUI ANSI en vivo y usa salida lineal clásica en consola |
| `--tui` | — | `false` | Fuerza la TUI ANSI en vivo incluso si `System.console()` no detecta TTY interactivo |
| `--speed-scale <f>` | `SPEED_SCALE` | `1.0` | `[PRUEBAS]` Multiplicador de velocidad `(vL, vR)` (`0.20..1.20`) antes de emitir `COMMAND:` |
| `--pwm-min <0..255>` | `PWM_MIN` | `105` | `[PRUEBAS]` Piso `pwmZonaMuerta` enviado por `CMD:PWM:` al Arduino Mega |
| `--pwm-max <0..255>` | `PWM_MAX` | `255` | `[PRUEBAS]` Tope máximo `pwmTope` enviado por `CMD:PWM:` al Arduino Mega |
| `--reverse-steps <0..15>` | `REVERSE_STEPS` | `4` | `[PRUEBAS]` Pasos en reversa (`0..15`, a 10 Hz = `0.4 s`) ante `BLOQUEO` en `BUSQUEDA` |
| `--real-dir <dir>` | — | `data/real` | `[ENTRENAMIENTO]` Carpeta destino de `dataset_ultrasonico_real.csv` y `dataset_canicas_real.csv` |
| `--retrain-real` | — | `false` | `[ENTRENAMIENTO]` Reentrena Neuroph 2.98 combinando base + CSV reales, guarda en `models/historial/` y termina |
| `--list-models` | — | `false` | `[ENTRENAMIENTO]` Lista versiones guardadas en `models/historial/index.tsv` con accuracy y SHA-256 |
| `--use-model <id>` | — | `—` | `[ENTRENAMIENTO]` Restaura una versión del historial en `models/` (rollback seguro) |
| `--note <texto>` | — | `—` | `[ENTRENAMIENTO]` Nota de experimento asociada a `--retrain-real` |

### Controles interactivos en TUI (1 tecla sin `Enter` en Linux/macOS; línea con `Enter` como respaldo)
- **Operación:** `[k]` / `start` (`CMD:START`), `[x]` / `stop` (`CMD:STOP`), `[r]` / `reset` (vacía `estomago.txt` y reinicia FSM), `[v]` / `view` (alterna detallado/compacto), `[c]` / `clear` (limpia logs), `[q]` (salida limpia restaurando TTY).
- **`[PRUEBAS / DEBUG]` Modos forzados y banco:**
  - `[1]` / `busqueda`: fuerza `BUSQUEDA` (`STATE_CHANGE:BUSQUEDA`).
  - `[2]` / `escape`: fuerza `ESCAPE` (`STATE_CHANGE:ESCAPE`) para probar algoritmo de salida sin esperar 3 canicas.
  - `[3]` / `captura`: en corrida activa fuerza `CAPTURA` (`STATE_CHANGE:CAPTURA`); en reposo alterna `PICKER:ON`/`PICKER:OFF`.
  - `[p]` / `picker`: alterna `PICKER:ON`/`PICKER:OFF` directo en banco.
  - `[b]` / `sweep` / `hold` / `laser <-45..60>`: alterna `LASER:SWEEP` / `LASER:HOLD` o fija un ángulo específico en reposo.
  - `[+]`/`[=]` y `[-]`/`[_]` (o `speed <20..120>`): ajusta escala de velocidad en pasos de `10%`.
  - `[[]` / `[]]` (o `pwm <min> [max]`): ajusta `pwmZonaMuerta` (`pwmMin`) en pasos de `±5` y emite `CMD:PWM:`.
  - `[>]`/`[.]` y `[<]`/`[,]` (o `rev <0..15>`): ajusta en vivo los pasos de reversa ante `BLOQUEO` (`0` = pivote directo sin retroceder).
- **`[ENTRENAMIENTO]` Captura real y reentrenamiento en caliente (`data/real` + `models/historial/`):**
  - `[u]`: pasa a modo Red 1 (`US-CEN`) y cicla etiqueta objetivo (`BLOQUEO` → `ESPACIO_LIBRE` → `SALIDA`).
  - `[m]`: pasa a modo Red 2 (`CANICAS`) y cicla etiqueta (`LOBITO_IZQ` → `LOBITO_CENTRO` → `LOBITO_DER` → `NINGUNA`).
  - `[g]` / `rec on` / `rec off` / `label <ETIQUETA>`: inicia o pausa la grabación de ráfagas CSV reales (incluyendo lecturas `999.0` sin eco y telemetría estando detenido).
  - `[t]` / `retrain [nota]`: hace backup automático de `.nnet` en `models/historial/`, reentrena Neuroph 2.98 con datos reales + base sintética balanceada y recarga los modelos en caliente.
  - `models` / `use-model <id>`: lista historial y hace rollback en caliente sin reiniciar el servidor.

### Ejemplos de ejecución
```bash
# Listar puertos Bluetooth / serie disponibles
java -jar target/keiko-server.jar --list-ports

# Modo Bluetooth HC-05 por defecto (/dev/rfcomm0 @ 9600 en Linux, COM5 @ 9600 en Windows)
java -jar target/keiko-server.jar --reset

# Especificando puerto Bluetooth o serie explícito y auto-arranque
java -jar target/keiko-server.jar --serial /dev/rfcomm0 --baud 9600 --reset --auto-start
java -jar target/keiko-server.jar --serial COM5 --baud 9600 --reset
```

---

## 5. Persistencia del Estómago ("La Linareada")

1. **Archivo físico único:** `estomago.txt`.
2. **Sin contadores volátiles en memoria:** la transición a `ESCAPE` depende exclusivamente de los registros leídos de `estomago.txt`. Si el servidor se reinicia con 3 capturas ya persistidas, `ServidorCentral.iniciar()` restaura `MODO_ESCAPE` automáticamente.
3. **Sección crítica y sincronización física:** `GestorEstomago` protege toda operación sobre el archivo con `synchronized(bloqueoArchivo)` y ejecuta `bw.flush(); fos.getFD().sync();` en cada escritura.
4. **Cooldown anti-rebote:** `ManejadorCliente` filtra rebotes mecánicos repetidos dentro de `CAPTURE_COOLDOWN_MS` (`1500 ms` por defecto).
5. **Hilo Auditor:** `AuditorEstomago` sondea cada `AUDITOR_POLLING_MS` (`<= 25 ms`) y dispara `onModoEscapeActivado` + `STATE_CHANGE:ESCAPE` al confirmar 3 capturas válidas.

---

## 6. Protocolo de Comunicación (Bluetooth SPP / TCP)

Todas las tramas son líneas ASCII UTF-8 delimitadas por `\n`:

| Mensaje | Dirección | Formato / Significado |
| :--- | :---: | :--- |
| `HELLO` | Cliente → Servidor | `HELLO:<version>,<rig>\n` (ej. `HELLO:2.0.0,mega-3us-2laser`) |
| `TELEMETRY` | Cliente → Servidor | `TELEMETRY:<us_izq>,<us_cen>,<us_der>,<laser_izq>,<laser_der>,<ang_servo>,<ts_ms>\n` |
| `COMMAND` | Servidor → Cliente | `COMMAND:<v_left>,<v_right>,<estado>\n` |
| `EVENT` | Cliente → Servidor | `EVENT:CAPTURA` o `EVENT:CAPTURA_LOBITO_<n>` |
| `STATE_CHANGE` | Servidor → Cliente | `STATE_CHANGE:<estado>\n` (ej. `STATE_CHANGE:ESCAPE`) |
| `CMD:START` / `CMD:STOP` | Servidor → Cliente | Arranque / detención de la corrida en firmware Mega (`--auto-start` o consola `start`/`stop`) |
| `HEARTBEAT` | Bidireccional | `HEARTBEAT\n` (mantiene vivo el enlace por debajo del watchdog de `500 ms`) |

> **Arquitectura pura (`2.0.3`):** `ClasificadorNeuroph` y `PlanificadorNavegacion` operan exclusivamente sobre inferencia **Neuroph 2.98** y FSM simbólica, sin umbrales sensoriales crudos ni soporte de pose de simulación (`SIM-01..13` y `HRD-01..14` resueltos en [`docs/AUDITORIA_CONSOLIDACION.md`](../docs/AUDITORIA_CONSOLIDACION.md)).

---

## 7. Manual Visual de la TUI y Entrenamiento en Pista ("Wiki Operativa")

> **Mantenimiento obligatorio:** cada PR que agregue, quite o modifique paneles, métricas o atajos de `ConsolaTui` debe mantener actualizada esta sección y su captura ASCII de referencia.

### 7.1 Captura Visual de Referencia (`v2.0.3` — Modo Detallado)

```text
    ______ __    ___________               ⣸⣧                     ╭─ SISTEMA & ENLACE ─────────────────────────────╮
    ___  //_/_______(_)__  /_______       ⢠⣿⣿⣧                    │ Ver: 2.0.3 | Rig: mega-3us-2laser              │
    __  ,<  _  _ \_  /__  //_/  __ \     ⢠⣿⣿⣿⣿⡆     ⢀⣀⣄⡀⠰⠴⣶⣶⣤⣤⡀   │ Enlace  : BT /dev/rfcomm0 @ 9600               │
    _  /| | /  __/  / _  ,<  / /_/ /    ⣠⣿⣿⣿⣿⣿⡇ ⢀⣤⣶⣻⣾⣿⣴⣴⣾⣿⣿⣿⣿⣿⣿⡆  │ Cliente : Serie:/dev/rfcomm0@9600 (1)          │
    /_/ |_| \___//_/  /_/|_| \____/    ⣴⣿⣿⣿⣿⣿⣿⣥⣾⠿⢿⣿⣽⣾⣿⣿⣿⣿⣿⣿⣿⠿⢿⡿⣧  │ Hello   : [OK] v2.0.3                          │
                                     ⣠⣾⣿⣿⣿⣿⣿⠟⠉   ⣸⣿⣿⣿⣿⡿⠟⠛⠋⠉⠐⠊⠡⢹⢚  │ Enlace  :  0.0Hz | dt: 45ms | FS:OK            │
                           ⢀⣀⣠⣤⣤⡴⠂⠐⠒⢨⣿⣿⣿⣿⣿⣿⣤⣆⣤⣠⣴⣾⣿⣷⡿⠋⠁     ⠐⣁⠎ ⡘  │ Redes   : US[OK] CAN[OK] Pkts:1                │
                      ⢐⣠⣤⣶⣾⣿⣿⣿⣿⣿⣆⡀⡀⣀⣨⣿⣿⣿⣿⣿⣿⣿⣿⣿⠟⠁           ⡜  ⡐   ├─ CEREBRO (FSM) & MOTORES ──────────────────────┤
                   ⢠⣴⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠛⠁       ⠄ ⠄     ⠂    │ Estado  : [BUSQUEDA]   REP #124                │
               ⢀⣠⣴⡶⠿⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡟⡀              ⡠⠂     │ Vel: 60% | PWM: 105..255 | Rev:4p              │
             ⢀⣵⣿⣿⣅    ⢈⠙⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠖            ⣀⠂       │ Motor L : [██████░░░░░░░░░░] +2.20             │
           ⣠⣾⣿⣿⣿⣿⣿⣿⣿⣶⣦⣌⠁ ⠉⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡏⡞          ⣀⠜⠁        │ Motor R : [██████░░░░░░░░░░] +2.20             │
   ⣀⣀⣤⢤⢤⡴⢶⣾⡿⠿⣛⠩ ⠉⠉⠙⠛⠻⠿⢏⡀   ⠙⠻⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡿⢈⡷        ⣠⣷⣿⡀         ├─ ESTOMAGO (estomago.txt) ──────────────────────┤
⢠⠖⠋⠉ ⢀ ⠂⣌⢇ ⣰⣿⠁         ⠈⠳⣄ ⡀  ⢀⣽⣿⣿⣿⣿⣿⣿⣿⣿⡿⠋⣐⠰⠂    ⡀⣠⣴⣾⣿⣿⣿⡇         │ Capturas: ● ○ ○ (1/3)   ESCAPE: NO             │
⠛⠓⠒⠲⢤⣀⣐⣤⡞⣸⢊⠥⣿             ⠙ ⢀⣤⣿⣿⣿⣿⣿⣿⣿⡿⠟⠋⢄⣀ ⠠⠤⠴⠂⠈⠁⢰⣿⣿⣿⣿⣿⣿⡇         │ #1: CAPTURA_LOBITO_1                           │
        ⢿⠃  ⠸⡄              ⠈⠉⠉⠉⠉⠉⠋⠉             ⠐⣿⣿⣿⣿⣿⣿          │ LSR-EST :  58mm (b: 60 d:1) [ARM]              │
         ⠙⢖⣦⣀⢻⡀                                   ⢻⣿⣿⣿⣿⠃          │ DBG Mega: estomago=58 base=60... | Errs:0      │
           ⠈⠛⠾⠉                                   ⠸⣿⡿⠛⠁           ╰────────────────────────────────────────────────╯
╭─ SENSORES EN VIVO & INFERENCIA NEUROPH 2.98 ─────────────────────────────────────────────────────────────────────╮
│  US-IZQ │  82.5cm │ [█████░░░░░░░░░] │ ESPACIO_LIBRE 96%  │ TERNA: [ESPACIO_LIBRE|SALIDA|ESPACIO_LIBRE]          │
│  US-CEN │ 138.0cm │ [████████░░░░░░] │ SALIDA        94%  │ Ult. Ctrl: REV=4p (0.4s)                             │
│  US-DER │  24.5cm │ [█░░░░░░░░░░░░░] │ ESPACIO_LIBRE 92%  │ Sin errores de cliente                               │
├─ FILA INFERIOR (2x VL53L0X ToF + Servo SG90 -> Red 2 Canicas) ───────────────────────────────────────────────────┤
│  LSR-IZQ:  24.8 cm  [██░░░░░░░░░░░] │ LSR-DER:  25.1 cm  [██░░░░░░░░░░░] │ Red 2: LOBITO_CENTRO (91%)            │
│  SERVO  :  -6.0°  [-45° ───────────●┼───────────────── +60°]   Barrido espejo L/R                                │
├─ CAPTURA REAL & REENTRENAMIENTO NEUROPH (data/real) ─────────────────────────────────────────────────────────────┤
│ [● REC] CANICAS:LOBITO_CENTRO | BLQ:42 LIB:48 SAL:35 | I:28 C:31 D:27 N:44 | Train:US:99.3% CAN:96.8%            │
╰──────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯
╭─ EVENTOS Y LOGS RECIENTES ───────────────────────────────────────────────────────────────────────────────────────╮
│ 13:24:09 [OK] HELLO Serie:/dev/rfcomm0@9600 | ver=2.0.3 rig=mega-3us-2laser (COMPATIBLE)                         │
│ 13:24:10 [CTRL] Enviado REV=4p (0.4s)                                                                            │
╰──────────────────────────────────────────────────────────────────────────────────────────────────────────────────╯
 Teclas: [k]Run [x]Stop [1/2/3]Mod [p]Pck [b]Swp [+/-]Vel [[/]]PWM [</>]Rev [u/m]Etq [g]Rec [t]Trn [r]Rst [v/c/q]
```

*(Si la terminal mide menos de `112` columnas o menos de `32` filas, la TUI cambia automáticamente a `KEIKO TUI (COMPACTA)` para no desbordar márgenes).*

### 7.2 Qué significa cada panel y métrica

| Sección / Campo | Significado |
| :--- | :--- |
| **`Ver` / `Rig` / `Enlace` / `Hello`** | Versión del servidor, hardware esperado (`mega-3us-2laser`), dispositivo serie Bluetooth y estado del handshake `HELLO:<ver>,<rig>` (`[OK]` si coincide `major.minor` y `rig`). |
| **`Enlace Hz` / `dt` / `FS`** | Frecuencia real de recepción de `TELEMETRY:` (~`10.0 Hz`), silencio desde el último paquete (`dt`) y estado del Watchdog Fail-Safe (`FS:OK` < 350 ms, `FS:WARN`, `FS:TIMEOUT` ≥ 500 ms). |
| **`Redes`** | Estado de carga de Red 1 (`US[OK]`) y Red 2 (`CAN[OK]`) + total de tramas `Pkts` recibidas. |
| **`Estado` + `RUN`/`REP` + `#ciclos`** | Estado actual de `PlanificadorNavegacion` (`BUSQUEDA`, `APROXIMACION`, `CAPTURA`, `ESCAPE`, `FINALIZACION`), si la corrida autónoma está en marcha (`RUN`) o detenida (`REP`), y flags `SWP` / `PCK`. |
| **`Vel` / `PWM` / `Rev`** | Multiplicador de velocidad (`20%..120%`), rango `pwmZonaMuerta..pwmTope` enviado por `CMD:PWM:` y pasos de reversa ante `BLOQUEO` (`0..15`, a `10 Hz` `4p = 0.4 s`). |
| **`Motor L` / `Motor R`** | Velocidad diferencial comandada tras aplicar la escala (`+` verde avanza, `-` rojo retrocede, `■` detenido). |
| **`Capturas` / `ESCAPE` / `#1..#2`** | Progreso persistido en `estomago.txt` (`● ● ● (3/3)` activa `ESCAPE: SI` vía `AuditorEstomago`). |
| **`LSR-EST`** | Lectura cruda del sensor láser VL53L0X interno del estómago en mm: `58mm (b: 60 d:1) [ARM]` = lectura actual, línea base calibrada `b`, lecturas consecutivas distintas `d`, y estado del detector (`DES` desarmado en reposo, `CAL` calibrando base, `ARM` armado). |
| **`DBG Mega` + `Errs`** | Última trama de diagnóstico cruda emitida por el Arduino (`DBG:estomago=...`) y contador de errores reportados (`ERROR:...`). Si muestra `PCA9685_NO_RESPONDE` o `PICKER_TOPE_10S`, queda reflejado en `Ult. Err`. |
| **`US-IZQ / US-CEN / US-DER`** | Distancia en cm de los 3 HC-SR04 superiores (`999.0` = sin eco), etiqueta de Red 1 (`BLOQUEO`, `ESPACIO_LIBRE`, `SALIDA`), confianza `%` y **`TERNA`** final fusionada `[IZQ|CEN|DER]`. |
| **`LSR-IZQ / LSR-DER / Red 2 / SERVO`** | Distancia de los 2 VL53L0X inferiores (`TIMEOUT` = `999.0` sin retorno), clase de Red 2 (`LOBITO_*` / `NINGUNA`) y ángulo de espejo (`-45°` converge al centro, `0°` recto, `+60°` abre hacia afuera). |
| **`CAPTURA REAL & REENTRENAMIENTO`** | Estado del grabador (`[● REC]` / `[PAUSA]`), `RED:ETIQUETA` activa, conteos acumulados en `keiko-server/data/real/` (`BLQ`, `LIB`, `SAL` / `I`, `C`, `D`, `N`) y resultado del último entrenamiento `[t]`. |

### 7.3 Guía Paso a Paso para Grabar Datos Reales y Reentrenar Neuroph 2.98

Todas las grabaciones se pueden hacer **con el robot quieto (`REP`)** sin apretar `[k]`:

1. **Verificar sentido físico de servos (una sola vez tras flashear):**
   - Apretá `[b]` (o en consola `laser -45` → `laser 0` → `laser 60`).
   - Mirando desde arriba: en `-45°` ambos láseres deben cruzar hacia el centro/picker; en `0°` mirar paralelos al frente; en `+60°` abrir hacia los costados.
2. **Grabar muestras para Red 1 (`US-CEN` → `data/real/dataset_ultrasonico_real.csv`):**
   - Apretá `[u]` hasta elegir la etiqueta objetivo:
     - **`BLOQUEO`**: poné el frente del robot mirando paredes rectas, paredes en diagonal (`30°–60°`) y cubos `6×6 cm` entre `2` y `25 cm`.
     - **`ESPACIO_LIBRE`**: apuntá al interior libre de la bahía (`30–115 cm`).
     - **`SALIDA`**: apuntá a través de la abertura real de `30 cm` (> `130 cm`, incluyendo casos donde devuelva `999.0` sin eco).
   - Apretá `[g]` para iniciar `[● REC]`, mové suavemente el ángulo durante 3–5 s (~30–50 muestras) y volvé a apretar `[g]` para pausar (`REC:PAUSA` informa cuántas muestras se sumaron). Repetí hasta tener conteos parejos en `BLQ`, `LIB` y `SAL`.
3. **Grabar muestras para Red 2 (`CANICAS` → `data/real/dataset_canicas_real.csv`):**
   - Activá el barrido lento con `[b]` para recorrer `-45°..+60°`.
   - Apretá `[m]` para ciclar **`LOBITO_IZQ`**, **`LOBITO_CENTRO`**, **`LOBITO_DER`** y **`NINGUNA`**.
   - Con **`LOBITO_CENTRO`** / **`IZQ`** / **`DER`**, colocá la canica real de vidrio blanco a distintas distancias (`5–22 cm`) dejando que `[● REC]` capture el patrón real (por ej. caída de `~40 cm` a `~22–28 cm` o `999.0` si un láser pierde eco mientras el otro detecta).
   - Con **`NINGUNA`**, grabá el piso vacío de tu pista, paredes diagonales cercanas y cubos `6×6 cm` para que no genere falsos positivos.
4. **Reentrenar y activar en caliente:**
   - Apretá **`[t]`** (o fuera de TUI ejecutá `java -jar target/keiko-server.jar --retrain-real --note "pista-real"`).
   - `GestorModelosYEntrenamiento` primero guarda una copia inmutable de tus redes previas en `keiko-server/models/historial/`, entrena combinando tus muestras reales con anclas base (omitiendo automáticamente la banda sintética `22–35.5 cm = NINGUNA` cuando hay canicas reales grabadas) y recarga `ClasificadorNeuroph` en vivo.
5. **Validar en TUI y volver atrás si hace falta (Rollback):**
   - Probá pasando la canica delante del robot quieto y mirando `Red 2` y `TERNA`.
   - Si una red nueva empeoró, consultá el historial y restaurá cualquier versión anterior al instante:
     ```bash
     java -jar target/keiko-server.jar --list-models
     java -jar target/keiko-server.jar --use-model <version_id>
     ```
     (O dentro de la consola en vivo: `models` y `use-model <version_id>`).
