# Servidor Central (Cerebro) — Proyecto Orquitas (Keiko)

Módulo de procesamiento distribuido, inferencia dual con **Neuroph 2.98**, conectividad exclusiva por **Bluetooth RFCOMM/SPP** (`jSerialComm`), monitor TUI ANSI en vivo, persistencia atómica y concurrencia en **Java 21 LTS** para el ecosistema **Orquitas (`2.0.0` | `mega-3us-2laser`)**.

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
|    - TUI ANSI en vivo (sensores, Neuroph, FSM, Fail-Safe, estomago, logs)   |
|    - Comandos: start/k, stop/x, reset, view/v, clear/c                      |
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
Ejecuta las **9 clases de prueba (52 tests JUnit 5)**:
- `ProtocolParserTest` (10 tests): parseo de `TELEMETRY` (7 campos sin pose), `HELLO`, `CMD:START`/`CMD:STOP`, `DBG:`, `esCompatibleMajorMinor` y rechazo de tramas corruptas.
- `PlanificadorNavegacionTest` (14 tests): transiciones FSM simbólicas dependientes exclusivamente de la terna neuronal `[IZQ, CEN, DER]`, breakout de perímetro, anti-bucle y modo `ESCAPE`.
- `ConsolaTuiTest` (4 tests): renderizado detallado (`112` cols) y compacto (`92` cols) del monitor ANSI Bluetooth, cálculo de Hz, Fail-Safe `< 500 ms` y reset.
- `ConfiguracionServidorTest` (5 tests): carga de `.env`, argumentos CLI (`--auto-start`, `--no-tui`, `--tui`) y resolución de rutas.
- `HardwareSerialAndCaptureTest` (5 tests): cooldown anti-rebote de capturas, `EVENT:CAPTURA`, `HELLO`, `DBG:`, `--auto-start` e ignorado de eventos heredados (`SALIDA_META`).
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

Comandos interactivos disponibles en la consola del servidor: `start` (`k`), `stop` (`x`), `reset` (vacía `estomago.txt` y reinicia el planificador para pruebas), `view` (`v`, alterna vista detallada/compacta) y `clear` (`c`, limpia el panel de eventos recientes).

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

> **Arquitectura pura (`2.0.0`):** `ClasificadorNeuroph` y `PlanificadorNavegacion` operan exclusivamente sobre inferencia **Neuroph 2.98** y FSM simbólica, sin umbrales sensoriales crudos ni soporte de pose de simulación (`SIM-01..13` y `HRD-01..14` resueltos en [`docs/AUDITORIA_CONSOLIDACION.md`](../docs/AUDITORIA_CONSOLIDACION.md)).
