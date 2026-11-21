# Proyecto Orquitas (Keiko)

Ecosistema robótico autónomo distribuido para la competencia **"Inteligencia y Supervivencia en la Bahía de Caza"** (Programación Sobre Redes & Desarrollo de Sistemas, Ciclo Lectivo 2026).

Versión activa: **`2.0.0-audit.1`** | Hardware objetivo (`rig`): **`mega-3us-2laser`**

---

## Visión General del Sistema

1. **La Orquita (Cliente físico):** Chasis **4WD Skid-Steer** sobre **Arduino Mega 2560**, shield **L293D** (`M1..M4`), driver PWM I2C **PCA9685** (`0x40`), **3× sensores ultrasónicos HC-SR04** fijos en fila superior (`TRIG {24,27,29}`, `ECHO {25,26,28}`), **2× sensores láser ToF VL53L0X** (`0x30`, `0x31`) sobre servos SG90 en barrido espejo, picker recolector (**2× servos SG90 360°** + DC auxiliar), **1× VL53L0X de estómago** (`0x32`, `XSHUT A10`), módulo Bluetooth **HC-05** (`Serial1`, `9600` bps) y **Watchdog Fail-Safe (`< 500 ms`)**.
2. **Servidor Central (Cerebro en PC):** Aplicación multihilo en **Java 21 LTS** (Maven + compatible con Apache NetBeans) con arquitectura dual **Neuroph 2.98** (`modelo_ultrasonico.nnet` + `modelo_canicas.nnet`), transporte TCP (`puerto 5000` por defecto) y Bluetooth SPP / serie (`--serial`, `jSerialComm`), persistencia atómica en `estomago.txt` (`GestorEstomago` con `synchronized` + `FileDescriptor.sync()`) e **Hilo Auditor (`AuditorEstomago`)** que dispara la transición irreversible a `ESCAPE` al validar 3 capturas físicas en disco.
3. **Bahía de Caza (Simulador 3D Webots):** Entorno paramétrico semielíptico (`~1.80 m` base × `~1.20 m` profundidad) y circular (`R = 0.90 m`) con múltiples escenarios (`scen_1..14`, `circular`), física de ultrasonido y láser ToF, controlador `robot_client.py` (`mega-3us-2laser`) y recolección de datasets.
4. **Entrenamiento Neuroph (`neuroph-train/`):** Herramienta Swing/Java para entrenamiento local o distribuido (`neuroph.aguilucho.ar:443`) de redes `.nnet`.

---

## Mapa del Repositorio

```text
keiko/
├── VERSION                   <- Fuente única de verdad de versión (2.0.0-audit.1)
├── CHANGELOG.md              <- Historial de cambios, matriz de compatibilidad y evidencia de tests
├── AGENTS.md                 <- Especificación técnica, reglas de arquitectura y enrutador
├── scripts/
│   └── check_versions.sh     <- Validador de sincronización entre VERSION, Java, Mega y Webots (9 checks)
│
├── keiko-server/             <- Servidor Central Java 21 (Maven / Apache NetBeans)
│   ├── pom.xml               <- Dependencias (Neuroph 2.98, jSerialComm 2.11.0, JUnit 5)
│   ├── models/               <- Redes activas (.nnet): modelo_ultrasonico.nnet y modelo_canicas.nnet
│   ├── scripts/              <- bt_rfcomm_bridge.py (puente Bluetooth RFCOMM <-> TCP)
│   ├── src/main/java/        <- Servidor, protocolo, Neuroph dual, GestorEstomago, Auditor y FSM
│   ├── src/test/java/        <- Suite JUnit 5 (36 tests)
│   └── README.md             <- Manual de CLI, puertos, protocolo y Maven
│
├── keiko-sim/                <- Simulación Robótica 3D en Webots (3US + 2Laser)
│   ├── config/               <- simulation_params.json (geometría, física y versión/rig)
│   ├── worlds/               <- Escenas .wbt (bahia_de_caza_scen_1..14, circular, client, dataset)
│   ├── controllers/          <- Controladores Python (robot_client, live_visualizer, dataset_collector, common)
│   ├── data/                 <- Datasets CSV (datasets/) y modelos .nnet (models/)
│   ├── scripts/              <- iniciar_simulacion.sh, iterar_simulacion.sh, build_worlds.py, etc.
│   ├── tests/                <- Suite unittest de simulación
│   └── README.md             <- Guía de simulación Webots
│
├── firmware/
│   └── orquita_firmware/     <- Sketch oficial Arduino Mega 2560 (orquita_firmware.ino)
│
├── neuroph-train/            <- Cliente/Servidor Swing para entrenamiento de redes Neuroph 2.98
├── modelos-3d/               <- Modelos CAD FreeCAD (.FCStd), STL exportados (Git LFS) y planos
├── pruebas/                  <- Sketches de banco Mega, bitácoras, pinout y simulaciones Python
├── docs/                     <- Consignas, informes técnicos y auditoría (AUDITORIA_CONSOLIDACION.md)
└── archive/                  <- Código y documentos legacy (UNO, coaxial, Hito 2/3, experimentos)
```

---

## Comandos Rápidos de Inicio

### 1. Verificar Sincronización de Versiones y Rig
```bash
./scripts/check_versions.sh
```

### 2. Compilar y Ejecutar Pruebas del Servidor Central (Java 21 / Maven)
```bash
cd keiko-server
mvn clean test
mvn package -DskipTests=true
```

### 3. Ejecutar la Simulación 3D Completa (Servidor Java + Cliente Webots)
```bash
# Escenario 1 (por defecto) o elegir 1..14 / random / circular
./keiko-sim/scripts/iniciar_simulacion.sh 1
./keiko-sim/scripts/iniciar_simulacion.sh random
./keiko-sim/scripts/iniciar_simulacion.sh circular
```

### 4. Supervisión e Iteración Continua en Pantalla
```bash
./keiko-sim/scripts/iterar_simulacion.sh
```

### 5. Ejecutar Servidor Central contra Hardware Real (Bluetooth SPP / Serie)
```bash
cd keiko-server
java -jar target/keiko-server.jar --list-ports
java -jar target/keiko-server.jar --serial /dev/rfcomm0 --baud 9600 --reset
```
*(Alternativa sin `/dev/rfcomm0`: levantar el servidor en TCP puerto `5000` y enlazar con `python3 scripts/bt_rfcomm_bridge.py --mac XX:XX:XX:XX:XX:XX`.)*

---

## Hardware, Pinout y Documentación Técnica

- **Firmware oficial (Arduino Mega 2560):** [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)
- **Pinout verificado, alimentación XL4015, picker y estómago:** [`pruebas/PICKER-ESTOMAGO-Y-ALIMENTACION.md`](pruebas/PICKER-ESTOMAGO-Y-ALIMENTACION.md)
- **Índice de pruebas de banco y simulaciones físicas:** [`pruebas/README.md`](pruebas/README.md)
- **Auditoría de consolidación y hoja de ruta `2.0.0`:** [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md)
- **Reglamento y especificaciones de arquitectura:** [`AGENTS.md`](AGENTS.md)
