# Changelog — Proyecto Orquitas (Keiko)

Todas las modificaciones relevantes de arquitectura, firmware, servidor Java, simulador Webots, modelos `.nnet` y protocolo se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones sincronizadas mediante `VERSION` + [`scripts/check_versions.sh`](scripts/check_versions.sh).

---

## [2.0.9-entrega-final] — 2026-11-25 (`release/entrega-final`)

### Changed / Cleaned (Versión Limpia para Entrega Final / Competencia)
- **Eliminación integral de scaffolding de pruebas, debug y entrenamiento en vivo (consigna PSR/DS + DCU):**
  - **Firmware ([`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)):** retirados `PERMITIR_MANUAL`, `DEPURAR_ESTOMAGO`, `ESPEJAR_USB`, `CMD:PWM`, `LASER:`, `PICKER:ON/OFF`, control manual `wasdkx` y mensajes `DBG:senal=`; congelados `PWM_ZONA_MUERTA = 105` y `PWM_TOPE = 255`.
  - **Servidor Central (`keiko-server`):** eliminadas las clases `AsistenteEntrenamientoGuiado`, `GrabadorDatasetReal`, `GestorModelosYEntrenamiento` y su suite asociada; retirados todos los flags CLI y atajos interactivos de calibración/debug/entrenamiento (`1-3`, `p`, `b`, `+/-`, `[/]`, `</>`, `w`, `u/m/g`, `t`, `--speed-scale`, `--pwm-*`, `--reverse-steps`, `--retrain-real`, `--list-models`, `--use-model`).
  - **Constantes operativas congeladas ([`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java)):** `ESCALA_VELOCIDAD_FIJA = 0.40`, `PWM_MIN_FIJO = 105`, `PWM_MAX_FIJO = 255`, `PASOS_REVERSA_FIJO = 4`.
  - **TUI de solo lectura operativa ([`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java), [`EstadoMonitorTui.java`](keiko-server/src/main/java/com/orquitas/server/EstadoMonitorTui.java)):** retirado el panel de dataset/wizard y métricas `DBG`, conservando monitoreo en vivo (`SISTEMA & ENLACE`, `CEREBRO`, `ESTOMAGO` con `#1..#3`, `SENSORES` / Neuroph y logs) y controles mínimos de sesión (`[k]Start [x]Stop [r]Reset(rep) [v]Vista [c]Log [q]Salir`, bloqueando `reset` durante corrida activa).
  - **Trazabilidad DCU (`RF-01..RF-08`) y estado `ERROR_SEGURO` ([`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java), [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java)):** ante corte inesperado de Bluetooth en corrida pasa transitoriamente a `ERROR_SEGURO` (`(0.0, 0.0)`) y al reconectar restaura `ESCAPE` o `BUSQUEDA` leyendo `estomago.txt`.
  - **Limpieza de rama:** retirados `keiko-sim/`, `neuroph-train/`, `pruebas/` y `archive/` para entregar exclusivamente código fuente Java limpio, `.nnet`, firmware `.ino` y `docs/`.

### Tests Run (Evidencia `release/entrega-final` — 2026-11-25)
- `cd keiko-server && mvn clean test`: **58/58 OK** (`0 Failures, 0 Errors, 0 Skipped`; 9 clases JUnit 5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `./scripts/check_versions.sh`: **5/5 `[OK]`** (`2.0.9 | mega-3us-2laser`).

---

## [2.0.9] — 2026-11-25

### Changed / Improved
- **Barrido ToF acelerado para completar ciclos mientras el robot pausa ([`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)):**
  - `BARRIDO_PASO = 2` (`2°` cada `28 ms`), `LASER_ASENTAR_MS = 8`, `TOF_PERIODO_MS = 25`: reduce cada media pasada `-45°..+60°` de `~3.15 s` a **`~1.48 s`** (`~3.0 s` ida y vuelta) manteniendo `3–5` impactos por canica en cada barrido.
- **Navegación pulsada + centrado en el lugar + anti-loop de pared paralela ([`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java)):**
  - **Exploración pulsada en `BUSQUEDA`:** alterna `4` ciclos de avance (`0.4 s`) y `6` ciclos de freno `(0.0, 0.0)` (`0.6 s`) sobre `ESPACIO_LIBRE`, evitando que el robot pase de largo por inercia sobre `pwmZonaMuerta=105` y dejando al láser completar lecturas en reposo.
  - **Centrado suave antes de avanzar en `APROXIMACION`:** ante `LOBITO_IZQ` / `LOBITO_DER`, pivota suave en el lugar (`(-2.4, 2.4)` / `(2.4, -2.4)` en ráfagas `2` ON / `4` OFF) hasta alinear `LOBITO_CENTRO`; recién al centrarla avanza recto despacio (`(2.6, 2.6)` en ráfagas `3` ON / `3` OFF) y mantiene persistencia de `12` ciclos (`1.2 s`).
  - **Corte simbólico contra falso `LOBITO` en pared paralela:** si tras `MAX_CICLOS_CENTRADO_SIN_CENTRO = 26` ciclos (`2.6 s`) de intentar centrar no aparece `LOBITO_CENTRO`, ejecuta un giro breve alejándose del lado sospechoso (`PASOS_GIRO_DESPEGUE_FALSO_LOBITO = 6`) y activa `COOLDOWN_FALSO_LOBITO_CICLOS = 25` (`2.5 s`) ignorando falsas alertas hasta despegarse de la pared.
- **Asistente Guiado `[w]` ampliado a 32 poses para US laterales curvados hacia afuera y paredes diagonales/paralelas ([`AsistenteEntrenamientoGuiado.java`](keiko-server/src/main/java/com/orquitas/server/AsistenteEntrenamientoGuiado.java), [`GrabadorDatasetReal.java`](keiko-server/src/main/java/com/orquitas/server/GrabadorDatasetReal.java), [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):**
  - Agregado `GrabadorDatasetReal.CanalUs` (`CEN`, `IZQ`, `DER`): los nuevos pasos **9–12** de Red 1 graban directamente las lecturas de los sensores **`US-IZQ` y `US-DER` curvados hacia afuera** en pared diagonal (`~12 cm` → `BLOQUEO`) y espacio abierto (`~45 cm` → `ESPACIO_LIBRE`).
  - Red 2 (`CANICAS`): reduce los pasos `NINGUNA` a `50` muestras (`5.0 s`, cubriendo `>1.6` barridos completos con el nuevo paso de `2°`) e incorpora 5 poses críticas nuevas (**22–26**): `Paralela pegada IZQ/DER (3–6 cm)` (US lejos + láser lateral corto), `Diagonal extrema IZQ/DER (~60°)` (rebote diagonal sin canica) y `Frente a Salida sin canica`.

### Tests Run (Evidencia `2.0.9` — 2026-11-25)
- `cd keiko-server && mvn clean test`: **68/68 OK** (`0 Failures, 0 Errors, 0 Skipped`; 11 clases JUnit 5, incluye `testAvancePulsadoYCentradoSuaveConCooldownFalsoLobito` y validación 32 poses + `CanalUs`).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `python3 -m unittest discover -s keiko-sim/tests -v`: **33/33 OK** (`7.938 s`, incluye carga headless Webots).
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.9 | mega-3us-2laser`).

---

## [2.0.8] — 2026-11-25

### Fixed (Corregido)
- **Log automático de sesión vacío con la TUI activa ([`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):**
  - **Qué:** el `FileHandler` de `logs/corrida_<timestamp>.log` se registra en el logger del paquete `com.orquitas.server` en lugar del logger raíz.
  - **Por qué:** `ConsolaTui` desactiva la propagación al raíz (`setUseParentHandlers(false)`) para pintar los logs en su panel, por lo que con TUI el archivo quedaba vacío salvo la línea de cierre. Ahora el archivo recibe todos los mensajes del servidor (incluido `[NAV]`) con o sin TUI.
  - El archivo sigue siendo uno por sesión del servidor (abre al iniciar, cierra con `q`/Ctrl+C), no por corrida `[k]`→`[x]`.
- Sin cambios de protocolo ni de firmware más allá del bump de versión (`2.0.7` y `2.0.8` son compatibles por `major.minor`).

### Tests Run (Evidencia `2.0.8` — 2026-11-25)
- `cd keiko-server && mvn test`: **67/67 OK** (0 Failures, 0 Errors, 0 Skipped).
- `cd keiko-server && mvn package`: **`BUILD SUCCESS`**.
- Prueba manual con TUI activa en pseudo-terminal (`script -qc "java -jar target/keiko-server.jar ..."`): `logs/corrida_*.log` registra `[HILO AUDITOR INICIADO]` y `[SERIE] Iniciando escucha...`, emitidos después de iniciar la TUI (en `2.0.7` el archivo solo contenía `[SERIE] Receptor serie detenido.`).
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.8 | mega-3us-2laser`).

---

## [2.0.7] — 2026-11-25

### Changed / Improved
- **Detector de estómago no bloqueante por señal de retorno VL53L0X ([`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)):**
  - Reemplaza las lecturas bloqueantes en mm por lectura directa del registro `0x14` (`estomagoSenal()`, tasa de retorno en MCPS cada `~20 ms`, sin bloquear el `loop()`).
  - La canica de vidrio casi no desplaza la distancia medida pero produce una variación de `30–60%` en la señal de retorno respecto de la línea base (`MUESTRAS_BASE = 10`, `UMBRAL_SENAL_PCT = 20`, `LECTURAS_CONFIRMAR = 1`, cooldown `3000 ms`), resolviendo omisiones y dobles conteos en pista.
  - Emite `DBG:senal=<mcps> base=<mcps> det=<n> cambios=<c>` y reduce `PICKER_EXTRA_MS` de `4000` a **`2500 ms`** para evitar acumular `PICKER_TOPE_10S` en aproximaciones consecutivas.
  - Agrega guarda `if (corrida) return;` en `empezarCorrida()` para evitar reinicios de estado si llega un `CMD:START` duplicado.
- **Soporte de `DBG:senal=` y ajuste de ancho de TUI a `106` columnas ([`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java), [`EstadoMonitorTui.java`](keiko-server/src/main/java/com/orquitas/server/EstadoMonitorTui.java), [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java)):**
  - `parsearDbgEstomago()` y la TUI soportan tanto `DBG:senal=<mcps> base=<mcps>` (`LSR-EST : 14.5mcp (b:15.0 d:1) [ARM]`) como el formato heredado `DBG:estomago=<mm>`.
  - Reducido `ANCHO_DETALLADO` de `112` a **`106` columnas** (`64 + 1 + 41`) para encajar sin recortes en paneles divididos (~108 cols) manteniendo todas las métricas y barras adaptativas; `detectarTamanoTerminal()` consulta `stty size </dev/tty` antes de `stty size` / `tput` / `$COLUMNS`.
- **Ajustes de navegación en pista real ([`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java)):**
  - Acortado el barrido de exploración `(contadorCiclos / 22) ± 0.25` (antes `/ 75 ± 0.45`) para cruzar el centro de la Bahía en lugar de orbitar muros.
  - Aumentado `DURACIONES_GIRO_ASIMETRICO` a `{13, 17, 21}` ciclos y reducido `UMBRAL_EPISODIOS_MISMO_LADO` de `4` a **`3`** para despegarse antes y con mayor ángulo cuando roza paredes en ángulos agudos.
  - Endurecida la evasión de `SALIDA` lateral en `BUSQUEDA` con pivote firme `(3.8, -2.2)` / `(-2.2, 3.8)` (antes arco suave `3.8, 2.2`) para evitar enganches en el cartón de salida y egresos accidentales.
  - Aumentada `PERSISTENCIA_LOBITO_CICLOS` de `5` a **`10` ciclos (`1.0 s`)** y suavizadas las velocidades de `APROXIMACION` (`(2.8, 2.8)`, `(1.8, 3.0)`, `(3.0, 1.8)`) para reducir empuje accidental de canicas manteniendo intactas Red 1 (solo 1 entrada ultrasónica) y la prioridad `SALIDA > BLOQUEO > LOBITO`.
- **Robustez Bluetooth SPP y trazabilidad automática de corridas ([`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java), [`ReceptorSerie.java`](keiko-server/src/main/java/com/orquitas/server/ReceptorSerie.java), [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):**
  - `marcarStartManual()` suprime un `CMD:START (auto)` redundante si el operador ya arrancó manualmente con `[k]`/`start`.
  - `ReceptorSerie` aplica backoff (`1500 -> 3000 -> 5000 ms`) cuando un puerto `/dev/rfcomm0` fantasma abre y cierra con `0` tramas válidas.
  - `ServidorCentral.iniciar()` registra automáticamente los logs de la sesión en `logs/corrida_<timestamp>.log`.

### Tests Run (Evidencia `2.0.7` — 2026-11-25)
- `cd keiko-server && mvn clean test`: **67/67 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `python3 -m unittest discover -s keiko-sim/tests -v`: **33/33 OK** (`8.375 s`, incluye carga headless Webots).
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.7 | mega-3us-2laser`).
- Firmware `.ino`: `arduino-cli` no está instalado en el host actual; requiere verificación/flasheo en Arduino IDE antes de pista.

---

## [2.0.6] — 2026-11-24

### Fixed (Corregido — Paso C: Prioridad de Evasión sobre Persecución)
- **Prioridad estricta `SALIDA > BLOQUEO > LOBITO` en [`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java):**
  - En `BUSQUEDA`, evalúa resguardos de `SALIDA` y evasión de `BLOQUEO` (frontal o lateral) **antes** que `evaluarLobito()`, evitando que el robot atropelle muros frontales/laterales cuando otro sector detecta una canica.
  - Cualquier detección de `BLOQUEO` o `SALIDA` limpia de inmediato `ciclosPersistenciaLobito` y evita que `LOBITO` aborte maniobras comprometidas de retroceso/giro.
  - Reducida la persistencia sin visión directa de canica de `12` (`1.2 s`) a **`5` ciclos (`0.5 s`)**.
  - Mantiene intacta Red 1 (`modelo_ultrasonico.nnet`, `1` entrada HC-SR04 sin mezcla láser).
- **Prueba unitaria agregada ([`PlanificadorNavegacionTest.java`](keiko-server/src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java)):** `testBloqueoYSalidaSuperanALobitoYPersistenciaEsCorta` (`66` tests Java en total).

### Tests Run (Evidencia `2.0.6` — 2026-11-24)
- `cd keiko-server && mvn clean test`: **66/66 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `python3 -m unittest discover -s keiko-sim/tests -v`: **33/33 OK**.
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.6 | mega-3us-2laser`).

---

## [2.0.5] — 2026-11-24 (`#28`, Mergeado en `main`)

### Fixed (Corregido — Paso B: Calidad de Datos y Captura Guiada)
- **Sesión limpia al iniciar `[w]` ([`GrabadorDatasetReal.java`](keiko-server/src/main/java/com/orquitas/server/GrabadorDatasetReal.java), [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):** `archivarYReiniciarSiTieneDatos()` mueve automáticamente los CSV previos a `data/real/archivo/<timestamp>/` y reinicia conteos en `0` antes de comenzar una pasada del asistente, evitando mezclar grabaciones viejas/corruptas.
- **Filtro anti-vacío exclusivo en captura guiada `LOBITO_*` ([`GrabadorDatasetReal.java`](keiko-server/src/main/java/com/orquitas/server/GrabadorDatasetReal.java), [`AsistenteEntrenamientoGuiado.java`](keiko-server/src/main/java/com/orquitas/server/AsistenteEntrenamientoGuiado.java), [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java)):** durante los pasos `LOBITO_IZQ/CENTRO/DER`, el grabador CSV descarta los ángulos del barrido `-45°..+60°` que miran al fondo vacío sin caída láser (`< min(usFondo - 5, 37) cm` en el ángulo coherente), mostrando `(vacio:N)` en la TUI y guardando únicamente los `12` impactos reales sobre la canica. Corrige falsos positivos masivos (`78–80%` `LOBITO` en aire libre).
- **Cobertura real ampliada a 23 pasos ([`AsistenteEntrenamientoGuiado.java`](keiko-server/src/main/java/com/orquitas/server/AsistenteEntrenamientoGuiado.java)):** agregados `Espacio abierto (~40 cm)`, `Pared paralela IZQ (~8 cm)` y `Pared paralela DER (~8 cm)` en `NINGUNA`, y ajustadas las 6 poses `LOBITO_*` al rango físico real efectivo de los VL53L0X (`8–15 cm` simétricos IZQ/CEN/DER).

### Tests Run (Evidencia `2.0.5` — 2026-11-24)
- `cd keiko-server && mvn clean test`: **65/65 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5, validando filtro anti-vacío y auto-archivo).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `python3 -m unittest discover -s keiko-sim/tests -v`: **33/33 OK**.
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.5 | mega-3us-2laser`).

---

## [2.0.4] — 2026-11-24 (`#27`, Mergeado en `main`)

### Added (Agregado)
- **Asistente de Entrenamiento Guiado paso a paso en la TUI ([`AsistenteEntrenamientoGuiado.java`](keiko-server/src/main/java/com/orquitas/server/AsistenteEntrenamientoGuiado.java), [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java), [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):**
  - Tecla **`[w]`** (`wizard`): detiene el robot por seguridad (`CMD:STOP`) y guía en **21 poses reales** (`1045` muestras totales):
    - Red 1 (`ULTRASONICO`, `LASER:0`, `25` muestras/paso): paredes a `~5`, `12`, `~20 cm`, cubo `6×6 cm`, libre corto `~35`, medio `~75`, largo `~115 cm` (frontera previa a salida) y salida `> 135 cm`.
    - Red 2 (`CANICAS`, `LASER:SWEEP`, **`65` muestras/paso = ~6.5 s** para garantizar 1 barrido completo `-45°..+60°` ida y vuelta): pista vacía, pared recta `~12 cm`, diagonales izquierda y derecha (`~35°`, `12–20 cm`), esquina y cubo `6×6 cm` como `NINGUNA`; más canicas de vidrio `CENTRO` (`~6`, `~13`, `~21 cm`), `IZQ` (`8–12`, `16–20 cm`) y `DER` (`8–12`, `16–20 cm`).
  - Auto-selecciona la etiqueta objetivo (`ULTRASONICO` / `CANICAS`), configura servos (`LASER:0` / `LASER:SWEEP`), graba al pulsar **`[ESPACIO]`** (o `[g]`) con barra de progreso en vivo, detiene la captura y avanza solo al llegar a la meta.
  - Soporta navegación manual (`[n]` siguiente, `[a]` anterior) y pantalla final `21/21 COMPLETADO` invitando a reentrenar con `[t]`.
- **Wiki visual actualizada en [`keiko-server/README.md`](keiko-server/README.md):** captura ASCII `v2.0.4`, vista del Asistente Guiado `[w]` y tabla completa de los 21 pasos en pista.
- **Pruebas unitarias (`65` total):** `ConsolaTuiTest#testRenderizadoAsistenteEntrenamientoGuiadoAlineado` y `ServidorCentralControlTest#testModoEntrenamientoGuiadoWizardPasoAPaso`.

### Fixed (Corregido)
- **Sincronización visual de pausa automática ([`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java), [`GrabadorDatasetReal.java`](keiko-server/src/main/java/com/orquitas/server/GrabadorDatasetReal.java)):** `ManejadorCliente` consulta `grabadorDataset.isGrabando()` tras ejecutar el callback `onMuestraRegistrada` para reflejar la pausa automática apenas se alcanza la meta del paso.

### Tests Run (Evidencia `2.0.4` — 2026-11-24)
- `cd keiko-server && mvn clean test`: **65/65 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `python3 -m unittest discover -s keiko-sim/tests -v`: **33/33 OK** (incluyendo carga headless Webots).
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.4 | mega-3us-2laser`).

---

## [2.0.3] — 2026-11-24 (`#26`, Mergeado en `main`)

### Added (Agregado)
- **Manual Visual de la TUI y Guía de Entrenamiento en Pista ([`keiko-server/README.md`](keiko-server/README.md), [`AGENTS.md`](AGENTS.md)):** captura visual de referencia `v2.0.3`, glosario completo de paneles/métricas (`LSR-EST`, `DBG Mega`, `TERNA`, `Rev`, `REC`) e instructivo paso a paso para verificación de servos, captura de datos reales (`999.0` sin eco / canica vidrio), reentrenamiento y rollback.
- **Ergonomía de grabación real ([`GrabadorDatasetReal.java`](keiko-server/src/main/java/com/orquitas/server/GrabadorDatasetReal.java), [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):** al pausar `[g]` informa cantidad exacta de muestras añadidas y archivo CSV objetivo; al pulsar `[t]` sin muestras reales avisa claramente sin fallar.
- **Prueba de aprendizaje real en banda `~25 cm` ([`RealDatasetAndRetrainTest.java`](keiko-server/src/test/java/com/orquitas/server/RealDatasetAndRetrainTest.java)).**

### Changed (Modificado)
- **Umbral `SALIDA` ajustado a `> 130 cm` en Red 1 ([`generate_datasets.py`](keiko-sim/scripts/generate_datasets.py), [`modelo_ultrasonico.nnet`](keiko-server/models/modelo_ultrasonico.nnet)):** actualizadas bandas `ESPACIO_LIBRE` (`19.5–122.0 cm`) y `SALIDA` (`132.0–250.0 cm`) con reentrenamiento en `neuroph-train` (**`99.28%` accuracy**, F1 `0.9928`, sincronizado en las 3 rutas del repo, SHA-256 `44b112eaa1a7...`) y actualización de [`TwoNetworksInferenceTest.java`](keiko-server/src/test/java/com/orquitas/server/TwoNetworksInferenceTest.java).
- **Reentrenamiento fiel de canicas reales (`~22–28 cm`) ([`GestorModelosYEntrenamiento.java`](keiko-server/src/main/java/com/orquitas/server/GestorModelosYEntrenamiento.java)):** al combinar datos reales con base sintética de Red 2, excluye la banda negativa sintética `laser_medio` (`22.0–35.5 cm = NINGUNA` con `US >= 30 cm`) para que las canicas reales de vidrio aprendan su patrón sin contradicción, conservando piso limpio, muros cercanos y `999.0`.
- **Pulido visual en [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java):** ajuste de ancho en barra láser (`12 + extraLas`) para evitar truncamiento `...` a `112–116` cols y compactación de la barra `Teclas:` para que entren todos los atajos incluyendo `[v/c/q]`.

### Tests Run (Evidencia `2.0.3` — 2026-11-24)
- `cd keiko-server && mvn clean test`: **63/63 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `python3 -m unittest discover -s keiko-sim/tests -v`: **33/33 OK** (incluyendo carga headless Webots).
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.3 | mega-3us-2laser`).

---

## [2.0.2] — 2026-11-24 (`#25`, Mergeado en `main`)

### Added (Agregado)
- **Pasos de reversa ajustables en vivo desde TUI (`// [SOLO PRUEBAS / DEBUG]`):** [`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java) (`--reverse-steps`, env `REVERSE_STEPS`, default `4` = `0.4 s` a `10 Hz`), [`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java) (`setPasosReversa(0..15)`), [`EstadoMonitorTui.java`](keiko-server/src/main/java/com/orquitas/server/EstadoMonitorTui.java), [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java) (`Rev:Np`) y [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java) (teclas `>`/`.`, `<`/`,` y `rev <n>`). Con `0` pasos salta el retroceso y pivota directamente.

### Changed (Modificado)
- **Modo `ESCAPE` sin detección de canicas ([`ClasificadorNeuroph.java`](keiko-server/src/main/java/com/orquitas/server/ClasificadorNeuroph.java), [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java)):** `clasificarPares(sensores, false)` omite Red 2 cuando `planificador.getEstado()` es `ESCAPE` o `FINALIZACION` para que ninguna canica reemplace sectores en la terna. En `BUSQUEDA` se mantiene `SALIDA > LOBITO`.
- **Pivote sin reversa frente a `SALIDA` y giros anti-órbita ([`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java)):** en `BUSQUEDA`, frente a `SALIDA` sin `BLOQUEO` ejecuta **0 pasos de reversa** y pivota en el lugar alternando sentido y duraciones `{8, 11, 14}` pasos, abortando el pivote de inmediato si aparece `BLOQUEO` al frente; en `BLOQUEO` real baja el retroceso base de `10`/`12` pasos a `4`/`6` pasos (`0.4 s` / `0.6 s`).

### Tests Run (Evidencia `2.0.2` — 2026-11-24)
- `cd keiko-server && mvn clean test`: **62/62 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.2 | mega-3us-2laser`).

---

## [2.0.1] — 2026-11-24 (`#24`, Mergeado en `main`)

### Added (Agregado)
- **Script automatizado de versionado ([`scripts/bump_version.sh`](scripts/bump_version.sh)):** sincroniza en un solo comando `VERSION`, `keiko-server/pom.xml`, `ConfiguracionServidor.java`, `orquita_firmware.ino`, `simulation_params.json`, `robot_client.py`, `README.md` y `AGENTS.md`, validando `9/9 [OK]` con [`check_versions.sh`](scripts/check_versions.sh).
- **Validación estricta de protocolo de 7 campos ([`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java), [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java)):** `parsearTelemetria(linea, 6, true)` rechaza tramas mutiladas por Bluetooth de 6 tokens evitando que el timestamp pase al campo `angulo_servo`.
- **Comando `laser <-45..60>` en consola ([`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):** permite fijar ángulo directo para validar servos en banco.

### Fixed (Corregido)
- **Sentido físico invertido de servos láser en [`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino):** actualizado `LASER_SENTIDO[2] = {-1, 1}` (antes `{1, -1}`) tras validar en el robot real que `-45°` apuntaba hacia afuera; ahora `-45°` converge hacia el centro y `+60°` abre hacia afuera igual que Webots y `modelo_canicas.nnet`.
- **Desbordes del buffer TX `Serial1` @ `9600` bps en [`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino):** `enviarTelemetria()` emite como máximo una trama por vuelta del `loop()` (`TELEMETRY:` / `HEARTBEAT` / `DBG:`) verificando `availableForWrite()`, evitando tramas cortadas al principio/medio.
- **Desbordes verticales y de márgenes en [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java):** cálculo exacto `alto - lineas.size() - 4` (incluyendo los 2 renglones de captura real y la fila de prompt dentro de `altoTerm`), cambio automático a modo compacto en terminales angostas/bajas (`< 112` cols o `< 32` filas), barra `Teclas:` acotada al ancho sin wrap y espaciado limpio en `LSR-IZQ` / `LSR-DER`.

### Tests Run (Evidencia `2.0.1` — 2026-11-24)
- `cd keiko-server && mvn clean test`: **60/60 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5, con pruebas de no-desborde `112x34`, `120x39`, `150x44`, `95x28`, `84x24` y rechazo estricto de tramas truncadas).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.1 | mega-3us-2laser`).

---

## [PR-C — 2026-11-23] Captura Real CSV (`999.0` sin eco), Reentrenamiento Neuroph 2.98, Historial `.nnet` y Fix `LASER:SWEEP` (`#23`, Mergeado en `main`)

### Added (Agregado)
- **Grabación guiada de datasets reales desde la TUI ([`GrabadorDatasetReal.java`](keiko-server/src/main/java/com/orquitas/server/GrabadorDatasetReal.java), [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java)):** teclas `[u]` (cicla etiquetas US `BLOQUEO`/`ESPACIO_LIBRE`/`SALIDA`), `[m]` (cicla etiquetas Canicas `LOBITO_IZQ`/`LOBITO_CENTRO`/`LOBITO_DER`/`NINGUNA`) y `[g]` (`REC ON/PAUSA`) graban ráfagas reales en marcha o en reposo hacia `data/real/dataset_ultrasonico_real.csv` y `dataset_canicas_real.csv`, preservando lecturas `999.0` reales (sin eco) y mostrando conteos por clase en [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java).
- **Reentrenamiento integrado Neuroph 2.98, backup inmutable y rollback en caliente ([`GestorModelosYEntrenamiento.java`](keiko-server/src/main/java/com/orquitas/server/GestorModelosYEntrenamiento.java), [`ClasificadorNeuroph.java`](keiko-server/src/main/java/com/orquitas/server/ClasificadorNeuroph.java), [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):** tecla `[t]` / `--retrain-real` respalda los `.nnet` previos en `models/historial/<id>/` (con `index.tsv`, SHA-256, accuracy y matriz de confusión), entrena MLP (`1→8→3` / `6→32→16→4`) combinando base sintética balanceada + muestras reales con la normalización oficial compartida y recarga [`ClasificadorNeuroph`](keiko-server/src/main/java/com/orquitas/server/ClasificadorNeuroph.java) en vivo; `--list-models` y `--use-model <id>` (`use-model <id>` en consola) permiten rollback instantáneo.
- **Suite de pruebas PR-C ([`RealDatasetAndRetrainTest.java`](keiko-server/src/test/java/com/orquitas/server/RealDatasetAndRetrainTest.java)).**

### Fixed (Corregido)
- **`LASER:SWEEP` bloqueado en reposo tras una corrida previa en `ESCAPE` ([`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)):** `terminarCorrida()` (`CMD:STOP`) no restablecía `estado` a `"BUSQUEDA"` y `atenderBarrido()` seguía bloqueado por `estadoFinal()` aun con `barridoEnReposo == true`. Se restablece `estado` en `terminarCorrida()`, se prioriza `barridoEnReposo` en reposo y se emite `ERROR:PCA9685_NO_RESPONDE` al pedir `LASER:SWEEP` si `!pcaOk`.

### Tests Run (Evidencia PR-C — 2026-11-23)
- `cd keiko-server && mvn clean test`: **60/60 OK** (0 Failures, 0 Errors, 0 Skipped; 11 clases JUnit 5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.0 | mega-3us-2laser`).

---

## [PR-B — 2026-11-23] TUI 1-Tecla, Layout Fluido Ancho Completo y Modos Forzados (`#22`, Mergeado en `main`)

### Added (Agregado)
- **Entrada instantánea de 1 tecla sin `Enter` ([`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java), [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)):** modo TTY crudo POSIX (`stty -icanon -echo min 1 time 0 </dev/tty`, con restauración garantizada al cerrar/SIGTERM/`q` y fallback automático a lectura por línea en Windows/pipes) más filtrado de secuencias de escape CSI (`ESC [`). Atajos directos: `k`/`x`, `+`/`=`/`-`/`_`, `[`/`]`, `1`/`2`/`3`, `p`, `b`, `r`, `v`, `c`, `q`.
- **Modos forzados y pruebas aisladas de banco ([`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java), [`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java), [`EstadoMonitorTui.java`](keiko-server/src/main/java/com/orquitas/server/EstadoMonitorTui.java)):** `1` (`BUSQUEDA`), `2` (`ESCAPE`), `3` (`CAPTURA` en corrida o toggle `PICKER:ON`/`OFF` en reposo) y `p` (`PICKER:ON`/`OFF`) mediante tramas `STATE_CHANGE:<estado>` y `PICKER:ON|OFF`, todo marcado explícitamente `// [SOLO PRUEBAS / DEBUG]`.
- **Layout fluido de ancho y alto completos ([`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java)):** autodetección periódica de terminal vía `stty size </dev/tty` expandiendo el panel derecho, las barras visuales de US/láser/servo/motores y la cantidad de filas de logs sin romper el diseño de `112` / `92` cols ni agregar dependencias.
- **Suite de pruebas ampliada ([`ServidorCentralControlTest.java`](keiko-server/src/test/java/com/orquitas/server/ServidorCentralControlTest.java), [`ConsolaTuiTest.java`](keiko-server/src/test/java/com/orquitas/server/ConsolaTuiTest.java), [`PlanificadorNavegacionTest.java`](keiko-server/src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java), [`ProtocolParserTest.java`](keiko-server/src/test/java/com/orquitas/server/ProtocolParserTest.java)).**

### Tests Run (Evidencia PR-B — 2026-11-23)
- `cd keiko-server && mvn clean test`: **57/57 OK** (0 Failures, 0 Errors, 0 Skipped).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.0 | mega-3us-2laser`).

---

## [PR-A — 2026-11-23] Pruebas Reales: Telemetría en Reposo, Estómago en Vivo y Velocidad/PWM (Mergeado en `main`)

### Added (Agregado)
- **Telemetría continua en reposo + estómago en paralelo ([`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino), [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java)):** el Mega transmite `TELEMETRY:` (7 campos) y `DBG:estomago=...` cada 200 ms sin bloquear `TELEMETRY:` aun antes de `CMD:START`; `keiko-server` clasifica con Neuroph 2.98 y actualiza la TUI en modo pasivo (sin avanzar la FSM ni mover motores mientras está detenido).
- **Control en vivo de velocidad y PWM (`// [SOLO PRUEBAS / DEBUG]`):** flags `--speed-scale`, `--pwm-min`, `--pwm-max` ([`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java)), comandos de consola `speed <pct>`/`+`/`-`, `pwm <min> [max]`, `sweep`/`hold` ([`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java)), y tramas `CMD:PWM:<min>,<max>` / `LASER:SWEEP|HOLD` ([`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java)).
- **Visualización del láser ToF del estómago y parámetros motrices en TUI ([`EstadoMonitorTui.java`](keiko-server/src/main/java/com/orquitas/server/EstadoMonitorTui.java), [`ConsolaTui.java`](keiko-server/src/main/java/com/orquitas/server/ConsolaTui.java)):** parseo estructurado de `DBG:estomago=<mm> base=<mm> det=<n> cambios=<n>` mostrado como `LSR-EST` en los paneles detallado y compacto junto a `Vel` y `PWM`.

### Changed (Modificado)
- **Integración de calibraciones y fixes de campo en [`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino):** incorporados `US_A`/`US_B`, `LASER_B_CM`, `BARRIDO_PASO = 1` (`30 ms`, `LASER_ASENTAR_MS = 10 ms`), `LECTURAS_CONFIRMAR = 4`, `CAPTURA_ENFRIAMIENTO_MS = 3000`, corrección de temblor en `ESCAPE`/`FINALIZACION` (`estadoFinal()`), reporte diferido de errores ToF al iniciar corrida, soporte de `LASER:<grados|SWEEP|HOLD>` y `PICKER:ON/OFF`, y `#define PERMITIR_MANUAL 1` / `DEPURAR_ESTOMAGO 1` marcados para pruebas físicas.
- **TUI ANSI en vivo y enlace Bluetooth exclusivo (`#20`):** panel visual en terminal sin dependencias externas con banner ASCII fusionado, transporte Bluetooth RFCOMM/SPP exclusivo (`ReceptorSerie` vía `jSerialComm`, sin `ReceptorConexiones`/TCP/`--port`), Fail-Safe `< 500 ms`, reinicio limpio de handshake al desconectar y suite [`ConsolaTuiTest.java`](keiko-server/src/test/java/com/orquitas/server/ConsolaTuiTest.java).

### Tests Run (Evidencia de Pruebas — 2026-11-23)
- `cd keiko-server && mvn clean test`: **54/54 OK** (0 Failures, 0 Errors, 0 Skipped).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`**.
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.0 | mega-3us-2laser`).

---

## [2.0.0] — 2026-11-23

Línea base oficial de Fase B: erradicación completa de atajos de simulación (`SIM-01..13`), reglas sensoriales hardcodeadas (`HRD-01..14`) y hallazgos complementarios (`COMP-01..05`). Toda clasificación perceptual emana exclusivamente de **Neuroph 2.98** (`modelo_ultrasonico.nnet` + `modelo_canicas.nnet`) sobre telemetría limpia de 7 campos idéntica entre Arduino Mega 2560 y Webots.

### Matriz de Compatibilidad (`2.0.0`)

| Componente | Ruta / Artefacto | Versión | Rig / Especificación | Hash / Verificación |
| :--- | :--- | :---: | :---: | :--- |
| **Raíz (`VERSION`)** | [`VERSION`](VERSION) | `2.0.0` | `mega-3us-2laser` | `./scripts/check_versions.sh` (**9/9 `[OK]`**) |
| **Servidor Central (Java 21)** | [`keiko-server/pom.xml`](keiko-server/pom.xml) + [`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java) | `2.0.0` | `mega-3us-2laser` | `mvn clean test` (**47/47 OK**) + `mvn package` |
| **Firmware Robot (Mega 2560)** | [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) | `2.0.0` | `mega-3us-2laser` | `PERMITIR_MANUAL 0` + `HELLO:2.0.0,mega-3us-2laser` |
| **Simulador 3D (Webots)** | [`keiko-sim/config/simulation_params.json`](keiko-sim/config/simulation_params.json) + [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py) | `2.0.0` | `mega-3us-2laser` | `unittest` (**33/33 OK**) + `TELEMETRY:` 7 campos |
| **Red Neuronal 1 (Sector US)** | [`modelo_ultrasonico.nnet`](keiko-server/models/modelo_ultrasonico.nnet) | `2.0.0` | `1 → 8 → 3` (`{BLOQUEO, ESPACIO_LIBRE, SALIDA}`) | SHA-256 `9e932230e84a2a0ef79bcc17d2f510df87d1936e87ef27828ab79fe4285c7877` (`5 373 B`, acc `99.17%`) |
| **Red Neuronal 2 (Canicas)** | [`modelo_canicas.nnet`](keiko-server/models/modelo_canicas.nnet) | `2.0.0` | `6 → 32 → 16 → 4` (`{LOBITO_CENTRO, LOBITO_DER, LOBITO_IZQ, NINGUNA}`) | SHA-256 `7fb3ed7d2c461b19fa2cb36d2dd97108b874c20928bea41aeaa82df56f66236d` (`35 745 B`, acc `97.29%`) |

### Removed / Erradicated (Eliminado — `SIM-01..13`, `HRD-01..14`)
- **Protocolo y telemetría sin pose (`SIM-01..04`):** retirados `(rx, ry, yaw)` y `EVENT:SALIDA_META` de [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py), el parseo de pose en [`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java), `hasPose`/`getPosX`/`getPosY`/`getTheta` de [`VectorSensores.java`](keiko-server/src/main/java/com/orquitas/server/VectorSensores.java) y la rama `SALIDA_META:` de [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java). **Por qué:** garantizar paridad estricta de 7 campos entre simulación y hardware físico.
- **Filtros post-inferencia en `ClasificadorNeuroph.java` (`HRD-01`, `HRD-02`, `COMP-05`):** eliminadas las sobreescrituras por `servoDeg >= 18.0` y reasignaciones geométricas `dl`/`dr`; la clasificación perceptual proviene íntegramente de `NeuralNetwork.calculate()` + argmax.
- **Lógica hardcodeada y escape por GPS en `PlanificadorNavegacion.java` (`SIM-05..13`, `HRD-03..14`, `COMP-02`):** reescrito como máquina de estados puramente simbólica operando sobre la terna `[IZQ, CEN, DER]` de Neuroph 2.98 y memoria temporal FSM, eliminando `verificarEscudoPerimetral`, `esPuntoEnPared`, `esLaserEnPared`, `esSombraObstaculoUltrasonico`, `esAnchoTicsCanicaValido` y cualquier lectura de distancias crudas.

### Changed (Modificado)
- **Breakout simbólico y guarda `SALIDA`+`BLOQUEO` en [`PlanificadorNavegacion.java`](keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java):** agregado contador simbólico `episodiosMismoLado` (`UMBRAL_EPISODIOS_MISMO_LADO = 4`, olvidado tras `150` ciclos) para ejecutar retroceso + pivote hacia el interior ante bucles tangenciales en el perímetro, y retroceso + pivote ante `SALIDA` lateral simultánea con `BLOQUEO` central en `BUSQUEDA`.
- **Alineación física del simulador y rig (`COMP-01`):** unificado barrido continuo `[-45°, +60°]` paso `5°` en [`simulation_params.json`](keiko-sim/config/simulation_params.json), [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py) y [`benchmark_hil.py`](keiko-sim/scripts/benchmark_hil.py) (con `CAPTURE_COOLDOWN_MS=0` para `--mode=fast`), y ajustado el estrecho de salida de [`build_worlds.py`](keiko-sim/scripts/build_worlds.py) a **`0.300 m`** exactos.
- **Datasets V2 y reentrenamiento Neuroph 2.98:** actualizado [`generate_datasets.py`](keiko-sim/scripts/generate_datasets.py) (cobertura `[-45°, +60°]`, proyección cross-beam `y_hit`, banda negativa de suelo `23.0–35.5 cm`, rechazos laterales `>= +20°` y umbrales acústicos `BLOQUEO`/`SALIDA` calibrados) y reentrenados ambos `.nnet` (`99.17%` / `97.29%` en `neuroph-train`), sincronizados en las 3 rutas del repositorio.

### Fixed (Corregido)
- **`live_visualizer.py` y suite Python `keiko-sim/tests`:** corregida la llamada a `UltrasonicPhysicsModel.apply(..., active_obstacles_xy=...)` en [`live_visualizer.py`](keiko-sim/controllers/live_visualizer/live_visualizer.py) y alineados `test_meshes_and_arena.py`, `test_ultrasonic_physics.py` y `test_robot_client_protocol.py`.

### Security / Competition Hardening (`COMP-03`)
- **Bloqueo de control manual en competencia ([`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)):** fijado `#define PERMITIR_MANUAL 0` por defecto (deshabilita comandos `wasdkx ` por USB/BT; cambiar a `1` solo en pruebas de banco).

### Tests Run (Evidencia de Pruebas — 2026-11-23)
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`2.0.0 | mega-3us-2laser`, `exit 0`).
- `cd keiko-server && mvn clean test`: **47/47 OK** (`0` Failures, `0` Errors, `0` Skipped, `2.501 s`; `ProtocolParserTest` 10/10, `PlanificadorNavegacionTest` 14/14, `ConfiguracionServidorTest` 4/4, `HardwareSerialAndCaptureTest` 5/5, `TwoNetworksInferenceTest` 5/5, `ConcurrencyEstomagoTest` 2/2, `Hito2IntegrationTest` 2/2, `NeurophInferenceTest` 5/5).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`** (`0.637 s`).
- `python3 -m unittest discover -s keiko-sim/tests -v`: **33/33 OK** (`8.006 s`, incluye `TestWebotsHeadlessLoad`).
- Benchmark HIL Webots headless (`benchmark_hil.py --scenarios 1-8 --port 8888`, Neuroph 2.98 puro): reducción de colisiones `165 → 58` respecto a la línea base pre-Fase B, y mejora tras breakout simbólico de `3/24` a **`7/24` capturas** (`5/8` escenarios `PARTIAL`; `SCEN-1` y `SCEN-5` con `2/3`).

---

## [2.0.0-audit.2] — 2026-11-21

Integración de la rama `feat/integracion-robot-real` (`5f22632`, archivada bajo tag `archive/feat-integracion-robot-real`) con calibraciones físicas y firmware no bloqueante validado sobre el robot real (pruebas del 2026-11-19), manteniendo sincronizado todo el ecosistema antes de iniciar la Fase B (`2.0.0`).

### Matriz de Compatibilidad (`2.0.0-audit.2`)

| Componente | Ruta / Artefacto | Versión | Rig / Especificación | Verificación |
| :--- | :--- | :---: | :---: | :--- |
| **Raíz (`VERSION`)** | [`VERSION`](VERSION) | `2.0.0-audit.2` | `mega-3us-2laser` | `./scripts/check_versions.sh` (9/9 `[OK]`) |
| **Servidor Central (Java 21)** | [`keiko-server/pom.xml`](keiko-server/pom.xml) + [`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java) | `2.0.0-audit.2` | `mega-3us-2laser` | `mvn clean test` (39/39 OK) + `mvn package` |
| **Firmware Robot (Mega 2560)** | [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) | `2.0.0-audit.2` | `mega-3us-2laser` | Loop no bloqueante (`orquita_mega`) + `HELLO:2.0.0-audit.2,mega-3us-2laser` |
| **Simulador 3D (Webots)** | [`keiko-sim/config/simulation_params.json`](keiko-sim/config/simulation_params.json) + [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py) | `2.0.0-audit.2` | `mega-3us-2laser` | `py_compile` OK + `HELLO:2.0.0-audit.2,mega-3us-2laser` |
| **Modelos `.nnet`** | [`modelo_ultrasonico.nnet`](keiko-server/models/modelo_ultrasonico.nnet) + [`modelo_canicas.nnet`](keiko-server/models/modelo_canicas.nnet) | `2.0.0-audit.2` | 3US + 2Laser | Sin cambios respecto a `2.0.0-audit.1` (`c0952be2...` / `9e717570...`) |

### Added (Agregado)
- **Control `CMD:START` / `CMD:STOP` y `DBG:` en `keiko-server`:** constantes y serialización en [`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java), flag `--auto-start` (`AUTO_START` en `.env`) y consola interactiva (`start`/`stop`) en [`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java) / [`ServidorCentral.java`](keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java), y manejo silencioso de tramas `DBG:` en [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java). **Por qué:** el firmware validado arranca en reposo seguro esperando `CMD:START` (o `'k'`) y puede emitir telemetría `DBG:` de estómago.
- **Nuevas pruebas físicas en `pruebas/`:** [`prueba-ultrasonidos-mega/`](pruebas/prueba-ultrasonidos-mega/prueba-ultrasonidos-mega.ino), [`prueba-laser-mega/`](pruebas/prueba-laser-mega/prueba-laser-mega.ino) y [`prueba-todo-junto/`](pruebas/prueba-todo-junto/prueba-todo-junto.ino) (test de caída de tensión / EEPROM brownout), catalogadas en [`pruebas/README.md`](pruebas/README.md).

### Changed (Modificado)
- **Firmware oficial validado en robot real ([`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino)):** reemplaza el sketch heredado de `tests-emi` por `orquita_mega` (434 líneas, bucle no bloqueante), con pines HC-SR04 verificados (`TRIG {24, 26, 28}`, `ECHO {25, 27, 29}`), sentido espejo de láseres `{1, -1}`, neutros y velocidades de picker `{1485, 1500}` / `{360, 300}`, tope duro de giro de `10 s`, corte inmediato de picker tras captura, rearmado de estómago gestionado por cambio de estado del servidor, fix de underflow de `millis()` en fail-safe/manual y handshake `HELLO:<version>,<rig>`. **Por qué:** el sketch previo de `tests-emi` tenía invertidos los canales US medio/derecho y valores de servos/picker no calibrados sobre el prototipo físico.
- **Calibración en sketches de `pruebas/`:** actualizados `PICKER-ESTOMAGO-Y-ALIMENTACION.md`, `picker-*`, `prueba-servo*` (`NEUTRO={1485,1500}`, `VEL_US={360,300}`, barrido espejo) y `prueba-traccion.ino` (control `WASD` por USB y HC-05 `Serial1`).
- **Alineación documental:** actualizados [`AGENTS.md`](AGENTS.md), [`README.md`](README.md), [`keiko-server/README.md`](keiko-server/README.md), [`keiko-sim/README.md`](keiko-sim/README.md) y [`archive/README.md`](archive/README.md) con los pines reales y el nuevo flujo `CMD:START`/`CMD:STOP`.

### Archived (Archivado)
- Movido el sketch Mega previo de `tests-emi` a [`archive/firmware-mega-tests-emi/orquita_firmware_tests_emi.ino`](archive/firmware-mega-tests-emi/orquita_firmware_tests_emi.ino).

### Tests Run (Evidencia de Pruebas — 2026-11-21)
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`exit 0`).
- `cd keiko-server && mvn clean test`: **39/39 OK** (0 Failures, 0 Errors, 0 Skipped; `2.634 s`; incluye nuevos tests para `CMD:START`/`CMD:STOP`/`PREFIX_DBG`, `--auto-start` y `DBG:` + auto-start en `ManejadorCliente`).
- `cd keiko-server && mvn package -DskipTests=true`: **`BUILD SUCCESS`** (`0.687 s`).
- `python3 -m py_compile keiko-sim/controllers/robot_client/robot_client.py`: **OK** (`exit 0`).

---

## [2.0.0-audit.1] — 2026-11-21

Consolidación en fase A de las líneas divergentes (`main`, `origin/feat/hw-field-ready` y `origin/tests-emi`) sobre un árbol unificado para el hardware objetivo `mega-3us-2laser`, incorporando versionado sincronizado, handshake `HELLO` y auditoría exhaustiva de cumplimiento anti-hardcoding previa a la refactorización de Fase B (`2.0.0`).

### Matriz de Compatibilidad (`2.0.0-audit.1`)

| Componente | Ruta / Artefacto | Versión | Rig / Especificación | Hash / Verificación |
| :--- | :--- | :---: | :---: | :--- |
| **Raíz (`VERSION`)** | [`VERSION`](VERSION) | `2.0.0-audit.1` | `mega-3us-2laser` | Fuente única de verdad (`./scripts/check_versions.sh`) |
| **Servidor Central (Java 21)** | [`keiko-server/pom.xml`](keiko-server/pom.xml) + [`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java) | `2.0.0-audit.1` | `mega-3us-2laser` | `mvn clean test` (36/36 OK) |
| **Firmware Robot (Mega 2560)** | [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) | `2.0.0-audit.1` | `mega-3us-2laser` | `KEIKO_VERSION` / `KEIKO_RIG` + `HELLO:2.0.0-audit.1,mega-3us-2laser` |
| **Simulador 3D (Webots)** | [`keiko-sim/config/simulation_params.json`](keiko-sim/config/simulation_params.json) + [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py) | `2.0.0-audit.1` | `mega-3us-2laser` | `KEIKO_VERSION` / `KEIKO_RIG` + `HELLO:2.0.0-audit.1,mega-3us-2laser` |
| **Red Neuronal 1 (Sector US)** | [`modelo_ultrasonico.nnet`](keiko-server/models/modelo_ultrasonico.nnet) | `2.0.0-audit.1` | 1 entrada → `{BLOQUEO, ESPACIO_LIBRE, SALIDA}` | SHA-256 `c0952be2...` (5 373 B) idéntico en `keiko-server/` y `keiko-sim/data/models/` |
| **Red Neuronal 2 (Canicas)** | [`modelo_canicas.nnet`](keiko-server/models/modelo_canicas.nnet) | `2.0.0-audit.1` | 6 entradas → `{LOBITO_CENTRO, LOBITO_DER, LOBITO_IZQ, NINGUNA}` | SHA-256 `9e717570...` (35 745 B) idéntico en `keiko-server/` y `keiko-sim/data/models/` |

> **Regla de handshake:** `ManejadorCliente` valida compatibilidad `major.minor` y coincidencia exacta de `rig` mediante `HELLO:<version>,<rig>` al iniciar la sesión serie o TCP.

### Added (Agregado)
- Archivo raíz [`VERSION`](VERSION) (`2.0.0-audit.1`) y validador automático [`scripts/check_versions.sh`](scripts/check_versions.sh) (9/9 comprobaciones entre Maven, Java, firmware `.ino`, `simulation_params.json` y `robot_client.py`). **Por qué:** evitar desincronización silenciosa entre hardware físico, simulador y servidor central.
- Handshake `HELLO:<version>,<rig>` en [`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java), [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java), [`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) y [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py). **Por qué:** detectar de inmediato incompatibilidades de versión o rig al conectar por Bluetooth SPP o TCP.
- Transporte serie real en `keiko-server` con `jSerialComm 2.11.0` ([`ReceptorSerie.java`](keiko-server/src/main/java/com/orquitas/server/ReceptorSerie.java)), puente Bluetooth RFCOMM/TCP ([`keiko-server/scripts/bt_rfcomm_bridge.py`](keiko-server/scripts/bt_rfcomm_bridge.py)), flags CLI `--serial`, `--baud`, `--list-ports` y carga de `.env` ([`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java)). **Por qué:** habilitar la operación directa contra el módulo HC-05 o USB sin adaptadores externos obligatorios.
- Informe de auditoría técnica [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md) con inventario `SIM-01..13`, `HRD-01..14`, `COMP-01..05` y hashes SHA-256 de modelos `.nnet`. **Por qué:** documentar con exactitud los atajos y umbrales heredados antes de su erradicación en Fase B (`2.0.0`).
- Índices documentales [`pruebas/README.md`](pruebas/README.md), [`archive/README.md`](archive/README.md) y este [`CHANGELOG.md`](CHANGELOG.md).

### Changed (Modificado)
- **Unificación de firmware activo a Arduino Mega 2560 (`mega-3us-2laser`):** [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) reemplaza al sketch UNO previo con soporte para shield L293D (`M1..M4`), PCA9685 (`0x40`), 3× HC-SR04 (`TRIG {24,27,29}`, `ECHO {25,26,28}`), 2× VL53L0X frontales en servos SG90 (`0x30`/`0x31`), picker 360° + DC auxiliar, VL53L0X de estómago (`0x32`, `A10`), HC-05 en `Serial1` (`9600` bps) y Fail-Safe `< 500 ms`. **Por qué:** alinear el repositorio con la plataforma física real verificada en banco.
- **Unificación de `keiko-server`:** integrado el planificador de doble red (`ClasificadorNeuroph` + `PlanificadorNavegacion`) con el soporte serie, cooldown anti-rebote (`1500 ms`), copia defensiva en broadcast TCP (`ReceptorConexiones.java`) y sincronización de los binarios `.nnet` (`modelo_ultrasonico.nnet` y `modelo_canicas.nnet`) en `keiko-server/models/`, `keiko-server/src/main/resources/models/` y `keiko-sim/data/models/`. **Por qué:** combinar la arquitectura de inferencia dual con la conectividad de campo en una sola base compilable.
- **Unificación de `keiko-sim`:** incorporados `build_worlds.py`, mundos `bahia_de_caza_scen_1..14.wbt`, `bahia_de_caza_circular.wbt` / `bahia_de_caza_circular_client.wbt`, `DatasetRecorder`, lanzadores [`iniciar_simulacion.sh`](keiko-sim/scripts/iniciar_simulacion.sh) e [`iterar_simulacion.sh`](keiko-sim/scripts/iterar_simulacion.sh) y entrenamiento continuo [`entrenamiento_continuo.py`](keiko-sim/scripts/entrenamiento_continuo.py). **Por qué:** reproducir en Webots 3D la geometría y sensores del robot Mega actual.
- **Alineación documental:** reescritura de [`AGENTS.md`](AGENTS.md), [`README.md`](README.md), [`keiko-server/README.md`](keiko-server/README.md) y [`keiko-sim/README.md`](keiko-sim/README.md) para reflejar rutas, puertos, flags y rig reales.

### Archived (Archivado)
- Traslado a [`archive/`](archive/README.md) de artefactos superados para conservar trazabilidad sin contaminar la ruta activa:
  - [`archive/firmware-uno/`](archive/firmware-uno/): firmware original Arduino UNO + L298N + 4 HC-SR04 y diagrama eléctrico previo.
  - [`archive/sensores-legacy/`](archive/sensores-legacy/): especificaciones coaxiales previas (4/6/8/10 HC-SR04).
  - [`archive/hitos/`](archive/hitos/): entregas académicas estáticas de Hito 2 y Hito 3 (incluye copia histórica Java en `archive/hitos/HITO3/codigo/`).
  - [`archive/pruebas-experimentales/`](archive/pruebas-experimentales/): experimentos descartados (`detector-6-ultrasonidos`, `lidar_scanner`, `vision`, `marble_test`, `prueba-trabado-vcc`).

### Removed (Eliminado)
- Eliminado el directorio `docs/uml/` por encontrarse obsoleto respecto a la arquitectura `mega-3us-2laser` y doble red Neuroph 2.98.

### Known Audit Findings (Hallazgos Conocidos para Fase B `2.0.0`)
Registrados en detalle en [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md):
- **`SIM-01..SIM-13` (Atajos de simulación):** `robot_client.py` aún transmite `(rx, ry, yaw)` en `TELEMETRY:` (10 campos) y `EVENT:SALIDA_META`, mientras `PlanificadorNavegacion.java` y `ManejadorCliente.java` mantienen ramas `hasPose()`, `establecerSalidaMeta`, `verificarEscudoPerimetral`, `esPuntoEnPared`/`esLaserEnPared` y navegación trigonométrica en `ESCAPE`.
- **`HRD-01..HRD-14` (Hardcoding sensorial):** `ClasificadorNeuroph.java` y `PlanificadorNavegacion.java` conservan filtros post-inferencia, heurísticas de sombra/ancho (`esSombraObstaculoUltrasonico`, `esAnchoTicsCanicaValido`) y comparaciones sobre distancias crudas en cm que corrigen o sustituyen a Neuroph 2.98.
- **`COMP-01..COMP-05`:** Desalineación de rango angular de servo (`[-45°, +60°]` en firmware vs `<= 50°` en Java vs `[-15°, +35°]` en Webots), eventos `EVENT:COLISION` exclusivos de Webots, comandos de banco `TEST:` en firmware sin flag de deshabilitación para competencia y contador local `canicasRecolectadas` en firmware.
- **Deuda en `keiko-sim`:** `TypeError` activo en `live_visualizer.py:231` (`marble_positions` vs `active_obstacles_xy`), desvío de ancho de salida en `build_worlds.py` (`0.3100 m` vs `0.30 ± 0.005 m`) y tests unitarios coaxiales legacy desactualizados.

### Tests Run (Evidencia de Pruebas — 2026-11-21)
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`exit 0`).
- `cd keiko-server && mvn clean test`: **36/36 OK** (0 Failures, 0 Errors, 0 Skipped; `2.505 s`; 8 clases: `ProtocolParserTest` 8/8, `PlanificadorNavegacionTest` 9/9, `ConfiguracionServidorTest` 3/3, `HardwareSerialAndCaptureTest` 3/3, `TwoNetworksInferenceTest` 4/4, `ConcurrencyEstomagoTest` 2/2, `Hito2IntegrationTest` 2/2, `NeurophInferenceTest` 5/5).
- `python3 -m unittest discover -s keiko-sim/tests -v`: **23/30 OK, 3 FAIL, 4 ERROR** (`12.173 s`, `exit 1`; detallados en [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md#53-simulador-python-python3--m-unittest-discover--s-keiko-simtests--v)).

---

## Histórico Pre-Unificación (`archive/*` — Serie `1.x` y Ramas Previas)

Para referencia histórica, se conservan los tags inmutables en Git y los artefactos estáticos en [`archive/`](archive/README.md):

| Tag Git | Commit | Descripción Histórica |
| :--- | :---: | :--- |
| `archive/main-pre-reorg` | `25e2600` | Estado previo de `main`: arquitectura inicial `1.x` con firmware Arduino UNO + L298N + 4 HC-SR04 coaxiales, modelos CAD/STL (`modelos-3d/`) y entregas académicas de Hitos. |
| `archive/feat-hw-field-ready` | `365b998` | Rama de integración serie/Bluetooth (`ReceptorSerie.java`, `bt_rfcomm_bridge.py`, carga `.env`, cooldown de captura) previa a la unificación con `tests-emi`. |
| `archive/tests-emi` | `83f86bf` | Rama con firmware definitivo Arduino Mega 2560 (`3US + 2Laser`), mundos `scen_1..14`/`circular` y arquitectura de doble red Neuroph 2.98. |
| `archive/feat-sim-3us-2laser` | `c65b9f9` | Rama experimental intermedia de simulación `3US + 2Laser` previa al merge en `main` (`#14`). |
