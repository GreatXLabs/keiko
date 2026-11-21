# Archivo Histórico del Proyecto Orquitas (Keiko)

Este directorio conserva código, documentación y experimentos previos que ya no forman parte del stack activo (`Arduino Mega 2560 + L293D + PCA9685 + 3 HC-SR04 + 2 VL53L0X + estómago VL53L0X + doble red Neuroph 2.98`), para mantener trazabilidad si el equipo necesita consultar algún antecedente.

---

## Índice de Contenidos

### 1. `archive/firmware-uno/`
- `orquita_firmware_uno.ino`: Primer firmware del robot basado en **Arduino UNO R3 + Driver L298N + 4 HC-SR04** (38400 bps en D0/D1, sensor bucal digital en D2). Reemplazado por `firmware/orquita_firmware/orquita_firmware.ino` (Arduino Mega 2560).
- `circuito_electronico_uno.md`: Diagrama de cableado y BOM original para Arduino UNO + L298N. Reemplazado por `pruebas/PICKER-ESTOMAGO-Y-ALIMENTACION.md`.

### 2. `archive/sensores-legacy/`
- `configuracion_sensores_ia.md`: Especificación previa de doble plano coaxial con 4/6/8/10 sensores ultrasónicos HC-SR04.
- `ESTUDIO_SENSORES_CONFIGURACIONES.md`: Comparativa técnica entre arreglos puramente ultrasónicos (previa a la adopción del arreglo mixto `3 US + 2 Láser ToF`).

### 3. `archive/hitos/`
- `HITO3/`: Carpeta de entrega académica del Hito 3 (incluye `DCU - Keiko.md`, `VCU - SQs - Clases.drawio.xml` y una copia histórica estática en `codigo/*.java` que no debe editarse; el código vivo reside en `keiko-server/`).
- `GUIA_MODIFICACION_DOCUMENTACION_HITO2.md`: Guía de ajustes documentales de la segunda entrega académica.

### 4. `archive/pruebas-experimentales/`
Experimentos de hardware/percepción descartados o superados durante la exploración de campo:
- `detector-6-ultrasonidos/`: Prototipo con matriz 3x2 de 6 HC-SR04 (descartado por crosstalk acústico y menor resolución sobre canicas de 16 mm frente a VL53L0X).
- `lidar_scanner/`: Barrido 2D de un VL53L0X en PCA9685 con visualización en tiempo real (`live_plot.py`, `map_plot.py`, `room_scan.py`, `scan_match.py`, `classify_live.py`, `test_icp.py`). Útil como referencia de ICP/nube de puntos; superado por el barrido dual en espejo (`detector-mixto`).
- `vision/` (`marble_pipeline.py`): Pipeline experimental OpenCV de detección de canicas por cámara de piso; descartado del robot final por restricciones de cómputo y hardware.
- `marble_test/`: Test inicial aislado de lectura de VL53L0X con servo sobre PCA9685; reemplazado por `pruebas/prueba-vl53l0x/` y `pruebas/detector-mixto/`.
- `prueba-trabado-vcc/`: Experimento para detectar atasco del picker midiendo caída de Vcc interna del Mega (solo funciona si los servos comparten 5V del Mega; no aplica cuando el picker se alimenta con regulador externo XL4015).
