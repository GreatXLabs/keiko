# Proyecto Orquitas: Consigna y Alcances Técnicos

Guía de arquitectura distribuida, requerimientos técnicos, metodología de trabajo e hitos de entrega para el desarrollo del ecosistema robótico autónomo.

---

## 1. Arquitectura del Sistema Distribuido

El proyecto consiste en un sistema distribuido compuesto por dos subsistemas comunicados de forma inalámbrica:

```text
  [ CLIENTE: ORQUITA (Robot) ]                  [ SERVIDOR CENTRAL (PC Externa) ]
+-------------------------------+              +----------------------------------+
| - Sensores de distancia       |              | - Cerebro / Toma de decisiones   |
| - Motores y navegación física |   Bluetooth  | - Clasificación IA (Neuroph)     |
| - Detección de pérdida enlace | <=========>  | - Auditor de persistencia        |
| - Estado seguro ante error    | (RFCOMM/SPP) | - Hilos concurrentes             |
+-------------------------------+              +----------------------------------+
                                                                |
                                                                v
                                                        [ estomago.txt ]
                                                   (Persistencia biológica)
```

| Componente | Rol | Funciones Principales |
| :--- | :--- | :--- |
| **Cliente (Robot "Orquita")** | Agente físico | Captura lecturas de sensores, envía datos al servidor y ejecuta órdenes de movimiento. Debe entrar en estado seguro si pierde conexión. |
| **Servidor Central (PC Externa)** | Cerebro y procesamiento | Analiza lecturas con Inteligencia Artificial, toma decisiones de navegación, administra hilos concurrentes y supervisa la persistencia. |
| **Enlace de Comunicación** | Conectividad | Inalámbrico mediante **Bluetooth** bajo perfil de puerto serie (**RFCOMM / SPP**), utilizando un protocolo propio de intercambio. |

---

## 2. Requerimientos Técnicos Obligatorios

### Comunicación y Protocolo Propio
* **Diseño propio:** Cada equipo debe definir el formato de las tramas de comunicación entre Cliente y Servidor.
* **Mensajería mínima requerida:**
  1. Envío continuo de vectores de distancias de sensores.
  2. Notificación de captura de lobitos.
  3. Recepción de comandos de navegación hacia los motores.
  4. Sincronización de estados del sistema.
* **Tolerancia a fallos:** Ante una desconexión inesperada o caída de enlace, el robot debe adoptar de inmediato un estado seguro (detención total, evitando movimientos erráticos).

### Inteligencia Artificial (Neuroph)
* **Reconocimiento de patrones:** La interpretación de sensores no puede basarse en reglas fijas ni comparaciones exactas debido al ruido y variaciones del entorno físico.
* **Biblioteca obligatoria:** Red Neuronal Artificial implementada con la biblioteca **Neuroph**.
* **Entrenamiento y validación:** El modelo debe entrenarse con datos reales de calibración de sensores para discriminar entre lobitos (canicas), obstáculos y muros. Se debe documentar la evidencia del entrenamiento.

### Persistencia del Estado Biológico (`estomago.txt`)
* **Prohibición de memoria volátil:** El estado de capturas no puede residir en una simple variable en memoria RAM.
* **Archivo persistente:** Cada captura confirmada debe escribirse físicamente en un archivo denominado `estomago.txt`.
* **Proceso Auditor Independiente:** Un hilo/proceso independiente debe monitorear continuamente `estomago.txt`. Al constatar 3 capturas válidas, notifica al sistema para mutar de conducta y activar el **Modo Escape**.

### Concurrencia y Protección de Recursos
* **Multihilo:** Ejecución simultánea de tareas críticas (recepción de datos, inferencia de red neuronal, escritura en disco, supervisión del auditor).
* **Sincronización:** Implementación obligatoria de mecanismos de exclusión mutua para proteger recursos compartidos y evitar condiciones de carrera.

---

## 3. Ciclo de Vida y Máquina de Estados

La conducta operativa del robot debe estructurarse obligatoriamente bajo los siguientes estados:

| Estado | Descripción | Transición / Evento de Cambio |
| :--- | :--- | :--- |
| **Búsqueda** | Exploración de la bahía rastreando patrones de presas | Al identificar un posible lobito |
| **Aproximación** | Maniobra orientada hacia la posición detectada | Al quedar en distancia de captura |
| **Captura** | Ejecución de toma y registro físico en `estomago.txt` | Al confirmar captura y escribir en disco |
| **Escape** | Búsqueda exclusiva del estrecho de salida (ignora presas) | Activado por el auditor al leer 3 registros |
| **Finalización** | Detención tras cruzar la salida de la bahía | Al abandonar completamente el área |
| **Error Seguro** | Detención preventiva por anomalía | Pérdida de enlace Bluetooth o falla crítica |

> [!IMPORTANT]
> Ninguna Orquita puede iniciar su construcción física sin contar previamente con la documentación técnica y el plano del circuito/diseño exterior debidamente aprobados por el cuerpo docente.

---

## 4. Gestión del Proyecto y Metodología

* **Conformación de equipos:** Grupos de **4 integrantes**.
* **Metodología SCRUM:** Realización obligatoria de un *daily* con minuta de reunión al comienzo de cada clase.
* **Responsabilidad compartida:** Si bien existen roles por área, todos los miembros deben comprender la arquitectura completa; las evaluaciones individuales pueden indagar sobre cualquier módulo.
* **Evidencias requeridas en repositorio:** Control de versiones con historial de commits, asignación de tareas, seguimiento de incidencias y registro de avances.

---

## 5. Cronograma de Entregables e Hitos

| Hito | Nombre | Contenido y Entregables |
| :--- | :--- | :--- |
| **Hito 1** | Planeamiento y Documentación | • Diagramas UML: Casos de Uso, Clases, Secuencia, Despliegue y Estados.<br>• Arquitectura Cliente-Servidor y protocolo preliminar.<br>• Estrategia de entrenamiento con Neuroph.<br>• Plano de circuito y diseño exterior aprobado. |
| **Hito 2** | Integración Técnica | • Enlace Bluetooth operativo e intercambio de mensajes con protocolo propio.<br>• Persistencia efectiva en `estomago.txt` y proceso auditor en funcionamiento.<br>• Concurrencia con sincronización de recursos compartidos.<br>• Manejo de desconexión y demo preliminar de clasificación con Neuroph. |
| **Hito 3** | Entrega Final | • Código fuente completo en proyecto NetBeans documentado.<br>• Repositorio actualizado con historial de desarrollo e incidencias resueltas.<br>• Modelo de Red Neuronal entrenado y carpeta técnica completa.<br>• Evidencias de pruebas de campo. |

---

## 6. Pruebas de Campo y Criterios de Evaluación

### Pruebas de Campo en la Bahía
1. **Inferencia y Autonomía:** Clasificación correcta de objetos en tiempo real y navegación sin intervención humana.
2. **Tolerancia a Fallos:** Respuesta segura y controlada ante simulaciones de corte de enlace o pérdida de señal.
3. **Concurrencia y Persistencia:** Estabilidad de hilos y verificación de integridad en `estomago.txt`.
4. **Cumplimiento de la Misión:** Demostración del ciclo completo (captura de lobitos, cambio de estado y escape).

### Defensa Técnica y Aprobación
* Instancia oral ante tribunal docente evaluando comprensión conceptual, justificación de diseño y participación individual.
* **La aprobación integral no depende únicamente de la posición en la competencia**, sino de la solidez técnica del diseño, el código, la documentación y el proceso metodológico seguido a lo largo del cuatrimestre.
