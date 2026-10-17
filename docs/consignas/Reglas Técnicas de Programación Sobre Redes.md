# Proyecto Orquitas: Reglas Técnicas de Programación Sobre Redes

Guía técnica enfocada en arquitectura distribuida, diseño de protocolos, concurrencia multihilo, sincronización de secciones críticas y tolerancia a fallos.

---

## 1. División de Responsabilidades: Cliente vs. Servidor

El sistema opera bajo un esquema distribuido donde ninguno de los dos componentes es autosuficiente:

```text
[ ENTORNO FÍSICO ] ──> Sensores ──> [ CLIENTE (Robot) ]
                                          │
                                          │ (Bluetooth RFCOMM/SPP)
                                          ▼
                                 [ SERVIDOR CENTRAL ]
                                          │
    ┌─────────────────────────────────────┴─────────────────────────────────────┐
    ▼                                     ▼                                     ▼
[ Hilo IA (Neuroph) ]            [ Hilo de Capturas ]                  [ Hilo Auditor ]
Clasificación y Decisión          Escritura en disco                    Monitoreo periódico
    │                                     │                                     │
    ▼                                     └──────────────┬──────────────────────┘
Envío de Órdenes                                         ▼
hacia el Cliente                                  [ estomago.txt ]
                                             (Recurso Compartido / Sync)
```

| Aspecto | Cliente (Robot "Orquita") | Servidor Central (PC Externa) |
| :--- | :--- | :--- |
| **Rol conceptual** | Ejecutor físico (percibir y actuar) | Núcleo de cómputo ("cerebro") |
| **Entorno de ejecución** | Hardware embebido en el robot | Computadora externa |
| **Tareas principales** | • Lectura y validación de sensores.<br>• Transmisión de vectores de distancias.<br>• Recepción y ejecución física de órdenes.<br>• Mecanismo Fail-Safe ante desconexión. | • Recepción concurrente de telemetría.<br>• Clasificación de patrones con IA (Neuroph).<br>• Toma de decisiones de navegación.<br>• Persistencia física en `estomago.txt`.<br>• Supervisión mediante hilo auditor. |
| **Flujo de datos** | No interpreta el entorno; solo transmite y obedece | Sensores → Procesamiento → Clasificación → Decisión → Orden |

---

## 2. Protocolo de Comunicación (Bluetooth RFCOMM/SPP)

La comunicación inalámbrica requiere la definición de un **protocolo de aplicación propio**, actuando como lenguaje común entre ambos extremos.

### Mensajería Mínima Obligatoria

| Tipo de Mensaje | Origen ➔ Destino | Contenido / Función |
| :--- | :--- | :--- |
| **Telemetría** | Cliente ➔ Servidor | Vectores de distancias crudos obtenidos de los sensores |
| **Órdenes de navegación** | Servidor ➔ Cliente | Instrucciones de movimiento y giro para los motores |
| **Eventos** | Cliente ➔ Servidor | Notificación de captura física de un lobito |
| **Cambios de estado** | Bidireccional | Sincronización del estado operativo (ej. transición a Modo Escape) |

### Requisitos de Diseño del Protocolo
* **Estructura de tramas:** Definir claramente identificador de mensaje, delimitadores de inicio/fin y separadores de campos.
* **Validación:** Detección de tramas corruptas, incompletas, fuera de rango o con valores desconocidos.
* **Manejo de errores:** El protocolo no debe bloquearse ante ruidos o bytes perdidos en el canal serie.

---

## 3. Concurrencia y Sincronización: El "Estómago" (`estomago.txt`)

El archivo `estomago.txt` actúa como el registro del estado biológico de la Orquita. Constituye un **recurso compartido** en disco accedido simultáneamente por múltiples hilos del Servidor.

### El Problema de Concurrencia
* **Hilo Escritor (Capturas):** Al registrarse una presa, abre y escribe un nuevo registro en `estomago.txt`.
* **Hilo Lector (Auditor):** Consulta periódicamente el archivo para contar registros válidos y detectar la condición de "Panza Llena" (3 capturas).

> [!WARNING]
> **Condición de Carrera (Sección Crítica):**
> Si un hilo intenta leer el archivo mientras el otro está escribiendo en él, se producirán lecturas inconsistentes, excepciones de I/O o bloqueos. Todo acceso a `estomago.txt` debe estar estrictamente protegido mediante mecanismos de sincronización (bloques sincronizados, monitores o cerrojos/locks).

### ¿Por qué Persistencia en Disco y no una Variable en RAM?
* **Sobrevivencia a reinicios:** Si el proceso del Servidor crashea o se reinicia durante la prueba, cualquier variable en memoria volátil se pierde a cero.
* **Evidencia auditable:** El archivo físico `estomago.txt` permanece intacto en el sistema de archivos como prueba inmutable de las capturas conseguidas.
* **Regla estricta:** No está permitido utilizar variables en memoria como fuente de verdad para el conteo de capturas.

### El Proceso Auditor
* Se ejecuta como un hilo en segundo plano completamente desacoplado del flujo de navegación.
* Consulta `estomago.txt` a intervalos regulares dentro de la sección protegida.
* Al contabilizar exactamente 3 registros válidos, notifica al núcleo del Servidor para conmutar la conducta general hacia el **Modo Escape**.

---

## 4. Tolerancia a Fallos y Mecanismo Fail-Safe

* **Pérdida de enlace:** Ante caídas de la conexión Bluetooth, desconexión repentina o timeouts prolongados, el Cliente debe entrar automáticamente en **estado seguro** (Fail-Safe), deteniendo los motores inmediatamente.
* **Prevención de descontrol:** El robot nunca debe continuar moviéndose a ciegas si deja de recibir órdenes del Servidor Central.
* **Resiliencia en el Servidor:** El Servidor debe manejar excepciones de conexión sin cerrarse inesperadamente, permitiendo intentos de reconexión controlados.
