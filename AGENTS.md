# Contexto Técnico del Proyecto: Orquitas (Keiko)

Especificación técnica de ingeniería y reglas operativas para el ecosistema robótico autónomo distribuido "Proyecto Orquitas" (Programación Sobre Redes y Desarrollo de Sistemas, Ciclo 2026).

---

## 0. Flujo de Trabajo en Git (Obligatorio)

- **Prohibido pushear directamente a `main`** — aplica a todos los integrantes, incluidos los dueños del repositorio.
- Todo cambio nace en una **rama propia**: `feat/<tema>`, `fix/<tema>`, `docs/<tema>`, `chore/<tema>`.
- Los commits son **atómicos** y siguen **Conventional Commits** (descripciones en inglés).
- La integración a `main` es **exclusivamente vía Pull Request** (merge squash), idealmente con al menos una revisión de otro integrante.
- Prohibido `git push --force` sobre `main` o ramas compartidas.

---

## 1. Alcance y Restricciones Tecnológicas del Proyecto

- **Estructura del Repositorio:** El directorio raíz `keiko/` aloja subproyectos independientes (ej. `neuroph-train/`, firmware cliente, servidor cerebro). Cada subproyecto gestiona su propio ciclo de vida y empaquetado.
- **Lenguaje y Entorno:** Java puro (Java 21 LTS), con estructura de proyecto compatible y ejecutable en NetBeans.
- **Motor de Inteligencia Artificial:** Exclusivamente **Neuroph 2.98**.
- **Infraestructura de Red:**
  - Comunicación Robot-Servidor: Bluetooth RFCOMM / SPP (Serial Port Profile).
  - Cluster de entrenamiento distribuido: `neuroph.aguilucho.ar:443`.

---

## 2. Modelo de Dominio y Escenario ("La Bahía de Caza")

### 2.1 Elementos del Escenario
- **Muros Perimetrales:** Límites físicos de la pista.
- **Obstáculos Naturales:** Elementos estáticos y dinámicos a esquivar.
- **Lobitos (Presas Biológicas):** Canicas físicas distribuidas en la bahía.
- **Estrecho de Salida:** Apertura perimetral que conduce a aguas abiertas.

### 2.2 Ciclo Biológico de Tres Fases
1. **Fase 1: Búsqueda y Captura ("Panza Llena"):** Localizar y capturar exactamente **3 Lobitos**.
2. **Fase 2: Mutación Conductual:** Al verificar la tercera captura en almacenamiento persistente, cesar inmediatamente la búsqueda de presas y transicionar al modo escape.
3. **Fase 3: Escape:** Localizar el Estrecho de Salida y abandonar la Bahía de Caza de manera 100% autónoma.

---

## 3. Arquitectura Distribuida y Pipeline de Datos

```text
[Sensores Fisicos] 
       |
       v (Muestreo + Validacion de frontera)
[Cliente: La Orquita]
       |
       v (Bluetooth RFCOMM/SPP - Mensajes delimitados)
[Servidor Central: Cerebro PC]
       |
       +--> [Modulo Persistencia] <---> [estomago.txt] (Seccion Critica)
       |                                      ^
       |                                      | (Sondeo periodico)
       |                                [Hilo Auditor]
       +--> [Neuroph 2.98: Clasificador Red Neuronal]
       |
       v (Evaluacion de FSM y Toma de Decisiones)
[Comando de Navegacion]
       |
       v (Bluetooth RFCOMM/SPP)
[Cliente: Motores / Actuacion]
```

### 3.1 Responsabilidades del Cliente (Hardware Robot)
- **Muestreo y Validación en Frontera:** Muestreo periódico de sensores (ultrasonido, infrarrojo, captura óptica/mecánica). Filtrar ruido espurio antes de transmitir.
- **Empaquetado y Transmisión:** Enviar telemetría al Servidor mediante el protocolo serie acordado.
- **Actuación Motriz:** Recibir y ejecutar órdenes de velocidad y giro.
- **Fail-Safe Obligatorio:** Si la conexión Bluetooth se interrumpe o transcurren **> 500 ms** sin recepción de comando o heartbeat, el robot **debe detener inmediatamente los motores**. Queda prohibida la navegación ciega sin enlace activo.
- **Sin Lógica Estratégica:** El cliente no clasifica datos ni decide trayectorias complejas.

### 3.2 Responsabilidades del Servidor Central (Cerebro en PC)
- **Procesamiento Multihilo Concurrente:** Coordinar hilos de comunicación serie, inferencia neuronal, persistencia de datos y auditoría de estado.
- **Inferencia Neuronal (Neuroph 2.98):**
  - Clasificar vectores de entrada en: `LOBITO`, `MURO`, `OBSTACULO`, `SALIDA`, `ESPACIO_LIBRE`.
  - Prohibido el uso de umbrales fijos o comparaciones simples de distancia; la clasificación debe resolver la incertidumbre de datos físicos ruidosos mediante la red neuronal.
- **Toma de Decisiones:** Generar comandos de navegación en función de la clasificación y el estado del sistema.

---

## 4. Persistencia y Concurrencia Crítica ("El Estómago" y "La Linareada")

### 4.1 Invariantes de Persistencia
- **Archivo Físico Único:** `estomago.txt`.
- **Prohibición de Estado Volátil:** Está terminantemente prohibido utilizar variables en memoria (contadores primitivos, variables estáticas, atributos) para determinar si la Orquita alcanzó "Panza Llena".
- **Fuente de Verdad Persistente:** El número de registros válidos en `estomago.txt` es la única evidencia aceptada. Ante un reinicio forzado del servidor, el estado debe reconstruirse leyendo este archivo físico.

### 4.2 Control de Concurrencia y Sincronización
- **Recurso Compartido Disputado:** Dos hilos acceden concurrentemente a `estomago.txt`:
  1. **Hilo Escritor (Persistencia):** Escribe un nuevo registro tras la captura de un Lobito.
  2. **Hilo Lector (Auditor):** Sondea periódicamente el archivo para inspeccionar capturas acumuladas.
- **Mecanismo de Bloqueo Requerido:** Todas las operaciones de I/O sobre `estomago.txt` deben residir dentro de una sección crítica protegida con `ReentrantReadWriteLock` o monitores `synchronized(lock)`.
- **Vaciado Obligatorio de Buffer:** Todo registro escrito debe ejecutar inmediatamente `flush()` / `FileDescriptor.sync()` para asegurar el volcado físico a disco sin retención en buffers volátiles del SO.

### 4.3 Contrato del Hilo Auditor
- Se ejecuta como un hilo independiente en segundo plano.
- Al contabilizar exactamente **3 registros de captura válidos** en `estomago.txt`, emite la transición formal al **Modo Escape**.

---

## 5. Protocolo de Comunicación Bluetooth (RFCOMM / SPP)

- **Capa de Transporte:** Bluetooth Serie RFCOMM (SPP).
- **Delimitación de Mensajes:** Framing explícito (ej. salto de línea `\n` o longitud prefijada) para resolver la fragmentación del buffer serie.
- **Tratamiento Defensivo:** Descartar tramas parciales o corruptas sin generar excepciones no controladas.

### Tipos de Mensajes Requeridos
| Tipo | Origen | Propósito |
| :--- | :--- | :--- |
| `TELEMETRY` | Cliente -> Servidor | Vector numérico normalizado de distancias y estados de sensores |
| `COMMAND` | Servidor -> Cliente | Órdenes motrices directas (dirección, velocidad, parada) |
| `EVENT` | Cliente -> Servidor | Notificación de captura física o contacto detectado |
| `STATE_CHANGE` | Servidor -> Cliente / Auditor | Transición de estado (`SEARCH`, `ESCAPE`, `EXITED`, `FAILSAFE`) |
| `HEARTBEAT` | Bidireccional | Verificación de enlace y cálculo de liveness / timeout (< 500 ms) |

---

## 6. Máquina de Estados Finita (FSM)

| Estado | Condición de Entrada | Objetivo Operativo | Transición Siguiente |
| :--- | :--- | :--- | :--- |
| `BÚSQUEDA` | Inicio o captura < 3 | Explorar y rastrear vectores asociados a presas | A `APROXIMACIÓN` al detectar presas |
| `APROXIMACIÓN` | Detección clasificada de Lobito | Reducir distancia evitando colisiones con entorno | A `CAPTURA` al alcanzar rango físico |
| `CAPTURA` | Contacto / sensor de retención | Asegurar Lobito, escribir captura en `estomago.txt` | A `BÚSQUEDA` (si registros < 3) o a `ESCAPE` (auditadas 3) |
| `ESCAPE` | Notificación del Hilo Auditor (3 capturas) | Localizar el Estrecho de Salida, ignorar presas | A `FINALIZACIÓN` al cruzar salida |
| `FINALIZACIÓN` | Abandono verificado de la bahía | Detener motores, misión completada | Estado terminal |
| `ERROR_SEGURO` | Timeout enlace (> 500 ms) o error crítico | Detención motriz inmediata (fail-safe) | Retorno a estado previo si reconecta |

---

## 7. Reglamento de Competencia y Matriz de Puntuación

### 7.1 Etapas de la Competencia
1. **Clasificación:** Pasada individual contra reloj. Tiempo límite: **7 minutos**. Los 4 mejores puntajes avanzan.
2. **Semifinales:** Llaves directas (1.° vs 4.° y 2.° vs 3.°). Dos robots en simultáneo en la bahía. Tiempo límite: **10 minutos**.
3. **Final:** Enfrentamiento directo entre ganadores de semifinales. Tiempo límite: **10 minutos**.
4. **La Recontrafinalísima:** Duelo único entre Campeón Turno Tarde y Campeón Turno Noche. Tiempo límite: **10 minutos**.

### 7.2 Matriz de Puntuación Oficial
| Concepto | Puntos | Condición |
| :--- | :---: | :--- |
| Captura del 1.er Lobito | **+5** | Captura exitosa verificada |
| Captura del 2.º Lobito | **+10** | Requiere retener el 1.º |
| Captura del 3.er Lobito | **+15** | Requiere retener el 1.º y el 2.º |
| Activación verificada del Modo Escape | **+15** | Auditada mediante `estomago.txt` |
| Abandono completo de la Bahía | **+30** | Salida total a través del Estrecho |
| Bonificación por Tiempo: Minuto 0:00 - 1:00 | **+15** | Misión completada dentro del 1.er minuto |
| Bonificación por Tiempo: Minuto 1:01 - 4:00 | **+10** | Misión completada entre 2.º y 4.º minuto |
| Bonificación por Tiempo: Minuto 4:01 - 10:00 | **+5** | Misión completada entre 4.º y 10.º minuto |
| Colisión contra muros perimetrales | **-5** | Penalización por impacto |
| Colisión contra obstáculos | **-5** | Penalización por impacto |
| Pérdida / escape de un Lobito | **-5** | Penalización por pérdida de presa retenida |

### 7.3 Restricciones Operativas en Pista
- **Autonomía Absoluta:** Prohibido tocar o asistir físicamente al robot una vez iniciada la prueba. Cualquier contacto manual acarrea descalificación inmediata de la pasada.
- **Prohibición de Entrada Manual:** Prohibido inyectar órdenes motrices por consola o alterar `estomago.txt` externamente.
- **Límite de Inactividad (10 Segundos):** Si el robot permanece inactivo o detenido por **más de 10 segundos continuos**, la prueba finaliza automáticamente, computando los puntos acumulados hasta ese instante.
- **Criterios de Desempate Jerárquicos:**
  1. Mayor cantidad de Lobitos capturados.
  2. Alcance verificado del Modo Escape.
  3. Mayor proximidad avanzada hacia el Estrecho de Salida.
  4. Prueba de desempate en pista.

---

## 8. Hitos del Proyecto y Requisitos de Entrega (PSR / DS)

- **Gestión:** Metodología SCRUM con dailies documentados en minutas de reunión al inicio de cada clase.

### Hito 1: Planeamiento y Modelado Formal
- Diagramas UML obligatorios:
  - Casos de Uso (Cliente, Servidor, Auditor).
  - Clases (arquitectura estática, persistencia, sincronización, Neuroph).
  - Secuencia (flujos normales y alternativos de telemetría, captura y escape).
  - Despliegue (nodos físicos, interfaces de comunicación serie/Bluetooth).
  - Estados (FSM completa con eventos disparadores).
- Plano técnico de circuito y diseño exterior aprobado.
- Documento preliminar de arquitectura Cliente-Servidor y estrategia de red neuronal.

### Hito 2: Integración Técnica
- Enlace Bluetooth SPP operativo entre Cliente y Servidor con protocolo custom.
- Persistencia en `estomago.txt` bajo sección crítica sincronizada.
- Hilo Auditor funcional activando la conmutación de estado.
- Mecanismo Fail-Safe activo ante pérdida de enlace.
- Clasificador Neuroph 2.98 funcional con lecturas reales de sensores.

### Hito 3: Entrega Final y Defensa Técnica
- Código fuente Java completo y estructurado como proyecto NetBeans.
- Modelo neuronal `.nnet` calibrado y entrenado.
- Repositorio con historial atómico de desarrollo.
- Carpeta técnica integral con minutas, evidencias de pruebas e informe de incidencias corregidas.
- **Defensa Oral:** Evaluación individual y colectiva sobre arquitectura, sincronización concurrente, persistencia, protocolo de red e inferencia IA ante el tribunal docente.
