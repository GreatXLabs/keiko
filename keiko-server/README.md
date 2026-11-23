# Servidor Central (Cerebro) — Proyecto Orquitas (Keiko)

Módulo de procesamiento distribuido, inferencia dual con **Neuroph 2.98**, conectividad serie/Bluetooth SPP y TCP, persistencia atómica y concurrencia en **Java 21 LTS** para el ecosistema **Orquitas (`2.0.0-audit.2` | `mega-3us-2laser`)**.

---

## 1. Arquitectura Técnica y Concurrencia

```text
[Cliente: La Orquita (Arduino Mega HC-05 / Webots robot_client.py)]
         |
         | (Bluetooth RFCOMM/SPP vía jSerialComm o Socket TCP - Puerto default 5000)
         v
+-----------------------------------------------------------------------------+
|                           SERVIDOR CENTRAL (PC)                             |
|                                                                             |
|  [ReceptorConexiones (TCP) / ReceptorSerie (Serial) -> ManejadorCliente]    |
|    - Valida handshake HELLO:<version>,<rig> (major.minor + rig)             |
|    - Lee tramas ASCII '\n' (TELEMETRY, EVENT, HEARTBEAT)                    |
|    - Emite HEARTBEAT periódico (200 ms por defecto, Fail-Safe < 500 ms)     |
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
Ejecuta las **8 clases de prueba (39 tests JUnit 5)**:
- `ProtocolParserTest` (9 tests): parseo de `TELEMETRY`, `HELLO`, `CMD:START`/`CMD:STOP`, `DBG:`, `esCompatibleMajorMinor` y rechazo de tramas corruptas.
- `PlanificadorNavegacionTest` (9 tests): transiciones FSM, evasión de bloqueos, anti-bucle y modo `ESCAPE`.
- `ConfiguracionServidorTest` (4 tests): carga de `.env`, argumentos CLI (`--auto-start`) y resolución de rutas.
- `HardwareSerialAndCaptureTest` (4 tests): cooldown anti-rebote de capturas, `EVENT:CAPTURA`, `HELLO`, `DBG:` y `--auto-start`.
- `TwoNetworksInferenceTest` (4 tests): inferencia dual con `modelo_ultrasonico.nnet` + `modelo_canicas.nnet`.
- `ConcurrencyEstomagoTest` (2 tests): estrés concurrente entre múltiples hilos escritores y `AuditorEstomago`.
- `Hito2IntegrationTest` (2 tests): integración cliente-servidor con `SimuladorOrquitaCliente`.
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
| `--port <n>` | `PORT` | `5000` | Puerto TCP del servidor (los lanzadores de Webots usan `--port 8888`) |
| `--serial <dev>` | `SERIAL_PORT` | *(desactivado)* | Puerto serie físico (`COM5`, `/dev/rfcomm0`, `/dev/ttyUSB0`, `/dev/ttyACM0`) |
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
# Modo TCP por defecto (puerto 5000)
java -jar target/keiko-server.jar

# Modo simulación Webots (puerto 8888 + reset de estómago)
java -jar target/keiko-server.jar --port 8888 --model ../keiko-sim/data/models/modelo_ultrasonico.nnet --reset

# Modo hardware físico por Bluetooth SPP (/dev/rfcomm0 o COMx)
java -jar target/keiko-server.jar --list-ports
java -jar target/keiko-server.jar --serial /dev/rfcomm0 --baud 9600 --reset

# Alternativa Linux con puente RFCOMM <-> TCP (sin crear /dev/rfcomm0 con sudo)
java -jar target/keiko-server.jar --port 5000 --reset
python3 scripts/bt_rfcomm_bridge.py --mac XX:XX:XX:XX:XX:XX --port 5000
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
| `HELLO` | Cliente → Servidor | `HELLO:<version>,<rig>\n` (ej. `HELLO:2.0.0-audit.2,mega-3us-2laser`) |
| `TELEMETRY` | Cliente → Servidor | `TELEMETRY:<us_izq>,<us_cen>,<us_der>,<laser_izq>,<laser_der>,<ang_servo>,<ts_ms>\n` |
| `COMMAND` | Servidor → Cliente | `COMMAND:<v_left>,<v_right>,<estado>\n` |
| `EVENT` | Cliente → Servidor | `EVENT:CAPTURA` o `EVENT:CAPTURA_LOBITO_<n>` |
| `STATE_CHANGE` | Servidor → Cliente | `STATE_CHANGE:<estado>\n` (ej. `STATE_CHANGE:ESCAPE`) |
| `CMD:START` / `CMD:STOP` | Servidor → Cliente | Arranque / detención de la corrida en firmware Mega (`--auto-start` o consola `start`/`stop`) |
| `HEARTBEAT` | Bidireccional | `HEARTBEAT\n` (mantiene vivo el enlace por debajo del watchdog de `500 ms`) |

> **Nota de auditoría (`2.0.0-audit.2`):** `ClasificadorNeuroph` y `PlanificadorNavegacion` conservan todavía filtros/umbrales heredados y soporte de pose de simulación inventariados en [`docs/AUDITORIA_CONSOLIDACION.md`](../docs/AUDITORIA_CONSOLIDACION.md) (`SIM-03..13`, `HRD-01..14`) para su retiro en Fase B (`2.0.0`).
