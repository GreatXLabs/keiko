# Pruebas de Hardware y Simulaciones (`pruebas/`)

Índice de las pruebas de banco activas para el robot sobre **Arduino Mega 2560**, la documentación técnica de hardware, el registro fotográfico y las simulaciones matemáticas en Python.

> Los experimentos descartados de etapas anteriores (`detector-6-ultrasonidos`, `lidar_scanner`, `marble_test`, `prueba-trabado-vcc`, `vision`) fueron archivados en [`archive/pruebas-experimentales/`](../archive/pruebas-experimentales/).

---

## 1. Tests de banco del robot actual (Arduino Mega 2560)

Plataforma objetivo: **Arduino Mega 2560** + shield **L293D** + driver PWM I2C **PCA9685** (`0x40`) + módulo Bluetooth **HC-05** + **3× HC-SR04** (obstáculos) + **3× VL53L0X** (2 frontales de barrido + 1 en estómago) + **4× servos SG90** (2 de barrido y 2 modificados a 360° para el picker).

> **Nota de migración UNO → Mega:** en el Mega el bus I2C está en `D20` (SDA) / `D21` (SCL), y el shield L293D tapa `A0–A5`, por lo que las líneas `XSHUT` finales usan `A8–A10` (ver [`PICKER-ESTOMAGO-Y-ALIMENTACION.md`](PICKER-ESTOMAGO-Y-ALIMENTACION.md)). Algunos sketches de diagnóstico inicial todavía referencian `A4`/`A5` o `A0`/`A1` en sus comentarios o constantes.

### 1.1 Frente sensorial integrado y prueba de estrés combinada
- [`detector-mixto/detector-mixto.ino`](detector-mixto/detector-mixto.ino): Prueba integrada del frente sensorial completo: 3 HC-SR04 arriba (`TRIG/ECHO`: izq `13/12`, med `11/10`, der `9/8`) y 2 VL53L0X abajo (`XSHUT`: izq `A8`, der `A9`; el del estómago en `A10` queda apagado) montados sobre servos SG90 en el PCA9685 (canales `0` y `1`).
- [`prueba-ultrasonidos-mega/prueba-ultrasonidos-mega.ino`](prueba-ultrasonidos-mega/prueba-ultrasonidos-mega.ino): Prueba y diagnóstico de los 3 HC-SR04 con el cableado real del Mega (`24/25`, `26/27`, `28/29`), modo ordenado vs. seguido y detección automática de pares cruzados (`'d'`).
- [`prueba-laser-mega/prueba-laser-mega.ino`](prueba-laser-mega/prueba-laser-mega.ino): Lectura simultánea en mm de los 3 VL53L0X del Mega (`XSHUT A8 -> 0x30`, `A9 -> 0x31`, `A10 -> 0x32`) y escaneo I2C (`'i'`).
- [`prueba-todo-junto/prueba-todo-junto.ino`](prueba-todo-junto/prueba-todo-junto.ino): Prueba combinada de caída de tensión (brownout) operando tracción `WASD` (USB + HC-05 `Serial1`), picker 360°, barrido de láseres, 3 VL53L0X y 3 HC-SR04 en simultáneo, registrando reinicios en EEPROM y reseteos del PCA9685.

### 1.2 Comunicación Bluetooth (HC-05)
- [`prueba-hc05/prueba-hc05.ino`](prueba-hc05/prueba-hc05.ino): Puente USB-Bluetooth (`Serial` a 115200 bps ↔ `Serial1` en `D18`/`D19` a 9600 bps por defecto), monitoreo del pin `STATE` (`D30`) y envío de `PING` cada 2 s.
- [`hc05-at/hc05-at.ino`](hc05-at/hc05-at.ino): Configuración del módulo HC-05 mediante comandos AT a 38400 bps desde el monitor serie del Mega (`Serial` a 115200 bps ↔ `Serial1` en `D18`/`D19`).

### 1.3 Bus I2C y sensores láser VL53L0X
- [`prueba-i2c/prueba-i2c.ino`](prueba-i2c/prueba-i2c.ino): Escáner del bus I2C; si detecta el PCA9685 en `0x40`, reporta su modo y el ancho de pulso configurado en los canales `0` y `1`.
- [`prueba-xshut/prueba-xshut.ino`](prueba-xshut/prueba-xshut.ino): Diagnóstico de los dos VL53L0X frontales encendiéndolos uno por uno vía `XSHUT` (en este sketch `A0`/`A1`) para verificar sus direcciones I2C junto al PCA9685 (`0x40`). En el cableado final del Mega las líneas `XSHUT` corresponden a `A8` (`0x30`), `A9` (`0x31`) y `A10` (`0x32`).
- [`prueba-vl53l0x/prueba-vl53l0x.ino`](prueba-vl53l0x/prueba-vl53l0x.ino): Lectura básica a ~10 Hz de un único VL53L0X fijo (~15–20 mm del piso) apuntando a canica de 16 mm, con cambio entre modo normal (`'n'`) y largo alcance (`'l'`).

### 1.4 Servos de barrido y servos 360° del picker
- [`prueba-servos/prueba-servos.ino`](prueba-servos/prueba-servos.ino): Calibración de los 2 servos SG90 de barrido en PCA9685 (`0` = izq, `1` = der): centro (`90°`), extremos (`0°`/`180°`), barrido completo y barrido en espejo del detector (`-45°` hacia el centro a `+60°` hacia afuera).
- [`prueba-servo-360/prueba-servo-360.ino`](prueba-servo-360/prueba-servo-360.ino): Calibración del punto neutro, sentido y velocidad (en µs) de los 2 servos SG90 modificados a 360° del picker en PCA9685 (`2` = izq, `3` = der).

### 1.5 Tracción y sistema de recolección (picker + estómago)
- [`prueba-traccion/prueba-traccion.ino`](prueba-traccion/prueba-traccion.ino): Prueba de los 4 motores DC en shield L293D (`M1..M4`) con control `WASD` (USB y HC-05 `Serial1` a 9600 bps, timeout de hombre muerto 450 ms), prueba individual `'1'..'4'` y ajuste `+/-` de velocidad.
- [`prueba-picker/prueba-picker.ino`](prueba-picker/prueba-picker.ino): Prueba histórica del motor DC con L298N (`ENA = D45`, `IN1 = D22`, `IN2 = D23`) previa a la adopción de los 2 servos 360° en PCA9685.
- [`picker-girando/picker-girando.ino`](picker-girando/picker-girando.ino): Giro continuo de los dos servos SG90 360° del picker (PCA9685 canales `2` y `3`) con neutro `{1485, 1500}` y sentido calibrados; control por serie (`'p'` frena, `'g'` gira).
- [`picker-estomago/picker-estomago.ino`](picker-estomago/picker-estomago.ino): Picker continuo (PCA9685 canales `2` y `3`, `VEL_US = {360, 300}`) + sensor VL53L0X interno del estómago (`XSHUT = A10`, dirección `0x32`). Se arma con `'a'` al entrar en `CAPTURA`, aprende la línea base actual y notifica una sola vez ante una variación grande de lectura.
- [`picker-destrabe/picker-destrabe.ino`](picker-destrabe/picker-destrabe.ino): Picker (PCA9685 canales `2` y `3`, neutro `{1485, 1500}`) con rutina de destrabe por oscilación rápida disparada al medir caída de tensión interna `Vcc` del Mega (aplica únicamente sin XL4015 aislado).

---

## 2. Bitácoras, fotos y notas técnicas

- [`PICKER-ESTOMAGO-Y-ALIMENTACION.md`](PICKER-ESTOMAGO-Y-ALIMENTACION.md): Bitácora técnica y pinout verificado del Arduino Mega, shield L293D (`M1..M4` e inversión), bus I2C (`D20`/`D21`), asignaciones `XSHUT` (`A8`/`A9`/`A10`), PCA9685, step-down XL4015 y detector de estómago.
- [`SIMULACION-UNITY.md`](SIMULACION-UNITY.md): Especificación geométrica y funcional de los sensores del frente medida sobre el chasis real (coordenadas, ángulos de barrido, regla de detección y mediciones de banco).
- [`bitacora-01-lidar-mapeo-y-vision.docx`](bitacora-01-lidar-mapeo-y-vision.docx): Bitácora de campo sobre pruebas iniciales de LiDAR, mapeo y visión artificial.
- [`bitacora-02-ultrasonido-vs-laser.docx`](bitacora-02-ultrasonido-vs-laser.docx): Bitácora comparativa de desempeño entre ultrasonido (HC-SR04) y láser ToF (VL53L0X).
- [`fotos_auto/`](fotos_auto/): Registro fotográfico del prototipo real (`01-vista-3-cuartos.jpg`, `02-vista-superior.jpg`, `03-lateral-servo-y-laser.jpg`, `04-frente.jpg`).
- [`fotos_pista/`](fotos_pista/): Fotografías de la pista/bahía y carpeta [`fotos_pista/pruebas/`](fotos_pista/pruebas/) con capturas de pruebas de suelo y segmentación.

---

## 3. Simulaciones matemáticas en Python (`pruebas/simulacion/`)

Scripts de modelado físico-acústico (HC-SR04) y geométrico-óptico (VL53L0X) utilizados para definir alturas, ángulos e inclinaciones de montaje de los sensores frontales:

### 3.1 Modelos base y validación
- [`simulacion/acustica.py`](simulacion/acustica.py): Modelo acústico de un HC-SR04 por óptica física (Kirchhoff) con directividad a 40 kHz, rebote especular en piso, dispersión difusa de Lambert, absorción en aire y umbral del primer eco.
- [`simulacion/sim_laser.py`](simulacion/sim_laser.py): Modelo del VL53L0X según [`SIMULACION-UNITY.md`](SIMULACION-UNITY.md) (promedio ponderado en el cono por reflectividad, coseno de incidencia, sensibilidad angular y $1/d^2$).
- [`simulacion/validar_modelo.py`](simulacion/validar_modelo.py): Chequeos unitarios del modelo acústico contra casos con resultado analítico conocido.

### 3.2 Simulaciones y análisis de ultrasonido (HC-SR04)
- [`simulacion/sim_altura_us.py`](simulacion/sim_altura_us.py): Evaluación de altura $H$ para los 3 HC-SR04 frontales (obstáculo 6×6 cm de alto mínimo 6 cm vs. rechazo de canica).
- [`simulacion/sim_altura_us_detalle.py`](simulacion/sim_altura_us_detalle.py): Barrido detallado por inclinación (`0°`, `-2°`, `+2°`), altura y `yaw` (`0°`, `30°`) de los 3 HC-SR04.
- [`simulacion/sim_escenas.py`](simulacion/sim_escenas.py): Simulación de escenas completas (matriz 3×2 de canicas y obstáculos aleatorios, modos `demo` y `montecarlo`) replicando la lógica de detección del Arduino.
- [`simulacion/analisis_alto.py`](simulacion/analisis_alto.py): Análisis del sensor superior (altura mínima de obstáculo detectable sin confundirlo con canica).
- [`simulacion/analisis_lateral.py`](simulacion/analisis_lateral.py): Vista lateral del alcance sobre canica y aparición de eco de piso según altura e inclinación.
- [`simulacion/analisis_placa.py`](simulacion/analisis_placa.py): Comparación de distribuciones de sensores posibles sobre placa de 155×100 mm (sensores acostados vs. parados).
- [`simulacion/analisis_inclinacion.py`](simulacion/analisis_inclinacion.py): Efecto de inclinar la fila superior y ajustar la separación entre columnas (5,4 cm) en la configuración con abanico de 30°.
- [`simulacion/analisis_detalle.py`](simulacion/analisis_detalle.py): Desglose del Montecarlo para identificar qué obstáculos generan falsos positivos y por qué se pierden canicas reales.
- [`simulacion/mapa_cobertura.py`](simulacion/mapa_cobertura.py): Mapa 2D de cobertura vista desde arriba y corte lateral (`resultados/cobertura.png`, `resultados/lateral.png`).

### 3.3 Ajuste y barrido de láser ToF (VL53L0X)
- [`simulacion/fit_laser.py`](simulacion/fit_laser.py): Ajuste de parámetros del modelo VL53L0X ($\sigma$, exponente de coseno, reflectividad) contra mediciones de banco; guarda `mejor_ajuste.npy`.
- [`simulacion/barrido_laser.py`](simulacion/barrido_laser.py): Cálculo de rangos de detección de canica (cm) para distintas alturas e inclinaciones ajustadas a lectura de reposo ~40 cm.

### 3.4 Salidas generadas
- Datos y reportes de texto: [`simulacion/mejor_ajuste.npy`](simulacion/mejor_ajuste.npy), [`simulacion/resultados_altura_us.txt`](simulacion/resultados_altura_us.txt), [`simulacion/resultados_detalle.txt`](simulacion/resultados_detalle.txt), [`simulacion/resultados_montecarlo.txt`](simulacion/resultados_montecarlo.txt), [`simulacion/resultados_placa.txt`](simulacion/resultados_placa.txt).
- Gráficos: [`simulacion/resultados/cobertura.png`](simulacion/resultados/cobertura.png), [`simulacion/resultados/lateral.png`](simulacion/resultados/lateral.png).
