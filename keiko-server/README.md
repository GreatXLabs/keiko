# Servidor Central (Cerebro) - Proyecto Orquitas (Keiko)

Módulo de procesamiento distribuido, inferencia con Inteligencia Artificial (**Neuroph 2.98**), persistencia crítica y concurrencia para el proyecto "Orquitas: Inteligencia y Supervivencia en la Bahía de Caza" (Programación Sobre Redes & Desarrollo de Sistemas, Ciclo 2026).

---

## 1. Arquitectura Técnica y Concurrencia

El Servidor Central actúa como el **Cerebro** del cetáceo robótico y coordina múltiples flujos de ejecución concurrentes:

```text
[Cliente: Orquita (Webots / Arduino)]
         |
         | (Bluetooth RFCOMM/SPP o TCP Socket - Puerto 8888)
         v
+-------------------------------------------------------------------------+
|                         SERVIDOR CENTRAL (PC)                           |
|                                                                         |
|  [Hilo 1: NetworkServer / ClientHandler]                                |
|    - Lectura no bloqueante de telemetría y eventos                      |
|    - Transmisión periódica de HEARTBEAT (liveness < 500 ms)             |
|                                                                         |
|  [Hilo 2: NeurophClassifier]                                            |
|    - Normalización de vectores sensoriales [4 sensores]                 |
|    - Clasificación con red neuronal .nnet calibrada                     |
|                                                                         |
|  [Hilo 3: EstomagoManager - Hilo Escritor]                             |
|    - Sección Crítica con ReentrantReadWriteLock (Fair Lock)            |
|    - Escritura atómica de capturas en estomago.txt                      |
|    - flush() obligatorio y FileDescriptor.sync() físico a disco         |
|                                                                         |
|  [Hilo 4: AuditorThread - Hilo Lector ("La Linareada")]                 |
|    - Sondeo periódico continuo de estomago.txt (Read Lock)             |
|    - Evaluación exclusiva contra almacenamiento físico (sin variables) |
|    - Disparo irreversible de MODO_ESCAPE al validar 3 capturas         |
|                                                                         |
|  [Hilo 5: NavigationPlanner (FSM)]                                      |
|    - BÚSQUEDA -> APROXIMACIÓN -> CAPTURA -> ESCAPE -> FINALIZACIÓN      |
+-------------------------------------------------------------------------+
```

---

## 2. Requisitos y Compatibilidad con Apache NetBeans

- **Java Development Kit:** Java 21 LTS (OpenJDK / Temurin).
- **Gestor de Construcción:** Apache Maven 3.9+.
- **Compatibilidad con NetBeans:** 100% nativa.
  - En Apache NetBeans: **File -> Open Project... -> Seleccionar la carpeta `keiko-server/`**.
  - NetBeans detectará automáticamente el archivo `pom.xml` y todas las dependencias (incluyendo Neuroph 2.98).
  - Para ejecutar desde NetBeans: Clic derecho en el proyecto -> **Run** o **Debug**.
  - Para ejecutar pruebas: Clic derecho -> **Test**.

---

## 3. Comandos de Consola y Maven

### Compilar y Ejecutar Pruebas Unitarias y de Concurrencia
```bash
mvn clean test
```
Ejecuta las pruebas automatizadas:
- `ConcurrencyEstomagoTest`: Prueba de estrés con 5 hilos escritores simultáneos disputando el bloqueo exclusivo y el hilo auditor leyendo periódicamente.
- `NeurophInferenceTest`: Verificación de inferencia neuronal para las 5 clases del entorno (`ESPACIO_LIBRE`, `LOBITO`, `MURO`, `OBSTACULO`, `SALIDA`).
- `ProtocolParserTest`: Validación de parseo defensivo de tramas y detección de datos corruptos.

### Empaquetar JAR Ejecutable Autónomo (Fat JAR)
```bash
mvn package -DskipTests=true
```
Genera el binario ejecutable `target/keiko-server.jar` con todas las librerías incluidas (Neuroph, SLF4J, etc.).

### Iniciar Servidor Central
```bash
# Iniciar en puerto por defecto (8888)
java -jar target/keiko-server.jar

# Iniciar reseteando el estómago para una nueva pasada oficial
java -jar target/keiko-server.jar --port 8888 --reset

# Parámetros adicionales configurables:
# --port <puerto>
# --model <ruta_al_modelo.nnet>
# --estomago <ruta_archivo_estomago>
# --reset (borra capturas previas)
```

---

## 4. Persistencia del Estómago ("La Linareada")

Por estricto contrato de la cátedra:
1. **Archivo Físico Único:** `estomago.txt`.
2. **Prohibición de Memoria Volátil:** No se utilizan contadores en memoria (`int captures`, etc.) para definir el estado biológico. Si el servidor se apaga o reinicia, reconstruye su estado leyendo `estomago.txt`.
3. **Sección Crítica:** Protegida mediante `ReentrantReadWriteLock(fair=true)`.
4. **Vaciado de Buffer Hardware:** Tras escribir una captura, se ejecuta:
   ```java
   bw.flush();
   fos.getFD().sync(); // Garantiza escritura en almacenamiento físico
   ```
5. **Auditor de la Bahía:** El `AuditorThread` lee periódicamente el archivo y dispara el evento `onPanzaLlena` al detectar exactamente 3 capturas válidas, activando formalmente el `MODO_ESCAPE`.

---

## 5. Protocolo de Comunicación (RFCOMM/SPP)

| Mensaje | Dirección | Significado |
| :--- | :---: | :--- |
| `TELEMETRY:<ll>,<lr>,<hl>,<hr>,<ts>\n` | Cliente -> Servidor | Lecturas de 4 sensores HC-SR04 en cm |
| `COMMAND:<v_left>,<v_right>,<estado>\n` | Servidor -> Cliente | Órdenes de velocidad motriz para 4WD |
| `EVENT:CAPTURA\n` | Cliente -> Servidor | Detección física de canica en boca |
| `STATE_CHANGE:<estado>\n` | Servidor -> Cliente | Conmutación de FSM (ej. `ESCAPE`) |
| `HEARTBEAT\n` | Bidireccional | Watchdog y mantenimiento de enlace (< 500 ms) |
| `ACK:CAPTURA_<n>\n` | Servidor -> Cliente | Confirmación de persistencia en disco |
