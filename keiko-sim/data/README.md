# 🧠 Datasets y Redes Neuronales — Orquitas (Keiko)

Índice del material de entrenamiento del proyecto. El detalle completo de campañas, épocas y métricas de cada experimento está en [`docs/EXPERIMENTS.md`](../../docs/EXPERIMENTS.md).

## 📁 Estructura

- **`datasets/`** — Muestras CSV etiquetadas (Ground-Truth) generadas en Webots 3D por `keiko-sim/controllers/dataset_collector/dataset_collector.py` (lanzado con `keiko-sim/scripts/generate_dataset.sh <4|6|8|10>`).
- **`models/`** — Redes Neuroph 2.98 serializadas (`.nnet`). Se obtienen por dos vías:
  - **Cluster distribuido** (`neuroph-train` + `keiko-sim/scripts/autonomous_researcher.py`): cada campaña descarga su artefacto como `model_exp-XXX.nnet`.
  - **Flujo local**: `keiko-sim/scripts/train_neuroph.sh <4|6|8|10>` entrena y valida los `orquitas_Nsensores_neuroph.nnet`.

## 📊 Datasets

| Archivo | Sensores | Muestras | Notas |
| :-- | :--: | :--: | :-- |
| `orquitas_dataset_4_sensores.csv` | 4 (doble plano frontal) | 2000 (400 por clase) | Línea de base (EXP-001) |
| `orquitas_dataset_6_sensores.csv` | 6 (4 frontales + 2 laterales) | 2000 (400 por clase) | Configuración campeona |
| `orquitas_dataset_8_sensores.csv` | 8 (4 frontales + 2 laterales + 2 traseros) | 1919 | Cobertura de retaguardia |
| `orquitas_dataset_10_sensores.csv` | 10 (trisector doble plano + laterales + traseros) | 2000 (400 por clase) | Exploración EXP-020 |

Formato: una columna `dist_*` normalizada por sensor + `class_label` con las 5 clases del clasificador: `LOBITO`, `MURO`, `OBSTACULO`, `SALIDA`, `ESPACIO_LIBRE`.

## 🏆 Modelos de producción y flujo local

| Archivo | Topología | Sensores | Descripción |
| :-- | :--: | :--: | :-- |
| `orquitas_production_champion.nnet` | `6-24-5` | 6 | **Modelo de producción.** Calibrado en cluster (EXP-011 / EXP-014 / EXP-015; Accuracy de test 77.6%–83.3%). Es el que carga `keiko-server` por defecto. |
| `orquitas_4sensores_neuroph.nnet` | `4-24-5` | 4 | Flujo `train_neuroph.sh 4`; línea de base validada en EXP-001 (95.0% Accuracy de test). |
| `orquitas_6sensores_neuroph.nnet` | `6-24-5` | 6 | Flujo `train_neuroph.sh 6`. |
| `orquitas_8sensores_neuroph.nnet` | `8-28-5` | 8 | Flujo `train_neuroph.sh 8`; 90.97% Accuracy / F1 0.9095 (EXP-017). |
| `orquitas_10sensores_neuroph.nnet` | `10-32-5` | 10 | Flujo `train_neuroph.sh 10`; 83.33% Accuracy (EXP-020). |

## 🧪 Modelos por experimento (`model_exp-*.nnet`)

Descargados automáticamente por `autonomous_researcher.py` al finalizar cada campaña del cluster. Accuracy y puntaje corresponden a la validación en simulación Webots 3D bajo las reglas PSR 2026 (detalle completo en `docs/EXPERIMENTS.md`):

| Archivo | Experimento | Sensores | Topología | Accuracy (test) | Puntaje PSR |
| :-- | :--: | :--: | :--: | :--: | :--: |
| `model_exp-002.nnet` | EXP-002 | 6 | `6-24-5` | 90.5% | +85 |
| `model_exp-003.nnet` | EXP-003 | 6 | `6-24-5` | 44.9% | +90 |
| `model_exp-004.nnet` | EXP-004 | 6 | `6-24-5` | 100.0% | +90 |
| `model_exp-005.nnet` | EXP-005 | 6 | `6-24-5` | 42.2% | +90 |
| `model_exp-006.nnet` | EXP-006 | 6 | `6-24-5` | 57.3% | +90 |
| `model_exp-007.nnet` | EXP-007 | 6 | `6-24-5` | 87.7% | +85 |
| `model_exp-008.nnet` | EXP-008 | 8 | `8-24-5` | 89.9% | +85 |
| `model_exp-009.nnet` | EXP-009 | 6 | `6-24-5` | 56.6% | +90 |
| `model_exp-010.nnet` | EXP-010 | 6 | `6-24-5` | 66.3% | +85 |
| `model_exp-011.nnet` | EXP-011 | 6 | `6-24-5` | 100.0% | +90 |
| `model_exp-012.nnet` | EXP-012 | 6 | `6-24-5` | 71.8% | +85 |
| `model_exp-013.nnet` | EXP-013 | 6 | `6-24-5` | 75.1% | +90 |
| `model_exp-014.nnet` | EXP-014 | 6 | `6-24-5` | 77.6% | +90 |

> Los experimentos EXP-001 y EXP-015 a EXP-021 no dejaron un `model_exp-*` propio: sus resultados se consolidaron en los modelos `orquitas_*sensores_neuroph.nnet` y en el campeón de producción según la bitácora.

## ⚙️ Cómo se usan

- **Servidor central:** carga `models/orquitas_production_champion.nnet` por defecto (ver `keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java` y la variable `MODEL_FILE_PATH` del `.env`).
- **Simulación:** `robot_client` puede inferir localmente con un modelo de `models/`.
- **Reentrenar:** `./keiko-sim/scripts/train_neuroph.sh 6` (genera/valida el `.nnet` correspondiente en `models/`).
