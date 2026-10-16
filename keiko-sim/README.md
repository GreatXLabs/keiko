# Keiko-Sim: Entorno de Simulación Robótica 3D 🐋⚡

Módulo de simulación 3D de alta fidelidad basado en **Webots (Cyberbotics)** para el ecosistema robótico autónomo **Proyecto Orquitas (Keiko)**.

Diseñado para resolver el desafío de entrenar y validar redes neuronales artificiales en **Neuroph 2.98**, ensayar ubicaciones y geometrías de sensores físicos, y simular el ciclo biológico completo de la Orquita en la **Bahía de Caza** sin desgaste de hardware ni baterías.

---

## 🌟 Características Principales

1. **Física Tridimensional Real (Open Dynamics Engine)**:
   - Modela la pista reglamentaria de \(2.4\text{ m} \times 2.4\text{ m}\) con muros perimetrales de \(15\text{ cm}\) y el **Estrecho de Salida** de \(30\text{ cm}\).
   - Simula presas biológicas ("Lobitos") como canicas físicas rodantes de \(16\text{ mm}\) con masa, fricción y restitución dinámica.
   - Cavidad bucal del robot con sensor de contacto para registrar capturas físicas (`EVENT: CAPTURA`).

2. **Banco de 4 Sensores Ultrasónicos en Doble Plano Vertical**:
   - **Plano Inferior (Z = 1 cm, \(\pm 5^\circ\) de convergencia frontal)**: Detecta canicas pequeñas en el suelo y obstáculos a nivel de piso.
   - **Plano Superior (Z = 8 cm, \(\pm 20^\circ\) de divergencia lateral)**: Detecta muros perimetrales y obstáculos altos, disparando por encima de las canicas para diferenciar presas de paredes.
   - **Apertura Acústica Conforme a Catálogo**: Modela conos de dispersión de \(26^\circ\) y reflectividad acústica sobre esferas vs. planos continuos.

3. **Generación Acelerada de Datasets Sintéticos (Modo Headless)**:
   - Controlador Supervisor que recorre la bahía y genera miles de muestras balanceadas en segundos (`webots --mode=fast --no-rendering`).
   - Etiquetado de Ground Truth en las 5 clases oficiales: `LOBITO`, `MURO`, `OBSTACULO`, `SALIDA`, `ESPACIO_LIBRE`.

4. **Entrenamiento y Validación en Neuroph 2.98**:
   - Integración nativa con `neuroph-train`.
   - Rendimiento demostrado en pruebas de generalización: **95.07% de Accuracy** y **0.9517 de F1-Score**.

---

## 📁 Estructura del Subproyecto

```text
keiko-sim/
├── config/
│   └── simulation_params.json         <- Dimensiones, ángulos, aperturas y configuración
├── worlds/
│   ├── bahia_de_caza.wbt              <- Escena 3D interactiva para visualización en tiempo real
│   └── bahia_de_caza_dataset.wbt      <- Escena configurada para generación masiva acelerada
├── controllers/
│   ├── live_visualizer/
│   │   └── live_visualizer.py         <- Controlador en vivo: ciclo biológico de 3 fases y telemetría
│   └── dataset_collector/
│       └── dataset_collector.py       <- Supervisor: muestreo de poses y exportación a CSV
├── data/
│   ├── datasets/                    <- Datasets sintéticos balanceados (4/6/8/10 sensores)
│   ├── models/                     <- Redes neuronales .nnet (producción y por experimento)
│   └── README.md                   <- Índice de datasets y modelos
├── scripts/
│   ├── run_gui.sh                     <- Lanza la simulación interactiva 3D con interfaz
│   ├── generate_dataset.sh            <- Genera el dataset CSV en modo acelerado
│   └── train_neuroph.sh               <- Entrena el modelo en Neuroph y valida métricas
└── README.md                          <- Esta documentación
```

---

## 🚀 Guía Rápida de Uso

### 1. Ver la Simulación en Tiempo Real (Modo Standalone)
Abre la Bahía de Caza en Webots y observa a la Orquita navegar, esquivar muros, detectar canicas y ejecutar las 3 fases del ciclo biológico con el controlador local:
```bash
./scripts/run_gui.sh
```

### 2. Ejecutar la Arquitectura Distribuida Completa (Hardware-in-the-Loop)
Arranca el Servidor Central Cerebro en Java 21 (con Neuroph 2.98, concurrencia, `estomago.txt` y Auditor) en simultáneo con el cliente en Webots 3D comunicándose por socket:
```bash
./scripts/run_distributed.sh
```

### 3. Generar un Nuevo Dataset Sintético
Ejecuta la simulación en modo acelerado sin ventana gráfica para generar miles de filas CSV:
```bash
./scripts/generate_dataset.sh
```
El archivo resultante se guardará en `data/datasets/orquitas_dataset_4_sensores.csv`.

### 4. Entrenar la Red Neuronal con Neuroph 2.98
Ejecuta el pipeline de entrenamiento en Java 21 sobre el dataset generado:
```bash
./scripts/train_neuroph.sh
```
El modelo compilado `.nnet` se guardará automáticamente en `data/models/orquitas_4sensores_neuroph.nnet`.

---

## 📊 Resultados de Entrenamiento en Neuroph

Entrenamiento de MultiLayerPerceptron (Topología \(4 \to 24 \to 5\)) evaluado sobre el 30% de datos de prueba no vistos:

| Métrica | Valor Obtenido |
| :--- | :---: |
| **Accuracy (Precisión Global)** | **95.07%** |
| **F1-Score Promedio** | **0.9517** |
| **RMSE (Error Cuadrático)** | **0.1434** |
| **Tiempo de Entrenamiento** | **2.14 segundos** (756 épocas) |

### Matriz de Confusión:
```text
                  ESPACIO  LOBITO    MURO  OBSTACULO  SALIDA
ESPACIO_LIBRE [    120,       0,      0,        0,      0 ]  (100.0%)
LOBITO        [      0,      67,      0,        1,      0 ]  ( 98.5%)
MURO          [      0,       0,    120,        0,      0 ]  (100.0%)
OBSTACULO     [      3,       2,      0,      114,      1 ]  ( 95.0%)
SALIDA        [      8,       1,      2,        9,    100 ]  ( 83.3%)
```

---

## 💻 Instalación de Webots para el Equipo

Webots es un software libre y de código abierto (licencia Apache 2.0). Cada integrante del equipo puede instalarlo según su sistema operativo:

### En Linux (Ubuntu / Debian):
Instalación sin permisos de root (versión portable oficial):
```bash
mkdir -p ~/.local/opt && cd ~/.local/opt
curl -L -o webots.tar.bz2 https://github.com/cyberbotics/webots/releases/download/R2025a/webots-R2025a-x86-64.tar.bz2
tar -xjf webots.tar.bz2 && rm webots.tar.bz2
ln -sf ~/.local/opt/webots/webots ~/.local/bin/webots
```

### En Windows:
1. Descarga el instalador oficial desde [Cyberbotics Webots Releases](https://github.com/cyberbotics/webots/releases/download/R2025a/webots-R2025a_setup.exe).
2. Ejecuta el instalador `.exe` y sigue el asistente.
3. Abre el archivo `keiko-sim/worlds/bahia_de_caza.wbt` directamente desde Webots.

### En macOS:
1. Descarga el archivo `.dmg` desde [Webots Releases](https://github.com/cyberbotics/webots/releases/download/R2025a/webots-R2025a.dmg).
2. Arrastra Webots a tu carpeta de Aplicaciones.

---

## ⚙️ Calibración de Sensores: Caso de Estudio (Blind Spot)

Durante las pruebas físicas iniciales en simulación se identificó que, con los sensores frontales abiertos a \(\pm 15^\circ\), existía un **punto ciego frontal de \(10^\circ\)** en el que las canicas de 16 mm no eran detectadas a distancias intermedias.

Gracias a la simulación paramétrica, la geometría se optimizó a:
- Orientación convergente de \(\pm 5^\circ\) en el plano bajo.
- Apertura cónica ajustada a \(0.45\text{ rad}\) (\(\sim 26^\circ\)).
- Detección de canicas frontal y lateral continua con 100% de cobertura.
