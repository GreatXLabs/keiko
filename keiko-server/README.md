# Servidor Central (Cerebro) - Proyecto Orquitas (Keiko)

Módulo de procesamiento distribuido, inferencia con **Neuroph 2.98** (arquitectura dual: `modelo_ultrasonico.nnet` + `modelo_canicas.nnet`), persistencia crítica en `estomago.txt` y conexión directa por **Puerto Serie / Bluetooth SPP (`HC-05`) o Socket TCP**.

---

## 1. Requisitos

- **Java:** JDK 21 LTS (compatible de forma nativa con Apache NetBeans: *File → Open Project → `keiko-server/`*).
- **Maven:** 3.9+.
- **Modelos `.nnet` incluidos:** Las 2 redes entrenadas ya vienen empaquetadas en `keiko-server/models/` y dentro del Fat JAR (`target/keiko-server.jar`), por lo que no requieren archivos externos.

---

## 2. Compilación Rápida

```bash
cd keiko-server
mvn clean package
```

Esto ejecuta los tests unitarios y genera `target/keiko-server.jar` listo para correr en cualquier PC (Windows o Linux).

---

## 3. Cómo Conectar con el Robot Físico (Arduino Mega + HC-05)

### Opción A — Puerto Serie directo (Windows `COMx`, Linux `/dev/rfcomm0` o Cable USB)

1. Ver qué puertos serie/Bluetooth detecta la PC:
   ```bash
   java -jar target/keiko-server.jar --list-ports
   ```
2. Iniciar el servidor reseteando `estomago.txt` para una pasada nueva (el `HC-05` del robot habla a **9600 baudios**):
   ```bash
   # Windows (reemplazar COM5 por el puerto saliente del HC-05)
   java -jar target/keiko-server.jar --serial COM5 --baud 9600 --reset

   # Linux con /dev/rfcomm0 o por cable USB (/dev/ttyUSB0 o /dev/ttyACM0 a 115200 si se usa modo USB)
   java -jar target/keiko-server.jar --serial /dev/rfcomm0 --baud 9600 --reset
   ```

### Opción B — Puente RFCOMM directo en Linux (sin `sudo rfcomm bind`)

Si ya emparejaste el `HC-05` con `bluetoothctl` (`transport bredr` → `scan on` → `pair` → `trust`):

```bash
# Terminal 1: levantar el servidor TCP
java -jar target/keiko-server.jar --port 5000 --reset

# Terminal 2: conectar por Bluetooth RFCOMM canal 1 hacia el servidor TCP
python3 scripts/bt_rfcomm_bridge.py --mac <MAC_DEL_HC05> --port 5000
```

---

## 4. Protocolo Serie / Bluetooth (`9600 8N1`, delimitado por `\n`)

| Mensaje | Dirección | Formato y Función |
| :--- | :---: | :--- |
| `TELEMETRY` | Mega → Servidor | `TELEMETRY:<us_izq>,<us_cen>,<us_der>,<laser_izq>,<laser_der>,<ang_servo>,<ms>\n` |
| `EVENT` | Mega → Servidor | `EVENT:CAPTURA_LOBITO_<n>\n` al detectar canica con el `VL53L0X` del estómago (`0x32`) |
| `COMMAND` | Servidor → Mega | `COMMAND:<v_left>,<v_right>,<estado>\n` (`BUSQUEDA`, `APROXIMACION`, `ESCAPE`, etc.) |
| `STATE_CHANGE` | Servidor → Mega | `STATE_CHANGE:ESCAPE\n` emitido por `AuditorEstomago` al validar **3 capturas** en `estomago.txt` |
| `HEARTBEAT` | Bidireccional | `HEARTBEAT\n` enviado periódicamente cada `200 ms` para sostener el watchdog Fail-Safe (`< 500 ms`) |

---

## 5. Conteo de Canicas y Persistencia (`estomago.txt`)

- El Arduino Mega detecta el ingreso de cada canica con el sensor láser `VL53L0X` dentro del estómago (`I2C 0x32`, `XSHUT=A10`) mediante captura armada y envía `EVENT:CAPTURA_LOBITO_<n>`.
- `ManejadorCliente` filtra rebotes mecánicos (`CAPTURE_COOLDOWN_MS=1500`) e ignora eventos extra una vez alcanzado el modo `ESCAPE`.
- `GestorEstomago` persiste cada captura en `estomago.txt` dentro de sección crítica sincronizada con `flush()` + `FileDescriptor.sync()` y checksum `CRC32`.
- `AuditorEstomago` ("La Linareada") audita periódicamente `estomago.txt` desde disco y dispara `STATE_CHANGE:ESCAPE` al confirmar **3/3** canicas.
