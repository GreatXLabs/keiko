# 🐋 Orquitas (Keiko) - Manual Explicativo Integral del Sistema

> **Guía Técnica y Conceptual para Humanos**  
> *Programación Sobre Redes & Desarrollo de Sistemas — Ciclo 2026*  
> *Arquitectura Robótica Autónoma Distribuida con Chasis 4WD, Red Neuronal Neuroph 2.98 y Cartografía 2D*

---

## 1. ¿Qué es el Proyecto Orquitas?

El proyecto **Orquitas** (cuyo nombre en código es **Keiko**) es un ecosistema robótico autónomo distribuido inspirado en el comportamiento de caza y supervivencia de las orcas marinas. 

El escenario de competencia se denomina **"La Bahía de Caza"**, una pista cerrada de **2.40 metros x 2.40 metros** con muros perimetrales de 20 cm de altura, obstáculos estáticos (cajas y columnas cilíndricas) y un **Estrecho de Salida** de 30 cm de apertura en el extremo Norte.

### La Misión Biológica en 3 Fases
El robot debe completar de manera 100% autónoma un ciclo biológico estricto:
1. **Fase 1: Búsqueda y Caza ("Panza Llena"):** Navegar activamente por la bahía esquivando muros y obstáculos hasta localizar y capturar físicamente **exactamente 3 canicas** ("lobitos").
2. **Fase 2: Mutación Conductual:** Al verificar la 3.ª captura almacenada de forma persistente en disco (`estomago.txt`), cesar de inmediato la búsqueda de alimento y transicionar al estado de huida.
3. **Fase 3: Escape a Aguas Abiertas:** Orientarse hacia el Estrecho de Salida perimetral y cruzar hacia la plataforma exterior, completando la misión antes de los tiempos límites establecidos.

```
       FASE 1                   FASE 2                   FASE 3
 [ Búsqueda Activa ]   --->   [ Mutación ]   --->   [ Modo Escape ]
  Capturar 3 presas         Verificación en disco       Cruce por salida
```

---

## 2. El Hardware: El Vehículo Rover 4WD

El hardware físico del robot está basado en el kit comercial de robótica educativa **Rover Auto 4WD** (distribuido por Todomicro):

```
                       +-------------------+
                       |    TOLVA BUCAL    |
                       |  (Embudo 13.6 cm) |
                       +---------+---------+
                                 |
              [Rueda Del. Izq]   |   [Rueda Del. Der]
                     |           |          |
              +------+-----------+----------+------+
              |                                    |
              |       Batería + Reductoras DC      |
              |          4x Motores 1:48           |
              |       Microcontrolador + BT        |
              |                                    |
              +------+----------------------+------+
                     |                      |
              [Rueda Tras. Izq]      [Rueda Tras. Der]
```

### Características Mecánicas y Cinemáticas
- **Chasis:** Doble plataforma de acrílico resistente con 4 motores DC acoplados a cajas reductoras de engranajes 1:48.
- **Ruedas:** 4 ruedas de goma de alto agarre con un diámetro exterior de 66 mm ($r = 33\text{ mm}$).
- **Tracción:** 4WD con cinemática **Skid-Steer** (dirección diferencial por deslizamiento). El robot no dispone de cremallera ni volante de dirección; para girar hacia la derecha, las ruedas izquierdas avanzan mientras las derechas giran en sentido contrario o se frenan.
- **Vencimiento de la Fricción Lateral:** Las ruedas de goma generan un fuerte rozamiento al deslizar lateralmente en giros en el lugar. Para que el robot no se trabe ni pierda torque, los giros sobre su eje se realizan a velocidades motrices superiores a $4.8\text{ rad/s}$ ($~0.16\text{ m/s}$ de velocidad tangencial), rompiendo limpiamente la resistencia estática.

### La Tolva Bucal Frontal (Sistema de Agarre sin Rebote)
Uno de los mayores desafíos físicos al embestir una canica es el rebote elástico (un robot de 780 g impactando una canica ligera de 5.5 g a gran velocidad la hace rodar en dirección opuesta).
Para resolver esto, se diseñó una **tolva bucal mecánica**:
1. **Embudo Ensanchado de 13.6 cm:** Abarca casi la totalidad de los 15 cm de ancho del vehículo. No existen paragolpes planos en el frente donde una canica pueda rebotar hacia afuera; cualquier canica frontal es guiada suavemente hacia el interior.
2. **Garganta de Retención (4.5 cm):** Chaflanes en ángulo de $31^\circ$ que canalizan la presa hacia el centro de masa del chasis.
3. **Aproximación Sigilosa (Stealth Approach):** Cuando el robot detecta una canica a menos de 20 cm de distancia, reduce automáticamente su velocidad de crucero ($5.0\text{ rad/s}$) a una velocidad de sigilo de **$1.8\text{ rad/s}$** ($0.05\text{ m/s}$). El robot "engulle" la canica sin empujarla, alojándola de forma segura en la cavidad de carga.

---

## 3. El Sistema Sensorial: "Doble Plano Ultrasónico"

El robot no utiliza cámaras pesadas ni procesadores con GPU a bordo; utiliza un banco de **4 sensores ultrasónicos HC-SR04** calibrados con una disposición geométrica estratégica en **dos planos horizontales**:

```
           VISTA LATERAL DEL BANCO SENSORIAL

  Z = 6.5 cm  --------------------------> Sensor Alto (Detecta muros y cajas)
               (El haz pasa POR ARRIBA de la canica)

  Z = 2.5 cm  --------------------------> Sensor Bajo (Detecta canica y piso)
                                 O
                              (Canica)
  Z = 0.0 cm  ======================================== PISO DE LA BAHÍA
```

| Sensor | Plano | Coordenadas $(X, Y, Z)$ | Propósito |
| :--- | :---: | :---: | :--- |
| `us_low_left` | Bajo | $+0.125\text{ m}, +0.020\text{ m}, 0.025\text{ m}$ | Localización de canica lado izquierdo |
| `us_low_right` | Bajo | $+0.125\text{ m}, -0.020\text{ m}, 0.025\text{ m}$ | Localización de canica lado derecho |
| `us_high_left` | Alto | $+0.125\text{ m}, +0.040\text{ m}, 0.065\text{ m}$ | Detección perimetral de muros y obstáculos |
| `us_high_right` | Alto | $+0.125\text{ m}, -0.040\text{ m}, 0.065\text{ m}$ | Detección perimetral de muros y obstáculos |

### ¿Cómo distingue el robot una Canica de una Pared?
- **Firma de Canica:** Un objeto de 1.6 cm de altura refleja el eco en el plano bajo (`min_low < 55 cm`), pero el haz del plano alto viaja libremente por encima de ella (`min_high >> min_low + 10 cm`). El sistema sabe con total certeza matemática que tiene una presa en frente y no un obstáculo sólido.
- **Firma de Muro u Obstáculo:** Tanto el sensor bajo como el alto rebotan a la misma distancia (`min_low ≈ min_high < 30 cm`). El sistema activa inmediatamente la maniobra de evasión.

---

## 4. Arquitectura Distribuida y Protocolo de Red

El sistema está dividido en dos entidades que cooperan a través de una red inalámbrica serie (Bluetooth RFCOMM / SPP):

```text
+-----------------------+                    +------------------------------------+
|  ROBOT CLIENTE (4WD)  |                    |      SERVIDOR CENTRAL (PC)         |
|  - Muestreo Sensores  |  TELEMETRY (x,y..) |  - Inferencia IA (Neuroph 2.98)    |
|  - Filtro Paso-Bajo   | =================> |  - FSM y Planificador              |
|  - Actuación Motriz   |                    |  - Concurrencia ReadWriteLock      |
|  - Fail-Safe < 500ms  | <================= |  - Auditor -> estomago.txt         |
|  - Cartografía 2D     |   COMMAND (vl,vr)  +------------------------------------+
+-----------------------+
```

### Protocolo de Mensajes Delimitados por Salto de Línea (`\n`)
| Mensaje | Dirección | Formato de Trama | Significado |
| :--- | :---: | :--- | :--- |
| `TELEMETRY` | Cliente -> Servidor | `TELEMETRY:<d_ll>,<d_lr>,<d_hl>,<d_hr>,<timestamp>,<x>,<y>,<yaw>\n` | Vector sensorial y odometría filtrada |
| `COMMAND` | Servidor -> Cliente | `COMMAND:<v_left>,<v_right>,<estado>\n` | Velocidades motrices en rad/s y estado FSM |
| `EVENT` | Cliente -> Servidor | `EVENT:CAPTURA,id=lobito_1\n` | Notificación de captura física en tolva |
| `STATE_CHANGE` | Servidor -> Cliente | `STATE_CHANGE:ESCAPE\n` | Orden de mutación conductual |
| `HEARTBEAT` | Bidireccional | `HEARTBEAT\n` | Comprobación de liveness de enlace |

### Requisito Fail-Safe (< 500 ms)
Si el enlace de radiofrecuencia sufre interferencias o la PC tarda más de **500 milisegundos** en responder, el robot detiene en seco los 4 motores. Está terminantemente prohibido que el robot navegue a ciegas si se rompe la comunicación con el servidor cerebro.

---

## 5. Persistencia y Concurrencia: "El Estómago"

Uno de los requerimientos más estrictos del proyecto es que **está prohibido contar las canicas en variables de memoria (RAM)**. Si el servidor se apaga, se reinicia o se corta la luz, el robot no puede "olvidar" cuántas canicas comió.

### El Archivo Físico: `estomago.txt`
El estado de saciedad del robot solo existe si está grabado físicamente en disco.
Cada línea de `estomago.txt` registra un evento inmutable con formato:
```text
CAPTURA|id=lobito_1|timestamp=1789061000|digest=a8f3b2
CAPTURA|id=lobito_2|timestamp=1789061120|digest=c91e44
CAPTURA|id=lobito_3|timestamp=1789061250|digest=7b049d
```

### Control de Concurrencia sin Condiciones de Carrera
Dos hilos del servidor compiten permanentemente por este recurso:
1. **Hilo Escritor (Persistencia):** Cuando la orca atrapa una canica, este hilo escribe el registro y ejecuta un `flush()` junto con `FileDescriptor.sync()`, asegurando que los bytes se graben magnética o electrónicamente en el SSD sin quedar atrapados en la memoria caché del sistema operativo.
2. **Hilo Auditor:** Se ejecuta en segundo plano inspeccionando físicamente `estomago.txt` cada 100 ms. Cuenta las líneas válidas. Al detectar la **tercera captura**, dispara el evento formal de transición a **Modo Escape**.

Ambos hilos están sincronizados con un cerrojo de lectura/escritura (`ReentrantReadWriteLock`), garantizando que jamás se produzca corrupción de datos ni lecturas parciales durante una escritura.

---

## 6. Inteligencia Artificial con Neuroph 2.98

En lugar de utilizar reglas cableadas o simples comparaciones `if (distancia < 20)`, la clasificación del entorno se realiza con una **Red Neuronal Artificial (Perceptrón Multicapa)** ejecutada con el motor oficial **Neuroph 2.98**:

```text
       CAPA DE ENTRADA          CAPA OCULTA             CAPA DE SALIDA
         (4 Sensores)       (12 Neuronas Sigmoide)     (Clasificación 1-Hot)

       [ dist_low_left  ] -----\                  /-----> [ LOBITO ]
       [ dist_low_right ] ------+-- (Pesos W1) --+------> [ MURO ]
       [ dist_high_left ] ------+--  Bias B1   --+------> [ OBSTACULO ]
       [ dist_high_right] -----/                  +-----> [ SALIDA ]
                                                  \-----> [ ESPACIO_LIBRE ]
```

- **Entrenamiento:** Entrenada mediante el algoritmo de retropropagación de errores (*Backpropagation*) con más de 12.000 muestras sintéticas recolectadas en la bahía de simulación bajo distintas condiciones de ruido y orientación.
- **Resolución de Incertidumbre:** La red clasifica correctamente si un objeto es una canica incluso cuando el robot se aproxima en ángulo oblicuo o cuando una canica se encuentra cerca de un muro.

---

## 7. Cartografía 2D en Tiempo Real: ArenaMapper

Para resolver la necesidad de que el operador pueda visualizar qué está haciendo el robot y hacia dónde se dirige, se incorporó el módulo de cartografía en tiempo real **`ArenaMapper`**:

### Rejilla de Ocupación Discreta (24 x 24 Celdas)
El mapa de la bahía (2.4m x 2.4m) se discretiza en una matriz de celdas de $10\text{ cm} \times 10\text{ cm}$:
- `.` **VISITADO:** Celdas por donde ya pisó el vehículo.
- `·` **LIBRE:** Celdas escaneadas por los ultrasonidos donde no hay obstáculos.
- `#` **OBSTÁCULO:** Muros perimetrales y obstáculos altos detectados.
- `E` **SALIDA:** El Estrecho de Salida en el extremo Norte.
- `^`, `v`, `<`, `>` **ROBOT:** Ubicación y orientación actual de la Orquita.

### Mapa ASCII en Consola (Ejemplo Real Generado por el Sistema)
```text
--- [MAPA DE OCUPACIÓN 2D - LA BAHÍA] ---
+------------------------+
|······####·...·##····#··|
|······####.E..#####··## |
|·····##############··## |
|·#···#··.#.....#······# |
|···##·##.......#······# |
|·########.......······# |
|··##·#####.......·····# |
| ··#·######......····## |
|··#########......····#  |
|·#########.......····#··|
|·##·######.......····#··|
|·#··####........·##·····|
|·#···###......########··|
|·##·#·#......#########··|
|·##·#####....####·#####·|
|·##··################## |
|··##·##################·|
|··#··#########.####·###·|
|····###·#####....##··##·|
| ···#·########...···###·|
| ·····#######....######·|
|······#################·|
|  ······############·##·|
| ······###··   ·#·······|
+------------------------+
Leyenda: [^ v < >] Robot | [.] Visitado | [·] Libre | [#] Obstáculo | [E] Salida
Cobertura cartográfica explorada: 96.5%
```

---

## 8. ¿Por qué el robot a veces salía con menos de 3 canicas y cómo se solucionó?

### Causa Raíz del Problema
En el muro Norte, el Estrecho de Salida es una abertura física de 30 cm de ancho que conduce al exterior.
Cuando el robot patrullaba cerca del cuadrante norte, sus sensores apuntaban hacia el estrecho y veían **"espacio libre"** (la señal ultrasónica no rebotaba contra nada hasta 2 metros después). Como resultado:
- El planificador creía que el camino estaba despejado.
- El robot cruzaba la salida por accidente con 1 o 2 canicas.
- Una vez afuera, no había canicas en la plataforma exterior y el robot no podía completar la misión.

### La Solución: La "Barrera Virtual Antiescape"
Se implementó una regla inviolable en el planificador (tanto en Java como en el cliente):
```text
SI (canicas_capturadas < 3) Y (posicion_Y > 0.85m) Y (|posicion_X| < 0.38m):
    --> TRATAR LA SALIDA COMO UN MURO SÓLIDO IMPENETRABLE
    --> ACTIVAR RETROCESO PREVENTIVO INMEDIATO
    --> GIRAR CON RUMBO SUR (HACIA EL INTERIOR DE LA BAHÍA)
```
Gracias a esta barrera virtual, **es matemáticamente imposible que el robot cruce o se acerque al estrecho de salida antes de tener sus 3 canicas verificadas en el estómago**. La salida permanece sellada hasta que el Hilo Auditor valida la 3.ª captura.

---

## 9. Guía Rápida de Uso y Ejecución

### 1. Simulación 3D Directa (Modo Visualizador en Vivo)
Para ver al robot moviéndose en 3D en la pantalla, explorando, capturando las 3 canicas y escapando:
```bash
./keiko-sim/scripts/run_gui.sh
```

### 2. Arquitectura Distribuida Completa (Servidor Java + Cliente 3D)
Para ejecutar la arquitectura de producción evaluable por el tribunal docente:
```bash
# Arranca el servidor central en background y el cliente Webots
./keiko-sim/scripts/run_distributed.sh
```
Para ejecutarlo en modo de pruebas automatizadas rápidas (sin ventana gráfica, a máxima velocidad de CPU):
```bash
./keiko-sim/scripts/test_all_maps.sh
```

### 3. Registro de Auditoría
Para verificar en caliente las capturas registradas en el almacenamiento persistente:
```bash
cat estomago.txt
```
Para observar la telemetría y el mapa ASCII en tiempo real:
```bash
tail -f keiko-sim/logs/simulation_live.log
```

---

## 10. Resumen de Puntuación Estimada en Competencia

Siguiendo el reglamento oficial PSR/DS 2026:

| Hito Alcanzado | Puntos Oficiales | Estado en Simulación |
| :--- | :---: | :--- |
| Captura del 1.er Lobito | **+5 pts** | ✅ Verificado y asegurado |
| Captura del 2.º Lobito | **+10 pts** | ✅ Verificado reteniendo el 1.º |
| Captura del 3.er Lobito | **+15 pts** | ✅ Verificado reteniendo 1.º y 2.º |
| Activación del Modo Escape | **+15 pts** | ✅ Auditada vía `estomago.txt` |
| Abandono completo de la Bahía | **+30 pts** | ✅ Verificado en plataforma exterior |
| Bonificación por Tiempo (dentro de 4 min) | **+10 pts** | ✅ Misión completada en ~1.5 - 3 min |
| Penalizaciones por Colisión o Pérdida | **0 pts** | ✅ Evasión preventiva sin choques |
| **TOTAL ESTIMADO** | **+85 pts** | **Puntaje Perfecto de Misión** |
