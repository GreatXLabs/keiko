# Proyecto Orquitas (Keiko) 🐋⚡

Ecosistema robótico autónomo distribuido para la competencia **"Inteligencia y Supervivencia en la Bahía de Caza"** (Programación Sobre Redes & Desarrollo de Sistemas, Ciclo Lectivo 2026).

---

## 🎯 Visión General del Proyecto

El proyecto implementa una arquitectura distribuida donde:
1. **La Orquita (Cliente):** Prototipo robótico autónomo basado en el chasis **4WD de acrílico de dos pisos (Kit Todomicro)** con 4 motores DC TT, 4 sensores de ultrasonido HC-SR04 en doble plano y sensor bucal de captura. Muestrea el entorno, valida valores en frontera, ejecuta comandos motrices y dispone de un **Watchdog Fail-Safe (< 500 ms)**.
2. **Servidor Central (Cerebro en PC):** Aplicación multihilo en **Java 21 LTS** (compatible con Apache NetBeans) que clasifica patrones sensoriales con **Neuroph 2.98**, gestiona la persistencia atómica en `estomago.txt` mediante `ReentrantReadWriteLock` con `FileDescriptor.sync()`, y ejecuta un **Hilo Auditor ("La Linareada")** que transiciona irreversiblemente al Modo Escape al verificar exactamente 3 capturas físicas en disco.
3. **Bahía de Caza (Simulador Webots 3D):** Entorno de pruebas físicas y dinámicas de alta fidelidad donde se calibran sensores, se generan datasets sintéticos balanceados y se prueba la navegación autónoma.
4. **Cluster Distribuido de Entrenamiento (`neuroph.aguilucho.ar:443`):** Infraestructura para entrenamiento acelerado de redes neuronales con Neuroph 2.98.

---

## 📂 Mapa del Repositorio

```text
keiko/
├── keiko-server/             <- Servidor Central Java 21 (NetBeans + Maven)
│   ├── pom.xml               <- Configuración Maven y dependencias (Neuroph 2.98, JUnit 5)
│   ├── src/main/java/        <- Código fuente del Cerebro, Auditor, Red y FSM
│   ├── src/test/java/        <- Pruebas de concurrencia, inferencia y protocolo
│   └── README.md             <- Manual de uso del Servidor Central
│
├── keiko-sim/                <- Simulación Robótica 3D en Webots
│   ├── worlds/               <- Mundos 3D (.wbt) de la Bahía de Caza
│   ├── controllers/          <- Controladores en Python (live_visualizer, robot_client, dataset_collector)
│   ├── data/                 <- Datasets sintéticos y modelo neuronal .nnet
│   ├── docs/                 <- Circuito electrónico, BOM y pinout de conexión
│   ├── scripts/              <- Scripts de ejecución (run_gui.sh, run_distributed.sh, etc.)
│   └── README.md             <- Guía del entorno de simulación
│
├── firmware/                 <- Código fuente embebido para microcontroladores
│   └── orquita_firmware/     <- Sketch oficial para Arduino UNO (L298N + HC-05 + Fail-Safe)
│
├── neuroph-train/            <- Cliente/Servidor Swing con FlatLaf para entrenamiento distribuido
│   ├── pom.xml
│   └── src/
│
├── entrega-hito2-java/       <- Snapshot de la entrega del Hito 2 (evidencia)
├── docs/                     <- Documentación, consignas de la cátedra y diagramas UML
└── AGENTS.md                 <- Especificación técnica de ingeniería y reglas operativas
```

---

## ⚡ Comandos Rápidos de Inicio

### 1. Ejecutar la Simulación 3D Local (Visualización Directa)
```bash
./keiko-sim/scripts/run_gui.sh
```

### 2. Ejecutar la Arquitectura Distribuida Completa (Servidor Java + Cliente Webots)
```bash
./keiko-sim/scripts/run_distributed.sh
```

### 3. Compilar y Ejecutar Pruebas del Servidor Central (Maven / NetBeans)
```bash
cd keiko-server
mvn clean test
```

### 4. Generar Dataset y Entrenar Red Neuronal en Neuroph 2.98
```bash
# 1. Generar 2000 muestras en segundos
./keiko-sim/scripts/generate_dataset.sh

# 2. Entrenar red neuronal (4 -> 24 -> 5) en Neuroph
./keiko-sim/scripts/train_neuroph.sh
```

---

## 🔌 Hardware y Circuito Electrónico

El esquema detallado de conexiones, cálculo de consumos, divisor de tensión para el módulo Bluetooth HC-05 (3.3V) y conexionado del puente H L298N para los 4 motores se encuentra documentado en:
👉 **[`keiko-sim/docs/circuito_electronico.md`](keiko-sim/docs/circuito_electronico.md)**

El firmware listo para flashear en el Arduino UNO físico se ubica en:
👉 **[`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)**

---

## 📜 Reglamento y Evaluación

- **Fase 1 (Panza Llena):** Cazar exactamente 3 Lobitos (+5, +10, +15 puntos).
- **Fase 2 (Mutación Conductual):** Disparada únicamente por el Hilo Auditor al contar 3 registros en `estomago.txt` (+15 puntos).
- **Fase 3 (Escape):** Encontrar el Estrecho de Salida y abandonar la Bahía (+30 puntos).
- **Fail-Safe:** Inactividad o pérdida de enlace > 500 ms detiene motores. Inactividad en pista > 10 s finaliza la pasada.
