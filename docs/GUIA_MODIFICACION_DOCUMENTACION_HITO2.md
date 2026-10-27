# Guía de Modificación de Documentación — Hito 2
## Proyecto Orquitas (Keiko) — Programación Sobre Redes & Desarrollo de Sistemas (Ciclo 2026)

> **Instrucciones para el compañero encargado del Documento de Casos de Uso (Google Docs):**  
> Este documento contiene el detalle exacto de los cambios a realizar sobre la versión del Hito 1 para subsanar todas las observaciones docentes y presentar la entrega del **Hito 2 ("La Linareada" + "La Orquita Pensante")**.  
> El contenido de la **Parte B** está formateado y redactado formalmente para ser copiado y pegado directamente en el Google Docs oficial del proyecto.

---

## PARTE A: Correcciones Inmediatas sobre el Documento Anterior

En la devolución del Hito 1, los profesores señalaron:
> *"La revisión encontró que la documentación se halla muy incompleta en lo que respecta a las vistas de secuencias y descripción de los casos de uso. Faltan conexiones entre los CU y las asociaciones entre casos de uso... Finalmente lo documentado, y lo implementado, son inconsistentes entre sí. Es necesario que se enfoquen en resolver las cuestiones 'formales' tal y como se piden..."*

Para resolver esto, realizar las siguientes correcciones de forma obligatoria:

1. **Eliminar los remanentes de plantillas ajenas:**
   - En el **Índice (página 2)**: Borrar definitivamente *"Caso de uso RF-01: Iniciar sesión"* y *"Caso de uso RF-02: Agregar cuenta"*. Reemplazarlos por el nuevo índice de Casos de Uso (CU-01 al CU-07).
   - En la **Ficha del Proyecto (página 3)**: Corregir el campo *Proyecto: Ikaros* por **Proyecto: Keiko (Orquitas)**.
2. **Asignación formal de Actores en todos los Casos de Uso:**
   - Queda terminantemente prohibido indicar `Actores: N/A`. Todo caso de uso en UML debe poseer un actor iniciador (primario) o disparador:
     - Para telemetría e ingesta: el actor primario es **Orquita (Cliente)**.
     - Para la persistencia y la toma de decisiones: el actor/ejecutor es el **Servidor Central**.
     - Para la auditoría y el cambio de estado a escape: el actor disparador es el **Hilo Auditor** (proceso/temporizador independiente).
3. **Completitud de Cursos de Eventos:**
   - No redactar cursos normales de dos renglones. Se deben detallar las tramas exactas del protocolo (`TELEMETRY:`, `COMMAND:`, `EVENT:CAPTURA`, `STATE_CHANGE:ESCAPE`, etc.).
   - Agregar en **todos** los casos de uso los **Cursos Alternativos y de Excepción** (pérdida de paquetes, datos fuera de rango, error de I/O en disco, desconexión por timeout Fail-Safe).
4. **Unificación Nominal 1:1 con el Código:**
   - Los nombres de los métodos mencionados en los casos de uso deben coincidir exactamente con las clases Java del Servidor (`ServidorCentral`, `ReceptorConexiones`, `ManejadorCliente`, `GestorEstomago`, `AuditorEstomago`, `ClasificadorNeuroph`, `PlanificadorNavegacion`).

---

## PARTE B: Contenido Formal para el Google Docs

*(Copiar y pegar las siguientes secciones en el documento Google Docs de entrega)*

---

# ESPECIFICACIÓN DE CASOS DE USO — SISTEMA CENTRAL KEIKO

## 1. Descripción de Actores

### Actor A-01: Orquita (Cliente / Hardware Robot)
- **Tipo:** Actor Externo (Sistema / Dispositivo de Red).
- **Descripción:** Prototipo robótico móvil 4WD que navega físicamente la Bahía de Caza. Muestrea el banco de 4 sensores ultrasónicos HC-SR04 en doble plano y el sensor bucal de captura. Transmite vectores sensoriales y eventos de ingesta al Servidor Central mediante sockets TCP (emulación RFCOMM/SPP) y ejecuta órdenes motrices de velocidad diferencial (`v_left`, `v_right`). Dispone de un watchdog local Fail-Safe de 500 ms.

### Actor A-02: Hilo Auditor ("La Linareada")
- **Tipo:** Actor Interno / Proceso Concurrente del Sistema (Daemon de Tiempo).
- **Descripción:** Hilo de ejecución independiente en segundo plano (`AuditorEstomago`) que sondea periódicamente (cada 200 ms) el archivo persistente `estomago.txt` bajo control de concurrencia. No depende de contadores en memoria RAM. Al validar la presencia física de exactamente 3 registros válidos de captura, dispara la transición irreversible al Modo Escape.

---

## 2. Matriz de Trazabilidad de Casos de Uso

| Identificador | Nombre del Caso de Uso | Actor Disparador | Tipo / Relación UML | Alcance Técnico |
| :--- | :--- | :--- | :--- | :--- |
| **CU-01** | Recibir Telemetría Sensorial | Orquita (Cliente) | Primario | La Orquita Pensante |
| **CU-02** | Clasificar Entorno con Red Neuronal | Servidor Central | `<<include>>` de CU-01 | Neuroph 2.98 |
| **CU-03** | Generar y Transmitir Comando Motriz | Servidor Central | `<<include>>` de CU-02 | FSM / Navegación |
| **CU-04** | Recibir Notificación de Ingesta | Orquita (Cliente) | Primario | La Linareada |
| **CU-05** | Registrar Captura Persistente | Servidor Central | `<<include>>` de CU-04 | Sección Crítica `estomago.txt` |
| **CU-06** | Auditar Estado Biológico en Disco | Hilo Auditor | Primario (Periódico) | La Linareada |
| **CU-07** | Conmutar a Modo Escape | Hilo Auditor | `<<extend>>` de CU-06 | FSM / Salida de Bahía |

---

## 3. Especificación Detallada de Casos de Uso

### Caso de Uso CU-01: Recibir Telemetría Sensorial
- **Identificador:** CU-01
- **Nombre:** Recibir Telemetría Sensorial
- **Actores:** Orquita (Cliente, Primario).
- **Tipo:** Primario.
- **Precondición:** Socket TCP establecido entre el cliente y el Servidor Central en el puerto 5000.
- **Postcondición:** Vector sensorial validado en memoria y puesto a disposición para clasificación.
- **Descripción:** El robot cliente lee sus sensores ultrasónicos y transmite periódicamente una trama de telemetría para que el servidor procese la situación espacial.
- **Curso Normal:**
  1. El cliente Orquita muestrea sus 4 sensores ultrasónicos HC-SR04 (distancias en cm).
  2. La Orquita formatea la trama ASCII: `TELEMETRY:<d_low_left>,<d_low_right>,<d_high_left>,<d_high_right>,<timestamp>\n` y la envía por el socket.
  3. La clase `ManejadorCliente` del servidor recibe la línea mediante `BufferedReader.readLine()`.
  4. La clase `MensajeProtocolo` parsea la trama y valida que los valores numéricos correspondan a distancias físicas plausibles ($0 \le d \le 300\text{ cm}$).
  5. Se construye una instancia de `VectorSensores`.
  6. El sistema ejecuta por inclusión el caso de uso **CU-02: Clasificar Entorno con Red Neuronal**.
- **Cursos Alternativos:**
  - **4a. Trama corrupta o formato numérico inválido:**
    - `ManejadorCliente` descarta la trama, emite una advertencia en el log y no interrumpe el bucle de recepción.
  - **4b. Valores fuera de rango físico:**
    - El sensor reporta lecturas anómalas (ruido espurio); el sistema satura el valor a 300 cm o descarta la muestra sin propagarla a la red neuronal.
- **Cursos de Excepción:**
  - **E1. Desconexión del socket durante la lectura:**
    - `ManejadorCliente` captura `IOException`, cierra los flujos asociados y notifica a `ReceptorConexiones` para remover la sesión. El cliente físico activa su Fail-Safe de 500 ms frenando motores.

---

### Caso de Uso CU-02: Clasificar Entorno con Red Neuronal
- **Identificador:** CU-02
- **Nombre:** Clasificar Entorno con Red Neuronal
- **Actores:** Servidor Central (Ejecutor).
- **Tipo:** Inclusión (`<<include>>` de CU-01).
- **Precondición:** Vector de sensores normalizado ($[0.0, 1.0]$) disponible en memoria. Red Neuroph `orquitas_production_champion.nnet` cargada.
- **Postcondición:** Clase ambiental identificada (`LOBITO`, `MURO`, `OBSTACULO`, `SALIDA`, `ESPACIO_LIBRE`) con su vector de probabilidades.
- **Descripción:** El clasificador inteligente utiliza el motor Neuroph 2.98 para resolver la incertidumbre de las lecturas físicas de los ultrasonidos en doble plano.
- **Curso Normal:**
  1. `ManejadorCliente` envía el `VectorSensores` a la clase `ClasificadorNeuroph`.
  2. `ClasificadorNeuroph` normaliza las lecturas dividiendo por el rango máximo calibrado ($3.0\text{ m}$).
  3. Se inyecta el vector de entrada a la red multicapa mediante `NeuralNetwork.setInput(double[])` y se ejecuta `calculate()`.
  4. Se obtienen los valores de las neuronas de salida: `[p_lobito, p_muro, p_obstaculo, p_salida, p_libre]`.
  5. Se identifica la categoría con mayor activación (`argmax`) y se valida que supere el umbral mínimo de confianza ($> 0.60$).
  6. Se retorna el resultado de clasificación a `PlanificadorNavegacion`.
  7. Se ejecuta por inclusión el caso de uso **CU-03: Generar y Transmitir Comando Motriz**.
- **Cursos Alternativos:**
  - **5a. Clasificación ambigua o confianza menor a 0.60:**
    - El clasificador etiqueta el resultado preventivamente como `OBSTACULO` para evitar colisiones por falso positivo.
- **Cursos de Excepción:**
  - **E1. Falla en el archivo de modelo .nnet al inicializar:**
    - El sistema opera en modo de contingencia aplicando reglas de gradiente reactivo de distancias, registrando la alerta en el log.

---

### Caso de Uso CU-03: Generar y Transmitir Comando Motriz
- **Identificador:** CU-03
- **Nombre:** Generar y Transmitir Comando Motriz
- **Actores:** Servidor Central (Ejecutor).
- **Tipo:** Inclusión (`<<include>>` de CU-02).
- **Precondición:** Clasificación de entorno recibida y estado de la FSM conocido (`BUSQUEDA` o `ESCAPE`).
- **Postcondición:** Trama de comando emitida por el socket antes de cumplirse los 500 ms de timeout del cliente.
- **Descripción:** La FSM del `PlanificadorNavegacion` evalúa el estado del robot y la clase detectada para computar las velocidades de giro y avance (`vLeft`, `vRight`), enviando la orden motriz al robot.
- **Curso Normal:**
  1. `PlanificadorNavegacion` consulta el estado operativo (`BUSQUEDA` o `ESCAPE`).
  2. Si está en `BUSQUEDA`:
     - Ante `LOBITO`: Activa aproximación sigilosa hacia la presa ($v = 1.8\text{ rad/s}$).
     - Ante `MURO` u `OBSTACULO`: Aplica giro evasivo sobre el eje ($v_{left} = -5.0, v_{right} = +5.0\text{ rad/s}$).
     - Ante `ESPACIO_LIBRE`: Avanza a velocidad de crucero ($v = 5.0\text{ rad/s}$).
  3. Si está en `ESCAPE`:
     - Ignora detecciones de `LOBITO` y prioriza vector de orientación hacia `SALIDA`.
  4. Se crea la instancia de `ComandoRobot(vLeft, vRight, estado)`.
  5. `ManejadorCliente` serializa la trama: `COMMAND:<v_left>,<v_right>,<estado>\n`.
  6. Se envía la trama por el socket TCP y se ejecuta `BufferedWriter.flush()`.
- **Cursos Alternativos:**
  - **1a. Estado de ERROR_SEGURO activo:**
    - Se genera orden de parada total: `COMMAND:0.00,0.00,ERROR_SEGURO\n`.
- **Cursos de Excepción:**
  - **E1. Error de I/O al escribir en el socket:**
    - Se cierra la conexión cliente. El hardware cliente detiene sus motores automáticamente por su watchdog local.

---

### Caso de Uso CU-04: Recibir Notificación de Ingesta
- **Identificador:** CU-04
- **Nombre:** Recibir Notificación de Ingesta
- **Actores:** Orquita (Cliente, Primario).
- **Tipo:** Primario.
- **Precondición:** El robot cliente alojó físicamente un lobito (canica) en su tolva bucal y su sensor de captura se activó en LOW.
- **Postcondición:** Mensaje de evento recibido por el servidor y disparado el registro persistente.
- **Descripción:** El cliente detecta la retención de una presa y envía inmediatamente el evento de captura hacia el Servidor Central.
- **Curso Normal:**
  1. El sensor bucal del robot (pin D2 en hardware, detector de tolva en simulación) valida la presencia de una canica por más de 100 ms continuos para filtrar rebotes.
  2. El cliente transmite la trama: `EVENT:CAPTURA,id=lobito_<N>\n`.
  3. `ManejadorCliente` en el servidor detecta el prefijo `EVENT:`.
  4. Se ejecuta por inclusión el caso de uso **CU-05: Registrar Captura Persistente**.
- **Cursos Alternativos:**
  - **2a. Trama de evento no reconocida:**
    - El servidor responde `ERROR:COMANDO_DESCONOCIDO\n` y lo asienta en los logs.

---

### Caso de Uso CU-05: Registrar Captura Persistente ("La Linareada")
- **Identificador:** CU-05
- **Nombre:** Registrar Captura Persistente
- **Actores:** Servidor Central (Ejecutor).
- **Tipo:** Inclusión (`<<include>>` de CU-04).
- **Precondición:** Archivo `estomago.txt` creado y accesible en el sistema de archivos del servidor.
- **Postcondición:** Nuevo registro inmutable grabado físicamente en disco; búferes del sistema operativo vaciados por hardware.
- **Descripción:** Registra de forma thread-safe una nueva captura dentro de una sección crítica protegida con cerrojo de monitor (`synchronized`), ejecutando `flush()` y `FileDescriptor.sync()`.
- **Curso Normal:**
  1. `ManejadorCliente` invoca `GestorEstomago.registrarCaptura(registro)`.
  2. El hilo de ejecución entra en la sección crítica: `synchronized (bloqueoArchivo)`.
  3. `GestorEstomago` abre `estomago.txt` en modo append (`FileOutputStream`).
  4. Lee las líneas físicas para calcular el número de captura secuencial ($N$).
  5. Genera la línea formal: `CAPTURA #<N> | id=<id> | timestamp=<ts> | CHECKSUM=<crc32>\n`.
  6. Escribe los bytes en el archivo.
  7. Ejecuta `BufferedWriter.flush()`.
  8. Ejecuta `fos.getFD().sync()` forzando la escritura física en disco sin retención en memoria caché del sistema operativo.
  9. Se libera la sección crítica.
  10. Se retorna el número total de capturas persistidas.
- **Cursos de Excepción:**
  - **E1. Fallo de I/O en disco (disco lleno o permisos insuficientes):**
    - Se captura `IOException`, se emite error en log crítico y no se envía ACK al cliente, requiriendo reintento.

---

### Caso de Uso CU-06: Auditar Estado Biológico en Disco ("La Linareada")
- **Identificador:** CU-06
- **Nombre:** Auditar Estado Biológico en Disco
- **Actores:** Hilo Auditor (Actor Interno / Tiempo, Primario).
- **Tipo:** Primario (Bucle Concurrente Periódico).
- **Precondición:** Servidor en ejecución; Hilo Auditor (`AuditorEstomago`) activo e independiente.
- **Postcondición:** Conteo verificado exclusivamente desde el almacenamiento físico `estomago.txt`.
- **Descripción:** Proceso en segundo plano que sondea periódicamente el disco para determinar el estado de saciedad del robot sin utilizar variables volátiles en memoria RAM.
- **Curso Normal:**
  1. `AuditorEstomago` duerme por su intervalo configurado (`AUDITOR_SONDEO_MS = 200 ms`).
  2. Al despertar, invoca `GestorEstomago.contarCapturasValidas()`.
  3. `GestorEstomago` ingresa a la sección crítica `synchronized (bloqueoArchivo)` para garantizar que el Hilo Escritor no esté en mitad de una operación de I/O.
  4. Abre `estomago.txt`, recorre las líneas y contabiliza las que posean formato válido `CAPTURA #`.
  5. Se libera la sección crítica y se retorna el total físico de registros leídos.
  6. Si la cantidad de capturas leídas es menor a 3, el Hilo Auditor registra el progreso y vuelve al paso 1.
  7. Si la cantidad de capturas leídas es exactamente o superior a 3:
     - Se ejecuta la extensión **CU-07: Conmutar a Modo Escape**.
- **Cursos Alternativos:**
  - **4a. Archivo estomago.txt no existe aún o está vacío:**
    - Retorna 0 capturas y continúa el sondeo.
- **Cursos de Excepción:**
  - **E1. Excepción de lectura I/O en estomago.txt:**
    - El auditor captura la excepción, registra la falla y reintenta en el próximo ciclo sin detener el hilo.

---

### Caso de Uso CU-07: Conmutar a Modo Escape
- **Identificador:** CU-07
- **Nombre:** Conmutar a Modo Escape
- **Actores:** Hilo Auditor (Ejecutor).
- **Tipo:** Extensión (`<<extend>>` de CU-06 con condición: `[capturas_auditadas >= 3]`).
- **Precondición:** El Hilo Auditor verificó físicamente 3 capturas en `estomago.txt`.
- **Postcondición:** Estado del sistema mutado a `ESCAPE`; difusión de la orden hacia todos los clientes conectados.
- **Descripción:** Mutación conductual biológica formal. Se aborta la búsqueda de lobitos y se comanda el escape autónomo hacia el Estrecho de Salida.
- **Curso Normal:**
  1. `AuditorEstomago` comprueba que la bandera de escape no haya sido activada previamente (`compareAndSet`).
  2. Notifica a los oyentes registrados mediante `OyenteAuditor.onModoEscapeActivado()`.
  3. `PlanificadorNavegacion` cambia irreversiblemente su estado interno a `EstadoRobot.ESCAPE`.
  4. `ReceptorConexiones` realiza un broadcast a todos los clientes conectados: `STATE_CHANGE:ESCAPE\n`.
  5. Los clientes conectados (Orquita) conmutan su conducta motriz, ignoran canicas y ejecutan el algoritmo de navegación hacia el Estrecho de Salida.
- **Cursos Alternativos:**
  - **1a. El escape ya había sido activado en un ciclo previo:**
    - El auditor omite duplicar la notificación.
