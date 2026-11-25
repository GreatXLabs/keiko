# Servidor Central (Cerebro) — Proyecto Orquitas (Keiko)

Módulo de procesamiento distribuido, inferencia dual con **Neuroph 2.98**, conectividad exclusiva por **Bluetooth RFCOMM/SPP** (`jSerialComm`), monitor TUI ANSI en vivo, persistencia atómica y concurrencia en **Java 21 LTS** para el ecosistema **Orquitas (`2.0.8` | `mega-3us-2laser`)**.

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
Ejecuta las **9 clases de prueba (58 tests JUnit 5)**:
- `ProtocolParserTest` (10 tests): parseo de `TELEMETRY` (7 campos sin pose), `HELLO`, `CMD:START`/`CMD:STOP`, `STATE_CHANGE:`, `HEARTBEAT` y rechazo de tramas corruptas.
- `PlanificadorNavegacionTest` (19 tests): transiciones FSM simbólicas (`RF-03`) dependientes de la terna neuronal `[IZQ, CEN, DER]`, prioridad `SALIDA > BLOQUEO > LOBITO`, exploración pulsada (`4` avance / `6` pausa), centrado pulsado en el lugar ante `LOBITO_IZQ/DER`, cooldown anti-loop en pared paralela, pasos de reversa fijos, modo `ESCAPE` y recuperación de `ERROR_SEGURO` desde disco.
- `ServidorCentralControlTest` (1 test): controles operativos (`k`/`x`, `r` permitido en reposo y bloqueado en corrida) y rechazo de atajos de debug.
- `ConsolaTuiTest` (5 tests): renderizado de solo lectura operativa en modos detallado (`106+` cols) y compacto (`92+` cols), cálculo de Hz, Fail-Safe `< 500 ms` y reset.
- `ConfiguracionServidorTest` (6 tests): carga de `.env`, constantes fijas (`ESCALA_VELOCIDAD_FIJA=0.40`, `PWM=105..255`, `PASOS_REVERSA_FIJO=4`) y resolución de rutas.
- `HardwareSerialAndCaptureTest` (8 tests): monitoreo pasivo en reposo, escala fija `0.40`, cooldown anti-rebote, `EVENT:CAPTURA`, `HELLO`, `--auto-start`, backoff de puerto fantasma y transición transitoria a `ERROR_SEGURO` ante corte de enlace durante corrida con restauración al reconectar.
- `TwoNetworksInferenceTest` (5 tests): inferencia dual pura (`RF-02`) con `modelo_ultrasonico.nnet` + `modelo_canicas.nnet`.
- `ConcurrencyEstomagoTest` (2 tests): estrés concurrente entre múltiples hilos escritores (`RF-06`) y `AuditorEstomago` (`RF-07`).
- `Hito2IntegrationTest` (2 tests): integración completa de persistencia concurrente e inferencia dual Neuroph + FSM.

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

### Controles operativos en TUI (solo ciclo de corrida y visualización, sin órdenes manuales de navegación/debug)
- `[k]` / `start`: envía `CMD:START` e inicia la corrida autónoma.
- `[x]` / `stop`: envía `CMD:STOP` y detiene el robot.
- `[r]` / `reset`: vacía `estomago.txt` y reinicia la FSM **únicamente en reposo** (bloqueado durante la corrida activa).
- `[v]` / `view`: alterna modo detallado / compacto.
- `[c]` / `clear`: limpia la ventana de logs recientes.
- `[q]`: cierre limpio restaurando la terminal.

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

> **Arquitectura pura (`2.0.5`):** `ClasificadorNeuroph` y `PlanificadorNavegacion` operan exclusivamente sobre inferencia **Neuroph 2.98** y FSM simbólica, sin umbrales sensoriales crudos ni soporte de pose de simulación (`SIM-01..13` y `HRD-01..14` resueltos en [`docs/AUDITORIA_CONSOLIDACION.md`](../docs/AUDITORIA_CONSOLIDACION.md)).

---

## 7. Manual Visual de la TUI de Competencia ("Solo Lectura Operativa")

> **Modo Entrega Final:** esta rama mantiene la TUI exclusivamente para monitoreo en vivo durante la pasada (sin panel de entrenamiento, grabación CSV ni teclas de calibración/debug).

### 7.1 Captura Visual de Referencia (`release/entrega-final` — Modo Detallado, `106` cols)

```text
    ______ __    ___________               ⣸⣧               ╭─ SISTEMA & ENLACE ──────────────────────╮
    ___  //_/_______(_)__  /_______       ⢠⣿⣿⣧              │ Ver: 2.0.9 | Rig: mega-3us-2laser       │
    __  ,<  _  _ \_  /__  //_/  __ \     ⢠⣿⣿⣿⣿⡆     ⢀⣀⣄⡀⠰⠴⣶⣶⣤⣤⡀ │ Enlace : BT /dev/rfcomm0 @ 9600         │
    _  /| | /  __/  / _  ,<  / /_/ /    ⣠⣿⣿⣿⣿⣿⡇ ⢀⣤⣶⣻⣾⣿⣴⣴⣾⣿⣿⣿⣿⣿⣿⡆ │ Cliente: Serie:/dev/rfcomm0@9600 (1)    │
    /_/ |_| \___//_/  /_/|_| \____/    ⣴⣿⣿⣿⣿⣿⣿⣥⣾⠿⢿⣿⣽⣾⣿⣿⣿⣿⣿⣿⣿⠿⢿⡿⣧ │ Hello  : [OK] v2.0.9                    │
                                     ⣠⣾⣿⣿⣿⣿⣿⠟⠉   ⣸⣿⣿⣿⣿⡿⠟⠛⠋⠉⠐⠊⠡⢹⢚ │ Enlace :  9.8Hz | dt: 45ms | FS:OK      │
                           ⢀⣀⣠⣤⣤⡴⠂⠐⠒⢨⣿⣿⣿⣿⣿⣿⣤⣆⣤⣠⣴⣾⣿⣷⡿⠋⠁     ⠐⣁⠎ ⡘ │ Redes  : US[OK] CAN[OK] Pkts:1240       │
                      ⢐⣠⣤⣶⣾⣿⣿⣿⣿⣿⣆⡀⡀⣀⣨⣿⣿⣿⣿⣿⣿⣿⣿⣿⠟⠁           ⡜  ⡐ ├─ CEREBRO (FSM) & MOTORES ───────────────┤
                   ⢠⣴⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠛⠁       ⠄ ⠄     ⠂ │ Estado : [BUSQUEDA]   REP #124          │
               ⢀⣠⣴⡶⠿⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡟⡀              ⡠⠂ │ Vel: 40% | PWM: 105..255 | Rev:4p       │
             ⢀⣵⣿⣿⣅    ⢈⠙⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠖            ⣀⠂ │ Motor L: [████░░░░░░] +1.04             │
           ⣠⣾⣿⣿⣿⣿⣿⣿⣿⣶⣦⣌⠁ ⠉⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡏⡞          ⣀⠜⠁ │ Motor R: [████░░░░░░] +1.04             │
   ⣀⣀⣤⢤⢤⡴⢶⣾⡿⠿⣛⠩ ⠉⠉⠙⠛⠻⠿⢏⡀   ⠙⠻⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡿⢈⡷        ⣠⣷⣿⡀ ├─ ESTOMAGO (estomago.txt) ───────────────┤
⢠⠖⠋⠉ ⢀ ⠂⣌⢇ ⣰⣿⠁         ⠈⠳⣄ ⡀  ⢀⣽⣿⣿⣿⣿⣿⣿⣿⣿⡿⠋⣐⠰⠂    ⡀⣠⣴⣾⣿⣿⣿⡇ │ Capturas: ● ○ ○ (1/3)   ESCAPE: NO      │
⠛⠓⠒⠲⢤⣀⣐⣤⡞⣸⢊⠥⣿             ⠙ ⢀⣤⣿⣿⣿⣿⣿⣿⣿⡿⠟⠋⢄⣀ ⠠⠤⠴⠂⠈⠁⢰⣿⣿⣿⣿⣿⣿⡇ │ #1: CAPTURA_LOBITO_1                    │
        ⢿⠃  ⠸⡄              ⠈⠉⠉⠉⠉⠉⠋⠉             ⠐⣿⣿⣿⣿⣿⣿ │ #2: ---                                 │
         ⠙⢖⣦⣀⢻⡀                                   ⢻⣿⣿⣿⣿⠃ │ #3: ---                    | Err:0      │
           ⠈⠛⠾⠉                                   ⠸⣿⡿⠛⠁ ╰─────────────────────────────────────────╯
╭─ SENSORES EN VIVO & INFERENCIA NEUROPH 2.98 ───────────────────────────────────────────────────────────╮
│  US-IZQ │  82.5cm │ [████░░░] │ ESPACIO_LIBRE 96% │ TERNA: [ESPACIO_LIBRE|SALIDA|ESPACIO_LIBRE]        │
│  US-CEN │ 138.0cm │ [██████░] │ SALIDA        94% │ Ult. Ctrl: CMD:START                               │
│  US-DER │  24.5cm │ [█░░░░░░] │ ESPACIO_LIBRE 92% │ Sin errores de cliente                             │
├─ FILA INFERIOR (2x VL53L0X ToF + Servo SG90 -> Red 2 Canicas) ─────────────────────────────────────────┤
│  LSR-IZQ:  24.8 cm  [██░░░░░░░] │ LSR-DER:  25.1 cm  [██░░░░░░░] │ Red 2: LOBITO_CENTRO (91%)          │
│  SERVO  :  -6.0°  [-45° ───────────●┼───────────────── +60°]   Barrido espejo L/R                      │
╰────────────────────────────────────────────────────────────────────────────────────────────────────────╯
╭─ EVENTOS Y LOGS RECIENTES ─────────────────────────────────────────────────────────────────────────────╮
│ 13:24:09 [OK] HELLO Serie:/dev/rfcomm0@9600 | ver=2.0.9 rig=mega-3us-2laser (COMPATIBLE)               │
│ 13:24:10 [INFO] Log de corrida: logs/corrida_20261125-132409.log                                       │
╰────────────────────────────────────────────────────────────────────────────────────────────────────────╯
 Ctrl: [k]Start [x]Stop [r]Reset(rep) [v]Vista [c]Log [q]Salir
```

*(Si la terminal mide menos de `106` columnas o menos de `32` filas, la TUI cambia automáticamente a `KEIKO TUI (COMPACTA)` para no desbordar márgenes; además toda sesión guarda copia automática en `logs/corrida_<timestamp>.log`).*

### 7.2 Qué significa cada panel y métrica

| Sección / Campo | Significado |
| :--- | :--- |
| **`Ver` / `Rig` / `Enlace` / `Hello`** | Versión del servidor, hardware esperado (`mega-3us-2laser`), dispositivo serie Bluetooth y estado del handshake `HELLO:<ver>,<rig>` (`[OK]` si coincide `major.minor` y `rig`). |
| **`Enlace Hz` / `dt` / `FS`** | Frecuencia real de recepción de `TELEMETRY:` (~`10.0 Hz`), silencio desde el último paquete (`dt`) y estado del Watchdog Fail-Safe (`FS:OK` < 350 ms, `FS:WARN`, `FS:TIMEOUT` ≥ 500 ms). |
| **`Redes`** | Estado de carga de Red 1 (`US[OK]`) y Red 2 (`CAN[OK]`) + total de tramas `Pkts` recibidas. |
| **`Estado` + `RUN`/`REP` + `#ciclos`** | Estado actual de `PlanificadorNavegacion` (`BUSQUEDA`, `APROXIMACION`, `CAPTURA`, `ESCAPE`, `FINALIZACION`, `ERROR_SEGURO`) y si la corrida autónoma está activa (`RUN`) o detenida (`REP`). |
| **`Vel` / `PWM` / `Rev`** | Constantes operativas fijas congeladas para la corrida (`40%`, `105..255`, `4p = 0.4 s`). |
| **`Motor L` / `Motor R`** | Velocidad diferencial comandada tras aplicar la escala fija (`+` verde avanza, `-` rojo retrocede, `■` detenido). |
| **`Capturas` / `ESCAPE` / `#1..#3` + `Err`** | Progreso persistido en `estomago.txt` (`● ● ● (3/3)` activa `ESCAPE: SI` vía `AuditorEstomago`), detalle de los 3 registros físicos y contador de errores reportados por el cliente. |
| **`US-IZQ / US-CEN / US-DER`** | Distancia en cm de los 3 HC-SR04 superiores (`999.0` = sin eco), etiqueta de Red 1 (`BLOQUEO`, `ESPACIO_LIBRE`, `SALIDA`), confianza `%` y **`TERNA`** final fusionada `[IZQ|CEN|DER]`. |
| **`LSR-IZQ / LSR-DER / Red 2 / SERVO`** | Distancia de los 2 VL53L0X inferiores (`TIMEOUT` = `999.0` sin retorno), clase de Red 2 (`LOBITO_*` / `NINGUNA`) y ángulo de espejo (`-45°` converge al centro, `0°` recto, `+60°` abre hacia afuera). |
