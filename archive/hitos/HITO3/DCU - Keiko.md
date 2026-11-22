

# 

# 

# 

# 

# **`DOCUMENTO DE ESPECIFICACIÓN DE CASOS DE USO`**

# ---

# **`Fecha: 01/11/2026`**

# **`Índice`**

`Historial de versiones	3`

[**`Información del proyecto	3`](#información-del-proyecto)**

[**`Aprobaciones	4`](#aprobaciones)**

[`Resumen Ejecutivo	5`](#resumen-ejecutivo)

[**`Diagrama de Casos de Uso	6`](#diagrama-de-casos-de-uso)**

[**`Descripción de Actores	7`](#descripción-de-actores)**

[`Orquita	7`](#heading=)

[`Cerebro	7`](#cerebro)

[`Hilo Escritor	7`](#hilo-escritor)

[`Hilo Lector	8`](#hilo-lector)

[`Especificación de Casos de Uso	9`](#especificación-de-casos-de-uso)

[`Caso de uso RF-01: Enviar datos de sensores	9`](#caso-de-uso-rf-01:-enviar-datos-de-sensores)

[`Curso Normal	9`](#curso-normal)

[`Caso de uso RF-02: Reconocer tipo de elemento	9`](#caso-de-uso-rf-02:-reconocer-tipo-de-elemento)

[`Curso Normal	10`](#curso-normal-1)

[`Caso de uso RF-03: Generar indicación de movimiento	10`](#heading=)

[`Curso Normal	10`](#curso-normal-2)

[`Caso de uso RF-04: Ejecutar comando de navegación	11`](#caso-de-uso-rf-04:-ejecutar-comando-de-navegación)

[`Curso Normal	11`](#curso-normal-3)

[`Caso de uso RF-05: Enviar señal de ingesta	12`](#heading=)

[`Curso Normal	12`](#curso-normal-4)

[`￼Cursos Alternos	12`](#￼cursos-alternos)

[`Caso de uso RF-06: Registrar captura	12`](#caso-de-uso-rf-06:-registrar-captura)

[`Curso Normal	13`](#curso-normal-5)

[`Caso de uso RF-07: Contar capturas registradas	13`](#caso-de-uso-rf-07:-contar-capturas-registradas)

[`Curso Normal	13`](#curso-normal-6)

[`Cursos Alternos	14`](#cursos-alternos)

[`Caso de uso RF-08: Cambiar a Escape	14`](#caso-de-uso-rf-08:-cambiar-a-escape)

[`Curso Normal	14`](#heading=h.pgt2cth8szkq)

# **`Historial de versiones`** {#historial-de-versiones}

| `Fecha` | `Versión` | `Autor` | `Organización` | `Descripción` |
| :---: | :---: | :---: | :---: | :---: |
| `13/09/2026` | `1.0` | `<INTEGRANTE>` | `GreatX` | `Resumen ejecutivo, diagrama de casos de uso y descripción de actores.` |
| `23/10/2026` | `2.0` | `<INTEGRANTE>` | `GreatX` | `Incorporación de casos de uso perceptuales y telemetría ultrasónica.` |
| `04/11/2026` |  `3.0` | `<INTEGRANTE>` | `GreatX` | `Especificación del sensado, sistema de evasión y manejo de captura.` |

# **`Información del proyecto`** {#información-del-proyecto}

| `Empresa / Organización` | `GreatX` |
| :---- | :---- |
| `Proyecto` | `Keiko` |
| `Fecha de preparación` | `13/09/2026` |
| `Cliente` | `<DOCENTE>` |
| `Gerente / Líder de Proyecto` | `<INTEGRANTE>` |
| `Gerente / Líder de Desarrollo de Software` | `<INTEGRANTE>` |

# **`Aprobaciones`** {#aprobaciones}

| `Nombre y Apellido` | `Cargo` | `Departamento u Organización` | `Fecha` | `Firma` |
| :---: | :---: | :---: | :---: | :---: |
|  |  |  |  |  |
|  |  |  |  |  |
|  |  |  |  |  |
|  |  |  |  |  |

# 

# **`Resumen Ejecutivo`**  {#resumen-ejecutivo}

`Keiko es un sistema diseñado para la recolección, comunicación y registro de información entre un cliente y un servidor en la ejecución de tareas específicas. El sistema basa su funcionamiento en la interacción coordinada entre dos roles/componentes principales: Orquita (Cliente), que actúa como el cliente encargado de detectar e informar eventos de ingesta/captura (como la recolección de lobitos) al servidor y ejecutar las órdenes o instrucciones recibidas; y Cerebro (Servidor), que se desempeña como el servidor central encargado de recibir y analizar las señales emitidas por el cliente, registrar formalmente las capturas obtenidas (como la adición de registros en "estomago.txt"), llevar el conteo de capturas y acondicionar las próximas decisiones o comandos que debe realizar el cliente.`

# 

# **`Diagrama de Casos de Uso`** {#diagrama-de-casos-de-uso}

# `![][image1]`

# **`Descripción de Actores`** {#descripción-de-actores}

## **`Orquita`**

| `Actor` | `Orquita` | `Identificador: A-01` |
| :---- | :---- | :---- |
| **`Descripción`** | `Cliente del sistema.`  |  |
| **`Características`** | `Dispositivo que genera y envía los datos al servidor, para después recibir y ejecutar las órdenes que recibe del servidor.` |  |
| **`Relación`** | `N/A` |  |
| **`Referencias`** | `Vista de caso de uso RF-01, RF-04, RF-05, RF-08 y vistas de secuencia.` |  |

## **`Cerebro`** {#cerebro}

| `Actor` | `Cerebro` | `Identificador: A-02` |
| :---- | :---- | :---- |
| **`Descripción`** | `Componente lógico del servidor encargado de la percepción neuronal y la planificación conductual.` |  |
| **`Características`** | `Rol que interactúa con la interpretación de lecturas y emisión de órdenes.` |  |
| **`Relación`** | `N/A` |  |
| **`Referencias`** | `Vista de caso de uso RF-02, RF-03 y vistas de secuencia.` |  |

## **`Hilo Escritor`** {#hilo-escritor}

| `Actor` | `Hilo Escritor` | `Identificador: A-03` |
| :---- | :---- | :---- |
| **`Descripción`** | `Hilo de atención por cliente dentro del servidor encargado de la persistencia de capturas.` |  |
| **`Características`** | `Proceso concurrente que atiende la sesión del robot y escribe de forma sincronizada y segura las capturas en el archivo de persistencia estomago.txt.` |  |
| **`Relación`** | `N/A` |  |
| **`Referencias`** | `Vista de caso de uso RF-06 y vistas de secuencia.` |  |

## **`Hilo Lector`** {#hilo-lector}

| `Actor` | `Hilo Lector` | `Identificador: A-04` |
| :---- | :---- | :---- |
| **`Descripción`** | `Hilo auditor autónomo del servidor encargado de supervisar el archivo de persistencia.` |  |
| **`Características`** | `Proceso en segundo plano que sondea periódicamente el contenido de estomago.txt, valida la integridad de cada registro y dispara la transición a modo escape al certificar 3 capturas válidas en disco.` |  |
| **`Relación`** | `N/A` |  |
| **`Referencias`** | `Vista de caso de uso RF-07 y vistas de secuencia.` |  |

# **`Especificación de Casos de Uso`** {#especificación-de-casos-de-uso}

## **`Caso de uso RF-01: Enviar datos de sensores`** {#caso-de-uso-rf-01:-enviar-datos-de-sensores}

| `Caso de Uso` | `Enviar datos de sensores` | `Identificador:  RF-01` |
| :---- | :---- | :---- |
| **`Actores`** | `Orquita.` |  |
| **`Tipo`** | `Primario.` |  |
| **`Referencias`** | `Incluye («include») a RF-02.` |  |
| **`Precondición`** | `Haber tomado los datos de los sensores.` |  |
| **`Postcondición`** | `La telemetría cruda es recibida, validada y puesta a disposición del subsistema de percepción neuronal.` |  |
| **`Descripción`** | `La Orquita envía el mensaje al servidor con los datos de los sensores.` |  |
| **`Resumen`** | `Se envía la señal al servidor de los datos tomados.` |  |

### **`Curso Normal`** {#curso-normal}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Orquita` | `Mide las distancias de los 3 sensores ultrasónicos superiores (izq, centro, der).` |
| **`2`** | `Orquita` | `Evalúa si se encuentra en modo búsqueda.` |
| **`3`** | `Orquita` | `Ejecuta un paso en el barrido de los servomotores capturando las lecturas de los 2 sensores láser inferiores junto a su ángulo actual.` |
| **`4`** | `Orquita` | `Concatena los valores numéricos obtenidos.` |
| **`5`** | `Orquita` | `Transmite la trama de telemetría al servidor a través del enlace de comunicación.` |
| **`6`** | `Servidor` | `Recibe la trama de telemetría.` |
| **`7`** | `Servidor` | `Valida el formato y la integridad de la trama.` |
| **`8`** | `Servidor` | `Incluye el caso de uso Reconocer tipo de elemento detectado (RF-02).` |

### **`Cursos Alternos`**

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`2.1`** | `Orquita` | `Detecta que se encuentra en modo escape.` |
| **`2.2`** | `Orquita` | `Omite el barrido de los servomotores.` |
| **`2.3`** | `Orquita` | `Transmite únicamente las lecturas de los sensores ultrasónicos superiores.` |

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`5.1`** | `Orquita` | `Detecta una interrupción en el enlace inalámbrico al transmitir.` |
| **`5.2`** | `Orquita` | `Reintenta la transmisión en el siguiente ciclo.` |
| **`5.3`** | `Orquita` | `Evalúa si la interrupción persiste por más de 500 ms.` |
| **`5.4`** | `Orquita` | `Activa el mecanismo Fail-Safe local.` |

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`7.1`** | `Servidor` | `Detecta inconsistencia en el formato (trama corrupta, incompleta o con datos erróneos).` |
| **`7.2`** | `Servidor` | `Descarta la trama sin alterar el estado actual.` |
| **`7.3`** | `Servidor` | `Aguarda el siguiente ciclo de telemetría.` |

## **`Caso de uso RF-02: Reconocer tipo de elemento`** {#caso-de-uso-rf-02:-reconocer-tipo-de-elemento}

| `Caso de Uso` | `Reconocer tipo de elemento` | `Identificador:  RF-02` |
| :---- | :---- | :---- |
| **`Actores`** | `Cerebro.` |  |
| **`Tipo`** | `Secundario.` |  |
| **`Referencias`** | `Incluido («include») por RF-01.` |  |
| **`Precondición`** | `Disponer de los datos normalizados de telemetría recibidos en el servidor.` |  |
| **`Postcondición`** | `Se obtiene la terna de percepción clasificada [IZQUIERDA, CENTRO, DERECHA] para la toma de decisiones motrices.` |  |
| **`Descripción`** | `El servidor envía los datos de telemetría a los modelos neuronales para clasificar el entorno.` |  |
| **`Resumen`** | `La red neuronal clasifica si el objeto detectado es una pared, lobito, obstáculo o salida.` |  |

### **`Curso Normal`** {#curso-normal-1}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Servidor` | `Normaliza los datos de entrada obtenidos de los sensores.` |
| **`2`** | `Servidor` | `Verifica si el modo ESCAPE se encuentra activo.` |
| **`3`** | `Servidor` | `Ejecuta la Red Neuronal Ultrasónica con las lecturas ultrasónicas.` |
| **`4`** | `Servidor` | `Ejecuta la Red Neuronal Láser para la detección de canicas.` |
| **`5`** | `Servidor` | `Integra las salidas clasificadas de ambas redes neuronales.` |
| **`6`** | `Servidor` | `Obtiene la terna de percepción clasificada [IZQUIERDA, CENTRO, DERECHA].` |

### **`Cursos Alternos`**

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`2.1`** | `Servidor` | `Detecta que el modo ESCAPE está activo.` |
| **`2.2`** | `Servidor` | `Ejecuta únicamente la Red Neuronal Ultrasónica.` |
| **`2.3`** | `Servidor` | `Continúa en el paso 6 del curso normal.` |

#### 

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`4.1`** | `Red Neuronal Láser` | `No clasifica ninguna canica dentro del rango de barrido.` |
| **`4.2`** | `Servidor` | `Asigna la clasificación del entorno basada exclusivamente en la Red Neuronal Ultrasónica.` |
| **`4.3`** | `Servidor` | `Continúa en el paso 6 del curso normal.` |

## **`Caso de uso RF-03: Generar indicación de movimiento`**

| `Caso de Uso` | `Generar indicación de movimiento` | `Identificador:  RF-03` |
| :---- | :---- | :---- |
| **`Actores`** | `Cerebro.` |  |
| **`Tipo`** | `Primario.` |  |
| **`Referencias`** | `Incluye («include») a RF-04.` |  |
| **`Precondición`** | `Haber recibido la respuesta de la red neuronal y generado el vector de objetos.` |  |
| **`Postcondición`** | `El cliente recibe el comando de navegación con las velocidades calculadas.` |  |
| **`Descripción`** | `Se evalúa el vector de elementos y el estado actual para determinar las velocidades de los motores y enviar la instrucción al cliente.` |  |
| **`Resumen`** | `Se procesa la información de la red para decidir cómo será el siguiente movimiento de la Orquita.` |  |

### **`Curso Normal`** {#curso-normal-2}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Servidor` | `Evalúa los datos de la red neuronal y el vector de elementos detectados.` |
| **`2`** | `Servidor` | `Consulta el estado actual de navegación (búsqueda o escape).` |
| **`3`** | `Servidor` | `Calcula las velocidades necesarias para cada rueda (centrando si es canica o retrocediendo si es obstáculo).` |
| **`4`** | `Servidor` | `Construye la trama de comando para Orquita.` |
| **`5`** | `Servidor` | `Transmite la indicación a Orquita.` |
| **`6`** | `Orquita` | `Ejecuta el comando de navegación (RF-04).` |

### **`Cursos Alternos`**

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`3.1`** | `Servidor` | `Detecta una condición de colisión inminente u obstáculo frontal.` |
| **`3.2`** | `Servidor` | `Genera una rutina de evasión indicando marcha atrás y posterior giro.` |

## **`Caso de uso RF-04: Ejecutar comando de navegación`** {#caso-de-uso-rf-04:-ejecutar-comando-de-navegación}

| `Caso de Uso` | `Ejecutar comando de navegación` | `Identificador:  RF-04` |
| :---- | :---- | :---- |
| **`Actores`** | `Orquita.` |  |
| **`Tipo`** | `Secundario.` |  |
| **`Referencias`** | `Incluido («include») por RF-03.` |  |
| **`Precondición`** | `Haber recibido las indicaciones del servidor.` |  |
| **`Postcondición`** | `Se ejecutan los movimientos y se inician nuevas lecturas de los sensores.` |  |
| **`Descripción`** | `La Orquita procesa la trama de control y aplica los niveles de potencia y dirección a los motores.` |  |
| **`Resumen`** | `La Orquita ejecuta los movimientos indicados paso a paso.` |  |

### 

### **`Curso Normal`** {#curso-normal-3}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Orquita` | `Parsea la información de la trama enviada por el servidor.` |
| **`2`** | `Orquita` | `Define la secuencia de movimientos a realizar.` |
| **`3`** | `Orquita` | `Ejecuta las instrucciones físicas sobre los motores.` |
| **`4`** | `Orquita` | `Inicia el nuevo ciclo de lecturas de los sensores.` |

### **`Cursos Alternos`**

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1.1`** | `Orquita` | `Detecta trama corrupta o incompleta al parsear el comando.` |
| **`1.2`** | `Orquita` | `Descarta el comando recibido y mantiene el estado de movimiento anterior hasta la siguiente trama.` |

## **`Caso de uso RF-05: Enviar señal de ingesta`**

| `Caso de Uso` | `Enviar señal de ingesta` | `Identificador:  RF-05` |
| :---- | :---- | :---- |
| **`Actores`** | `Orquita.` |  |
| **`Tipo`** | `Primario.` |  |
| **`Referencias`** | `N/A` |  |
| **`Precondición`** | `Haber sensado mediante los sensores de infrarrojo/láser de boca la entrada de un lobito.` |  |
| **`Postcondición`** | `Se despacha la orden de persistencia de captura al Hilo Escritor.` |  |
| **`Descripción`** | `La Orquita envía el mensaje al servidor notificando la detección del evento de captura del lobito.` |  |
| **`Resumen`** | `Se envía la señal al servidor que indica que se capturó un lobito.` |  |

### **`Curso Normal`** {#curso-normal-4}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Orquita` | `Envía la señal notificando el evento de ingesta al servidor.` |
| **`2`** | `Servidor` | `Valida e identifica que el evento recibido corresponde a una captura válida.` |
| **`3`** | `Servidor` | `Envía la solicitud de escritura al Hilo Escritor (RF-06).Envía respuesta ACK a Orquita.` |

## **`￼Cursos Alternos`** {#￼cursos-alternos}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`2.1`** | `Servidor` | `Determina que el evento recibido no corresponde a una captura.` |
| **`2.2`** | `Servidor` | `Finaliza el proceso de procesamiento de ingesta.` |

## **`Caso de uso RF-06: Registrar captura`** {#caso-de-uso-rf-06:-registrar-captura}

| `Caso de Uso` | `Registrar captura` | `Identificador:  RF-06` |
| :---- | :---- | :---- |
| **`Actores`** | `Hilo Escritor.` |  |
| **`Tipo`** | `Primario.` |  |
| **`Referencias`** | `N/A` |  |
| **`Precondición`** | `Haber recibido la orden de registro de captura generada por RF-05.` |  |
| **`Postcondición`** | `Persistencia de la nueva captura añadida de forma segura en estomago.txt.` |  |
| **`Descripción`** | `Se efectúa el bloqueo de exclusión mutua sobre estomago.txt y se añade la línea correspondiente al registro de captura.` |  |
| **`Resumen`** | `Se escribe el archivo (estómago) para agregar una captura.` |  |

### **`Curso Normal`** {#curso-normal-5}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Hilo Escritor` | `Recibe la solicitud de registro de captura.` |
| **`2`** | `Hilo Escritor` | `Adquiere el cerrojo mutex de estomago.txt.` |
| **`3`** | `Hilo Escritor` | `Escribe la nueva línea de captura en estomago.txt.` |
| **`4`** | `Hilo Escritor` | `Libera el cerrojo mutex de estomago.txt.` |

### **`Cursos Alternos`**

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`2.1`** | `Hilo Escritor` | `Detecta fallo de I/O al acceder o escribir en estomago.txt.` |
| **`2.2`** | `Hilo Escritor` | `Registra el error en el log del sistema y libera el cerrojo mutex.` |

## 

## **`Caso de uso RF-07: Contar capturas registradas`** {#caso-de-uso-rf-07:-contar-capturas-registradas}

| `Caso de Uso` | `Contar capturas registradas` | `Identificador:  RF-07` |
| :---- | :---- | :---- |
| **`Actores`** | `Hilo Lector.` |  |
| **`Tipo`** | `Primario.` |  |
| **`Referencias`** | `Extiende («extend») a RF-08.` |  |
| **`Precondición`** | `Estar en modo búsqueda y ejecutarse el sondeo periódico de auditoría.` |  |
| **`Postcondición`** | `Continúa la búsqueda o se gatilla la transición al modo escape (RF-08).` |  |
| **`Descripción`** | `Se contabilizan las capturas válidas en estomago.txt de forma independiente.` |  |
| **`Resumen`** | `Se lee el archivo (estómago) para mantener la persistencia de las capturas.` |  |

### **`Curso Normal`** {#curso-normal-6}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Hilo Lector` | `Inicia la lectura y auditoría del archivo estomago.txt.` |
| **`2`** | `Hilo Lector` | `Sondea y valida el contenido de los registros en estomago.txt.` |
| **`3`** | `Hilo Lector` | `Certifica que el total de capturas válidas acumuladas alcanza las 3 unidades.` |
| **`4`** | `Hilo Lector` | `Dispara el cambio a modo escape (RF-08).` |

### **`Cursos Alternos`** {#cursos-alternos}

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`3.1`** | `Servidor` | `Verifica que hay menos de 3 capturas registradas.` |
| **`3.2`** | `Hilo Lector` | `Finaliza la lectura del archivo.` |
| **`3.3`** | `Hilo Lector` | `Se vuelve al paso 1 para continuar el ciclo de monitoreo.` |

## **`Caso de uso RF-08: Cambiar a Escape`** {#caso-de-uso-rf-08:-cambiar-a-escape}

| `Caso de Uso` | `Cambiar a Escape` | `Identificador:  RF-08` |
| :---- | :---- | :---- |
| **`Actores`** | `Servidor, Orquita.` |  |
| **`Tipo`** | `Secundario.` |  |
| **`Referencias`** | `Extendido por RF-07.` |  |
| **`Precondición`** | `Haber alcanzado las 3 capturas registradas y validadas.` |  |
| **`Postcondición`** | `Modifica el estado del sistema a modo escape en servidor y cliente.` |  |
| **`Descripción`** | `Se cambia a modo escape y se notifica a Orquita para desactivar el barrido láser.` |  |
| **`Resumen`** | `Se pasa al estado de la navegación a modo escape.` |  |

### **`Curso Normal`**

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`1`** | `Servidor` | `Conmuta de forma irreversible el estado del planificador a ESCAPE, deshabilitando la recolección de canicas y orientando las decisiones futuras hacia la localización y cruce de la SALIDA.` |
| **`2`** | `Servidor` | `Construye y transmite la trama de broadcast STATE_CHANGE:ESCAPE a través de la conexión activa con la Orquita.` |
| **`3`** | `Orquita` | `Recibe la notificación de cambio de estado y actualiza sus directivas internas (detiene el barrido de servomotores y enfoca el procesamiento en los comandos de escape).` |

### **`Cursos Alternos`**

| `Nro.` | `Ejecutor` | `Paso o Actividad` |
| :---- | :---- | :---- |
| **`2.1`** | `Servidor` | `Detecta la pérdida de conexión de la Orquita durante el aviso.` |
| **`2.2`** | `Servidor` | `Mantiene irrevocablemente el modo ESCAPE registrado en memoria y respaldado por las 3 capturas en disco.` |
| **`2.3`** | `Servidor` | `Configura para que al restablecerse el enlace, cualquier comando subsiguiente se emita bajo el estado ESCAPE.` |

[image1]: <data:image/png;base64,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BvMi8oijRfTx+eefb7Fv7NixLboOmWUJOFqGd955R79ffPHFOvpXj/wk9wzXIlmGPJW1Efbaay+100472ZuFhEyZMkWXqQlZC0IeoG/oHfonpAP1ImXqMjgKxpnIijzbLiJWn3/+eeX7hRdeqN9ffvnlyjYwjtZ7772no5BRoo4W3Y0QdbReeukl/X7aaafpNCD1yE9yj3AxkmXIU1njwJTnXXfd1d4spASVAGUcrTQEIW3QL/TM97FFLkM9SRm7BGsSthaNSYs8264PPvhAy8V/dujQobKdMVo9evRQRx55pO5CNI4W0EXIck89e/bU3y+99FK122676ckf7777rt4WdbT69++vNt98c3XMMcdUttUiP8k9wdVIliFPZW2NJ554Qp177rn2ZiFlKON11lnH3iwIqYF+iS1nD2VMvVk0WXcTVsOltitvwpW8Ci5HsgyuKCuhVHn6zRfK/Mwzz7Q3C0Ji0CfTLSLkA/VmUWWeRj6spLjSdhVBuJJbuB7JMrigrOQniZMzhjBsdIDh2WefrafKJp2N8/jjjyf+bdYMGDDA3lST5Zdf3t4UC8p89913tzcLQmLQp9Zs+bnnnlMdO3ZUa6+9tnr00Uft3ZnCQOPWYNYXNGKDURgIbWDZnW233Tayt3WS2DNlTj2aJ2nlw0qKC21XUYQreQQfIlmGopV17733tjfVBEfLxjhazz77rNpyyy11nhKzFuBFF12kunXrpq644gr9/dhjj9XvDGTkM8nn6F8HplDTd25ml5APhXNHc6dwvlVWWaVyPipt1i0jo/NHH32kBz9yDA3I7bffrs+35557qn//+9/q9ddf17Jut9126q9//aseRMlTKMnvzDV89913aoMNNtCL0ZpKfuedd9bThO++++7KdQDybrTRRmro0KGVQZdsI9lhtAziwHWZKcmCkAT0J44tM0Ylulg6g+VNqodrr71WbbrpphVdRK932WUX1aVLl8rg46iOA7aI/TB4GBsmZxE2TILJaE4jg3G0ouc2dO7cWdub7WgxnoZxOb/97W/1d2wZ28WWDdg6A6a//fbbFo4W9QPjbqgfANn5XyOPoZY923Vaa8S5B82QRT6spBTddhkYj8iMw2pcd911aq211lKnnnqq/o6un3TSSWq99dZTt9xyi3V0fNyQvEB8iWQZilRWu7JpjXqOFk+BU6dOVaeffrqu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>