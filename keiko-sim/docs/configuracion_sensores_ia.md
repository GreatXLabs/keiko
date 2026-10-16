# Guía de Ingeniería: Configuración Modular de Sensores e Inferencia Neuronal (Neuroph 2.98)

Especificación técnica de la arquitectura de percepción sensorial, generación sintética de datasets en Webots 3D y despliegue del clasificador neuronal con **Neuroph 2.98** para el ecosistema autónomo **La Orquita (Keiko)** (PSR / DS 2026).

---

## 1. Fundamentos de Percepción en Doble Plano Coaxial

El discernimiento confiable de canicas físicas (*Lobitos*, diámetro \(16\text{ mm}\)) frente a muros perimetrales (\(200\text{ mm}\) de altura) u obstáculos prismáticos no puede resolverse con umbrales fijos o sonares a una única cota de altura:

1. **Plano Bajo (\(Z = 12\text{ mm}\)):**
   - Transductores ultrasónicos situados a la altura exacta del baricentro de la canica (\(R = 8\text{ mm}\)).
   - Cono acústico de apertura ancha (\(0.60\text{ rad} \approx 34^\circ\), 17 rayos ODE) alineado horizontalmente con el suelo.
   - Detecta canicas en proximidad (\(10\text{ a }45\text{ cm}\)), muros perimetrales y obstáculos.
2. **Plano Alto (\(Z = 95\text{ mm}\)):**
   - Transductores coaxiales montados en la bandeja acrílica superior.
   - Su haz horizontal vuela enteramente por encima de la canica (\(16\text{ mm} \ll 95\text{ mm}\)).
   - Únicamente detecta elementos de gran porte (muros, cajas, columnas).

### Firma Biométrica de Detección (Clasificación Neuronal)
| Clase de Objeto | Firma Plano Bajo (\(Z=12\text{ mm}\)) | Firma Plano Alto (\(Z=95\text{ mm}\)) | Asimetría Izq/Der |
| :--- | :--- | :--- | :--- |
| **`LOBITO` (Canica)** | Corta distancia (\(10 - 45\text{ cm}\)) | Larga distancia / Fondo (\(> \text{Bajo} + 8\text{ cm}\)) | Simétrica o leve desvío |
| **`MURO`** | Corta/media distancia (\(< 55\text{ cm}\)) | Distancia idéntica (\(\Delta < 15\text{ cm}\)) | Simetría bilateral fuerte |
| **`OBSTACULO`** | Corta/media distancia | Distancia idéntica en flanco impactado | Fuerte asimetría (\(\Delta > 14\text{ cm}\)) |
| **`SALIDA`** | Espacio abierto frontal (\(> 100\text{ cm}\)) | Espacio abierto frontal (\(> 100\text{ cm}\)) | Paredes laterales visibles |
| **`ESPACIO_LIBRE`** | Todo despejado (\(> 80\text{ cm}\)) | Todo despejado (\(> 80\text{ cm}\)) | Sin eco cercano |

---

## 2. Matriz de Configuraciones Modulares de Sensores

El modelo 3D en Webots incorpora 8 bahías de montaje físicas en el chasis Todomicro 4WD. Los controladores pueden habilitar selectivamente las siguientes configuraciones:

| Perfil | Sensores Activos | Entradas Red | Propósito Operativo |
| :--- | :--- | :---: | :--- |
| **Configuración 4 Sensores (Canónica)** | `us_low_left`, `us_low_right`, `us_high_left`, `us_high_right` | **4** | Cumplimiento estricto rúbrica oficial PSR 2026. Discriminación de canicas y escape. |
| **Configuración 6 Sensores (Extendida Flancos)** | Base 4 + `us_side_left`, `us_side_right` | **6** | Navegación perimetral fluida, centrado en pasillos y evasión anticipada sin zigzagueo. |
| **Configuración 8 Sensores (Omnidireccional)** | Base 6 + `us_rear_left`, `us_rear_right` | **8** | Retroceso autónomo seguro, maniobras de desatasque y verificación de retaguardia. |

### Coordenadas Físicas de Montaje (Chasis Todomicro 4WD)
| Sensor | Cota \(X\) (Avance) | Cota \(Y\) (Lateral) | Cota \(Z\) (Altura) | Orientación Yaw | Tipo |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `us_low_left` | \(+0.138\text{ m}\) | \(+0.038\text{ m}\) | \(+0.012\text{ m}\) | \(+5^\circ\) (\(+0.087\text{ rad}\)) | Sonar 17 rayos |
| `us_low_right` | \(+0.138\text{ m}\) | \(-0.038\text{ m}\) | \(+0.012\text{ m}\) | \(-5^\circ\) (\(-0.087\text{ rad}\)) | Sonar 17 rayos |
| `us_high_left` | \(+0.138\text{ m}\) | \(+0.038\text{ m}\) | \(+0.095\text{ m}\) | \(+5^\circ\) (\(+0.087\text{ rad}\)) | Sonar 17 rayos |
| `us_high_right` | \(+0.138\text{ m}\) | \(-0.038\text{ m}\) | \(+0.095\text{ m}\) | \(-5^\circ\) (\(-0.087\text{ rad}\)) | Sonar 17 rayos |
| `us_side_left` | \(0.000\text{ m}\) | \(+0.075\text{ m}\) | \(+0.065\text{ m}\) | \(+90^\circ\) (\(+1.571\text{ rad}\)) | Sonar 17 rayos |
| `us_side_right` | \(0.000\text{ m}\) | \(-0.075\text{ m}\) | \(+0.065\text{ m}\) | \(-90^\circ\) (\(-1.571\text{ rad}\)) | Sonar 17 rayos |
| `us_rear_left` | \(-0.125\text{ m}\) | \(+0.040\text{ m}\) | \(+0.065\text{ m}\) | \(180^\circ\) (\(3.142\text{ rad}\)) | Sonar 17 rayos |
| `us_rear_right` | \(-0.125\text{ m}\) | \(-0.040\text{ m}\) | \(+0.065\text{ m}\) | \(180^\circ\) (\(3.142\text{ rad}\)) | Sonar 17 rayos |

---

## 3. Pipeline Completo: De la Simulación a la Inferencia Neuronal

```text
[Webots 3D: bahia_de_caza_dataset.wbt]
              |
              v (Supervisor Ground-Truth)
[generate_dataset.sh 4|6|8]  --->  [orquitas_dataset_N_sensores.csv]
                                            |
                                            v (Partición 70/30 + MinMax)
                                 [SimulatedDatasetTrainTest.java]
                                            |
                                            v (Backpropagation Multicapa)
                                 [orquitas_Nsensores_neuroph.nnet]
                                            |
                                            v (Deserialización automática)
                                 [NeurophClassifier.java (keiko-server)]
                                            |
                                            v (Inferencia en tiempo real)
                                 [Control Motriz FSM: COMMAND:vL,vR,ESTADO]
```

### 3.1 Paso 1: Generación Sintética de Datos
Ejecutar el script acelerado sin interfaz gráfica (modo batch ODE):
```bash
# Para el dataset canónico de 4 sensores:
./keiko-sim/scripts/generate_dataset.sh 4

# Para el dataset extendido de 6 sensores:
./keiko-sim/scripts/generate_dataset.sh 6
```
Genera un archivo balanceado de \(\sim 2000\) muestras en `keiko-sim/data/datasets/` con etiquetado Ground-Truth estricto.

### 3.2 Paso 2: Entrenamiento con Neuroph 2.98
Ejecutar la suite de entrenamiento de redes neuronales:
```bash
./keiko-sim/scripts/train_neuroph.sh
```
Métricas alcanzadas sobre conjunto de prueba independiente (30% holdout):
- **Modelo 4 Sensores:** Accuracy: **82.34%**, F1-Score: **0.8024**.
- **Modelo 6 Sensores:** Accuracy: **87.74%**, F1-Score: **0.8756**.

El modelo queda serializado en formato binario estándar de Neuroph:
`keiko-sim/data/models/orquitas_4sensores_neuroph.nnet`.

### 3.3 Paso 3: Inferencia en el Servidor Central Java
El servidor (`keiko-server`) detecta dinámicamente la topología de la red cargada:
```java
int inputCount = neuralNetwork.getInputsCount(); // 4 o 6
double[] normalized = vector.toNormalizedArray(inputCount, mins, maxs);
neuralNetwork.setInput(normalized);
neuralNetwork.calculate();
double[] output = neuralNetwork.getOutput();
```

---

## 4. Garantías de Seguridad Física y Anti-Vacío en el Mapa

Para impedir cualquier caída accidental al vacío o atasque en esquinas durante la navegación autónoma:

1. **Plano de Contención de Fondo (`SAFETY_GROUND`):**
   - Superficie plana sólida de \(10.0\text{ m} \times 10.0\text{ m}\) situada a \(Z = -0.04\text{ m}\).
   - Recubre enteramente la bahía (\(2.4\text{ m} \times 2.4\text{ m}\)) y la plataforma de escape exterior (\(1.2\text{ m} \times 0.8\text{ m}\)).
2. **Muros Exteriores Perimetrales Anti-Vacío:**
   - 4 muros de contención exterior de \(10.0\text{ m}\) de longitud y \(0.60\text{ m}\) de altura en \(X = \pm 5.0\text{ m}, Y = \pm 5.0\text{ m}\).
   - Imposibilidad matemática y física de que un objeto o vehículo sea eyectado al vacío.
3. **Chaflanes Guía en el Estrecho de Salida:**
   - Dos guías diagonales a \(30^\circ\) en \(X = \pm 0.19\text{ m}, Y = 1.15\text{ m}\).
   - Si la Orquita se aproxima en ángulo hacia la salida de \(30\text{ cm}\), el paragolpes frontal se desliza suavemente hacia el centro del pasillo sin colisionar con bordes a \(90^\circ\).
4. **Cámara Cerrada de Plataforma Exterior:**
   - Encerrada por `EXIT_WALL_WEST` (\(X = -0.60\)), `EXIT_WALL_EAST` (\(X = +0.60\)) y `EXIT_WALL_NORTH` (\(Y = 2.00\)).
   - Cuando el vehículo cruza \(Y > 1.30\text{ m}\), detiene sus motores inmediatamente y concluye la prueba en estado `FINALIZACION`.
