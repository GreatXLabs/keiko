# Datasets V2 - Recolección Dinámica en Simulación

Esta carpeta almacena los datos sensoriales recolectados dinámicamente por `SimulationDatasetRecorder` durante las iteraciones de simulación en Webots con las bahías irregulares:

- `dataset_ultrasonico_v2.csv`: Lecturas de distancia ultrasonido y etiqueta de entorno (`BLOQUEO`, `ESPACIO_LIBRE`, `SALIDA`).
- `dataset_canicas_v2.csv`: Lecturas completas (3 US + 2 Láser ToF + Servo) y etiqueta de canica (`LOBITO_CENTRO`, `LOBITO_DER`, `LOBITO_IZQ`, `NINGUNA`).

Para consolidar y balancear las muestras con la base calibrada V1, ejecuta:
```bash
python3 keiko-sim/scripts/consolidar_dataset_v2.py --merge
```
Para promoverlos a producción:
```bash
python3 keiko-sim/scripts/consolidar_dataset_v2.py --deploy
```
