# Servidor Central (Cerebro) — Proyecto Orquitas (Keiko)

Módulo de procesamiento distribuido, inferencia dual con **Neuroph 2.98**, conectividad exclusiva por **Bluetooth RFCOMM/SPP** (`jSerialComm`), monitor TUI ANSI en vivo, persistencia atómica y concurrencia en **Java 21 LTS** para el ecosistema **Orquitas (`2.0.2` | `mega-3us-2laser`)**.

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
Ejecuta las **11 clases de prueba (62 tests JUnit 5)**:
- `ProtocolParserTest` (10 tests): parseo de `TELEMETRY` (7 campos sin pose), `HELLO`, `CMD:START`/`CMD:STOP`, `CMD:PWM:`, `LASER:`, `STATE_CHANGE:`, `PICKER:ON/OFF`, `DBG:estomago=...` y rechazo de tramas corruptas.
- `PlanificadorNavegacionTest` (17 tests): transiciones FSM simbólicas dependientes exclusivamente de la terna neuronal `[IZQ, CEN, DER]`, pivote sin reversa frente a `SALIDA`, pasos de reversa ajustables (`0..15`), anti-bucle, modo `ESCAPE` y modos forzados (`BUSQUEDA`, `ESCAPE`, `CAPTURA`).
- `RealDatasetAndRetrainTest` (3 tests): captura de datasets reales en marcha y en reposo (preservando `999.0` sin eco), reentrenamiento Neuroph 2.98, historial inmutable (`models/historial/`) y rollback en caliente.
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

> **Arquitectura pura (`2.0.0`):** `ClasificadorNeuroph` y `PlanificadorNavegacion` operan exclusivamente sobre inferencia **Neuroph 2.98** y FSM simbólica, sin umbrales sensoriales crudos ni soporte de pose de simulación (`SIM-01..13` y `HRD-01..14` resueltos en [`docs/AUDITORIA_CONSOLIDACION.md`](../docs/AUDITORIA_CONSOLIDACION.md)).
