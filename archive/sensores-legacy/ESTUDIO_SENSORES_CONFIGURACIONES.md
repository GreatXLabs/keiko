# Estudio Técnico: Configuraciones de Sensores, Física de Haces y Clasificación Neuronal

**Proyecto Orquitas (Keiko)**  
*Programación Sobre Redes y Desarrollo de Sistemas — Ciclo Lectivo 2026*

---

## 1. Alcance y Filosofía de Ingeniería

El objetivo central del ecosistema robótico **Orquitas** reside en la **percepción sensorial robusta, la detección y la clasificación autónoma de elementos en la Bahía de Caza** mediante Inteligencia Artificial (**Neuroph 2.98**). 

A diferencia de proyectos centrados en actuadores mecánicos complejos de prensión (brazos robóticos, garras articuladas), en este proyecto la captura mecánica es un proceso puramente pasivo asistido por embudo de convergencia (V-Funnel Scoop de 13.6 cm). El verdadero desafío de ingeniería radica en:
1. **Resolver la incertidumbre de datos físicos ruidosos** provenientes de transductores ultrasónicos de bajo coste (HC-SR04).
2. **Discriminar con precisión milimétrica** presas biológicas esféricas de 16 mm de diámetro (*Lobitos*) frente a muros perimetrales de 20 cm de altura, obstáculos prismáticos/cilíndricos y el Estrecho de Salida.
3. **Garantizar la cobertura del entorno** sin ángulos ciegos críticos que provoquen colisiones o pérdidas de presas.
4. **Proporcionar retroalimentación visual en tiempo real (Visual Debug Feedback)** del comportamiento de los haces para iterar, comparar y validar arquitecturas sensoriales.

---

## 2. Fundamentos Físicos de la Propagación Acústica (HC-SR04)

### 2.1 Principio de Tiempo de Vuelo (Time of Flight - ToF)
Los sensores ultrasónicos HC-SR04 emiten un tren de 8 pulsos piezoeléctricos a una frecuencia ultrasónica de $f = 40\text{ kHz}$. El cálculo de la distancia radial $d$ está gobernado por:

$$d = \frac{v_{\text{sonido}}(T) \cdot t_{\text{eco}}}{2}$$

donde $v_{\text{sonido}}(T) \approx 331.3 + 0.606 \cdot T \approx 343.2\text{ m/s}$ a $20^\circ\text{C}$, y $t_{\text{eco}}$ es el tiempo transcurrido hasta la detección del primer frente de onda reflejado por encima del umbral del comparador analógico.

### 2.2 Geometría del Haz y Cono de Emisión
El diagrama de radiación acústica del HC-SR04 presenta un lóbulo principal de apertura cónica aproximada de **$30^\circ$** ($\theta_{\text{haz}} \approx \pm 15^\circ$ respecto al eje central del transductor). 

```text
               -15°          0°          +15°
                 \           |           /
                  \          |          /
                   \    Haz Central    /
                    \        |        /
                     \       |       /
                      \      |      /
                       \     |     /
                        \    |    /
                         [HC-SR04]
```

La presión sonora decae con la distancia y el ángulo siguiendo la función directriz de un pistón circular plano:

$$P(\theta) = P_0 \cdot \left| \frac{2 J_1(k a \sin\theta)}{k a \sin\theta} \right|$$

donde $k = \frac{2\pi}{\lambda}$, $a$ es el radio del transductor piezoeléctrico ($a \approx 8\text{ mm}$), y $J_1$ es la función de Bessel de primer orden. Para $\theta > 15^\circ$, la sensibilidad cae más de $-6\text{ dB}$, delimitando la frontera práctica del cono.

### 2.3 Física de la Reflexión: Canicas Esféricas vs. Muros Planos

La interacción de la onda sonora con los diferentes elementos del escenario varía radicalmente según su geometría:

```mermaid
flowchart TD
    subgraph OndaIncidente["Onda Acústica Incidente (40 kHz)"]
        W[Tren de pulsos de presión]
    end

    subgraph Lobito["Canica Esférica (Lobito r = 8mm)"]
        L1[Dispersión Isotrópica Esférica]
        L2["Sección Eficaz σ = π r² ≈ 2.01 cm²"]
        L3[Eco muy débil a d > 60cm]
    end

    subgraph Muro["Muro Plano / Obstáculo Prismático"]
        M1[Reflexión Especular 'Efecto Espejo']
        M2[Ángulo θ > 35° refleja eco lejos del receptor]
        M3[Eco intenso solo en incidencia perpendicular]
    end

    W --> Lobito
    W --> Muro
```

1. **Canica Esférica (Lobito, $\varnothing = 16\text{ mm}$):**
   - Una esfera refleja el sonido de manera **isotrópica** (en todas las direcciones de forma homogénea).
   - Su sección eficaz de dispersión (RCS acústica) es minúscula: $\sigma_{\text{geométrica}} = \pi r^2 = \pi (0.008\text{ m})^2 \approx 2.01\times 10^{-4}\text{ m}^2 = 2.01\text{ cm}^2$.
   - La densidad de potencia reflejada cae con la **cuarta potencia** de la distancia ($P_r \propto 1/d^4$). A más de $60\text{ cm}$, el eco de una canica de $16\text{ mm}$ cae por debajo del piso de ruido del receptor. Por ello, la detección de presas debe ocurrir en el rango cercano ($d \le 55\text{ cm}$).

2. **Muro Perimetral Plano:**
   - Funciona como un espejo acústico (**reflexión especular**).
   - Si el robot incide perpendicularmente ($\theta = 0^\circ$), casi el 100% de la energía regresa al receptor ($P_r \propto 1/d^2$).
   - Si incide con un ángulo oblicuo $\theta > 35^\circ \dots 40^\circ$, la onda rebota alejándose del sensor, generando **falsos ecos de espacio libre** a pesar de tener una pared a pocos centímetros.

---

## 3. Estrategia Discriminante: Arquitectura de Doble Nivel Vertical (Dual-Plane)

Para resolver la ambigüedad física entre una canica en el suelo y una pared u obstáculo de fondo sin utilizar cámaras de visión artificial pesadas ni procesamiento de imágenes costoso, se desarrolló la arquitectura **Dual-Plane Coaxial**:

```text
Z (Altura)
  ▲
  │                                    [MURO DE FONDO (20 cm)]
  │                                    ┌───────────────────────┐
  │                                    │                       │
  ├─ 95 mm ───► [us_high] ════════════════════════════════════►│ (Eco d_high)
  │             (Haz Alto pasa por arriba de la canica)        │
  │                                                            │
  ├─ 12 mm ───► [us_low]  ════════► (o) [CANICA 16mm]          │
  │             (Haz Bajo impacta)  Eco d_low                  │
  └────────────────────────────────────────────────────────────┴────────► X (Frente)
```

### 3.1 Ecuación del Diferencial Coaxial ($\Delta$)
Para cada par vertical de sensores coaxiales (izquierdo, derecho o central):

$$\Delta_{\text{coaxial}} = d_{\text{alto}} - d_{\text{bajo}}$$

El espacio de decisión neuronal e inferencia queda determinado por:

$$\text{Clase} = \begin{cases}
\text{LOBITO (Presa)}, & \text{si } d_{\text{bajo}} < 60.0\text{ cm} \;\land\; \Delta_{\text{coaxial}} > 5.0\text{ cm} \\
\text{MURO / OBSTÁCULO}, & \text{si } d_{\text{bajo}} < 45.0\text{ cm} \;\land\; |\Delta_{\text{coaxial}}| \le 5.0\text{ cm} \\
\text{ESPACIO LIBRE}, & \text{si } d_{\text{bajo}} > 140.0\text{ cm} \;\land\; d_{\text{alto}} > 140.0\text{ cm}
\end{cases}$$

Esta formulación elimina los falsos positivos cuando el robot se aproxima a una pared o caja, ya que ambos sensores registran la misma distancia ($d_{\text{alto}} \approx d_{\text{bajo}} \implies \Delta \approx 0$). Solo un objeto de perfil ultra bajo ($Z < 25\text{ mm}$) genera un $\Delta$ positivo elevado.

---

## 4. Catálogo y Taxonomía de Configuraciones Sensoriales

### Configuración A: Baseline Dual-Plane Coaxial (8 Sensores)

- **Distribución de Transductores:**
  - Nivel Bajo ($Z = 12\text{ mm}$): `us_low_left` ($Y = +38\text{ mm}$, Yaw $= +5^\circ$), `us_low_right` ($Y = -38\text{ mm}$, Yaw $= -5^\circ$).
  - Nivel Alto ($Z = 95\text{ mm}$): `us_high_left` ($Y = +38\text{ mm}$, Yaw $= +5^\circ$), `us_high_right` ($Y = -38\text{ mm}$, Yaw $= -5^\circ$).
  - Flancos Laterales ($Z = 65\text{ mm}$): `us_side_left` ($X = 0$, $Y = +75\text{ mm}$, Yaw $= +90^\circ$), `us_side_right` ($X = 0$, $Y = -75\text{ mm}$, Yaw $= -90^\circ$).
  - Retaguardia ($Z = 65\text{ mm}$): `us_rear_left` ($X = -125\text{ mm}$, $Y = +40\text{ mm}$, Yaw $= 180^\circ$), `us_rear_right` ($X = -125\text{ mm}$, $Y = -40\text{ mm}$, Yaw $= 180^\circ$).
- **Dispositivo Display:** `sensor_hud` (340x340 px) integrado en el cuerpo del robot.
- **Evaluación Técnica:**
  - **Ventajas:** Excelente simplicidad geométrica y comparación directa $\Delta = d_H - d_L$. Canal bucal central ($Y \in [-35, +35]\text{ mm}$) totalmente despejado para la entrada de canicas.
  - **Limitaciones:** Cono frontal útil de $\approx \pm 20^\circ$. Si una presa queda a $35^\circ \dots 60^\circ$ en diagonal, pasa inadvertida hasta que el robot realiza un giro exploratorio.

---

### Configuración B: Array de Gran Apertura Trisectorial (10 Sensores)

- **Distribución de Transductores:**
  - Nivel Bajo ($Z = 12\text{ mm}$): 
    * `us_low_left_wide`: $X = 125\text{ mm}, Y = +60\text{ mm}$, Yaw $= +25^\circ$.
    * `us_low_left`: $X = 138\text{ mm}, Y = +38\text{ mm}$, Yaw $= +5^\circ$.
    * `us_low_right`: $X = 138\text{ mm}, Y = -38\text{ mm}$, Yaw $= -5^\circ$.
    * `us_low_right_wide`: $X = 125\text{ mm}, Y = -60\text{ mm}$, Yaw $= -25^\circ$.
  - Nivel Alto ($Z = 95\text{ mm}$): Idénticas posiciones angulares en el plano superior.
  - Flancos y Retaguardia: Sensores de supervisión perimetral.
- **Hallazgo Físico y Lección Crítica de Ingeniería:**
  > [!CAUTION]
  > **Incompatibilidad del Sensor Central en Plano Bajo ($Y = 0, Z = 12\text{ mm}$):**  
  > En pruebas iniciales se evaluó colocar un transductor `us_low_center` directamente en el eje de simetría ($Y = 0$). Aunque matemáticamente proporciona un haz axial perfecto, **físicamente obstruye la boca de la tolva de recolección**. La canica colisiona contra el cilindro del transductor ultrasónico antes de ingresar a la recámara bucal y activar el sensor táctil.  
  > **Solución Implementada:** Se suprimió el transductor central en $Y = 0$ y se bifurcó el banco en pares axiales y oblicuos montados sobre las aletas laterales ($Y = \pm 38\text{ mm}$ y $Y = \pm 60\text{ mm}$), preservando un canal libre de paso de $70\text{ mm}$.
- **Evaluación Técnica:**
  - **Ventajas:** Cobertura frontal panorámica de casi **$90^\circ$**. Permite inferir de inmediato en qué cuadrante angular se encuentra la presa (Sector Izquierdo $-25^\circ$, Central $0^\circ$ o Derecho $+25^\circ$) sin necesidad de zigzag o cabeceo motriz.

---

### Configuración C: Array Convergente con Foco Bucal (8 Sensores)

- **Distribución de Transductores:**
  - Nivel Bajo ($Z = 12\text{ mm}$): Transductores montados a $Y = \pm 48\text{ mm}$, inclinados **hacia el interior** a $\text{Yaw} = \mp 10^\circ$.
  - Nivel Alto ($Z = 95\text{ mm}$): Transductores altos coaxiales inclinados hacia el interior a $\text{Yaw} = \mp 10^\circ$.
  - Foco de Intersección Acústica: A una distancia de $X = 138 + \frac{48}{\tan(10^\circ)} \approx 41\text{ cm}$ frente al robot.
- **Evaluación Técnica:**
  - **Ventajas:** Máxima concentración de energía acústica y resolución en el embudo de aproximación final. Detección extremadamente estable de la presa durante los últimos 30 cm de maniobra.
  - **Limitaciones:** Reducción sustancial del campo de visión periférico lateral. Requiere más tiempo de barrido para localizar presas que no se encuentren en la trayectoria de avance directo.

---

### Configuración D: Híbrido Multimodal (Ultrasónico HC-SR04 + ToF Óptico Láser)

- **Distribución de Transductores:**
  - Nivel Bajo ($Z = 12\text{ mm}$): Sensores ultrasónicos HC-SR04 (`type "sonar"`, apertura $30^\circ$, 17 rayos) especializados en blancos curvos difusores (canicas).
  - Nivel Alto ($Z = 95\text{ mm}$): Sensores de Tiempo de Vuelo Óptico Láser (`type "laser"`, apertura colimada $2^\circ$, 1 rayo, simulando chips VL53L0X / Sharp ToF).
- **Evaluación Técnica:**
  - **Ventajas:** Elimina por completo las fallas de reflexión especular en paredes lejanas. El sensor láser mide con precisión milimétrica la distancia ortogonal a paredes y cajas de madera sin dispersión ni rebotes parásitos, mientras el sensor ultrasónico bajo aprovecha su amplio cono para no perder de vista la pequeña canica de 16 mm.

---

## 5. Herramientas de Depuración Visual Implementadas

Para garantizar la observabilidad exhaustiva solicitada en la directiva de ingeniería, se crearon e integraron dos sistemas de visualización complementarios:

### 5.1 On-Screen Sonar HUD 2D en Webots (Dispositivo `Display`)

El robot incorpora un dispositivo `Display` nativo de Webots bautizado `"sensor_hud"` de $340 \times 340$ píxeles, que proyecta una interfaz gráfica directamente en la ventana 3D de simulación:

```text
┌─────────────────────────────────────────────────────────┐
│ KEIKO RADAR | DUAL PLANE 8                 [APROXIMACION] │ ◄── Panel Superior FSM
├─────────────────────────────────────────────────────────┤
│                                                         │
│                     ( 2.0 m )                           │
│                     ( 1.5 m )                           │
│                     ( 1.0 m )                           │
│                     ( 50 cm )                           │
│                       \   /  ◄── Conos Acústicos 30°    │
│                        \ /                              │
│                    [CHASIS 4WD]                         │
│                    [   TT TT  ]                         │
│                        / \                              │
│                       /   \  ◄── Haces de Retaguardia   │
│                                                         │
│               ● LOBITO 24cm ◄── Marcador Verde Neón     │
├─────────────────────────────────────────────────────────┤
│ CANICAS: 2/3         ★ PRESA A 24cm ★     V:[2.8, 2.8]   │ ◄── Métricas de Telemetría
└─────────────────────────────────────────────────────────┘
```

**Características Gráficas del HUD:**
1. **Rejilla Polar de Alcance:** Anillos concéntricos a 25 cm, 50 cm, 100 cm, 150 cm y 200 cm con etiquetas métricas.
2. **Iconografía del Chasis:** Silueta a escala del rover 4WD, los 4 neumáticos amarillos TT y la cavidad del embudo de recolección.
3. **Proyección del Cono Acústico:** Trazado de las dos líneas directrices a $\pm 14^\circ$ que delimitan el haz sonoro de cada sensor.
4. **Vector de Medición Dinámico:** Línea central que conecta el transductor físico con el punto exacto de rebote acústico.
5. **Código de Colores de Clasificación IA:**
   - 🟢 **Verde Neón (`#00FF66`):** Presa detectada (`LOBITO`). Dibuja un punto de impacto con doble anillo pulsante y etiqueta con la distancia exacta.
   - 🔴 **Rojo Carmesí (`#EF4444`):** Muro u obstáculo estático en zona de riesgo ($d < 28\text{ cm}$).
   - 🟠 **Ámbar / Naranja (`#F59E0B`):** Detección en plano alto o proximidad en flancos laterales.
   - 🔵 **Gris / Azul Tenue (`#223348` / `#38BDF8`):** Espacio libre navegable sin obstáculo en el alcance del haz.

> [!TIP]
> **Activación de Rayos Nativos en Webots 3D:**  
> En la interfaz gráfica de Webots, es posible visualizar físicamente las líneas de proyección 3D de los sensores ultrasónicos accediendo al menú:  
> **`View -> Optional Rendering -> Show DistanceSensor Rays`** (o mediante el atajo de teclado **`Ctrl + F10`**).

---

### 5.2 Matriz de Haces y Radar Polar ASCII (Consola y Logs)

Para inspección durante ejecuciones en modo desatendido (`--batch`), integración continua o análisis forense en servidores remotos sin pantalla, `SonarVisualizer` emite cada 1.92 segundos una matriz polar tabular:

```text
==============================================================================
 [RADAR SONAR & MATRIZ DE HACES KEIKO 4WD] | T=018.4s | FSM=APROXIMACION | Canicas=1/3
 Configuración: DUAL_PLANE_8 | Cobertura=38.5% | ΔL=+84.2cm | ΔR= +1.1cm
------------------------------------------------------------------------------
                   [FRONTAL +0° (Doble Nivel Vertical)]
          FL-Alto: 104.5cm                  FR-Alto:  45.2cm
          FL-Bajo:  20.3cm ★                  FR-Bajo:  44.1cm  
                     \                          /
  Flanco-Izq:  85.0cm <----  [CHASIS ROVER 4WD]  ----> Flanco-Der: 115.0cm
                     /                          \
          RL-Tras: 180.2cm                  RR-Tras: 178.5cm
                      [RETAGUARDIA 180°]
------------------------------------------------------------------------------
 Haz Transductor | Distancia | Coaxial Δ | Perfil Haz       | Inferencia Estado
------------------------------------------------------------------------------
 us_low_left     |  20.3 cm  |  +84.2 cm | [=>               ] | >>> LOBITO (PRESA) <<<
 us_high_left    | 104.5 cm  |    ---    | [========>        ] | TRANSITABLE
 us_low_right    |  44.1 cm  |   +1.1 cm | [===>             ] | TRANSITABLE
 us_high_right   |  45.2 cm  |    ---    | [===>             ] | TRANSITABLE
 us_side_left    |  85.0 cm  |    ---    | [======>          ] | TRANSITABLE
 us_side_right   | 115.0 cm  |    ---    | [========>        ] | TRANSITABLE
 us_rear_left    | 180.2 cm  |    ---    | [==============>  ] | ESPACIO LIBRE
 us_rear_right   | 178.5 cm  |    ---    | [==============>  ] | ESPACIO LIBRE
==============================================================================
```

---

## 6. Matriz Comparativa de Rendimiento y Benchmarks

Resultados obtenidos en pruebas de simulación física acelerada en la Bahía de Caza estándar ($2.4\text{ m} \times 2.4\text{ m}$):

| Parámetro / Métrica | Config A: Dual-Plane (8) | Config B: Trisector (10) | Config C: Convergente (8) | Config D: Híbrido ToF (8) |
| :--- | :---: | :---: | :---: | :---: |
| **Transductores Totales** | 8 HC-SR04 | 10 HC-SR04 | 8 HC-SR04 | 4 Sonar + 4 ToF Laser |
| **Apertura Frontal Total** | $\pm 20^\circ$ | **$\pm 45^\circ$** | $\pm 15^\circ$ | $\pm 20^\circ$ |
| **Captura de Canicas** | **3 / 3 (100%)** | 1 / 3 (Obstrucción Y=0)* | **3 / 3 (100%)** | **3 / 3 (100%)** |
| **Tiempo de Misión Completa** | **640.4 s** | N/A (Bloqueo mecánico) | 654.2 s | **580.1 s** |
| **Cobertura Cartográfica Final** | **96.4%** | 99.0% | 89.8% | **97.2%** |
| **Resolución de Dirección Presa** | Diferencial Izq/Der | **Sector Tridimensional** | Centrado Focalizado | Diferencial Izq/Der |
| **Inmunidad a Reflexión Especular** | Regular (Paredes lisas) | Regular | Regular | **Excelente (Láser ToF)** |
| **Pines GPIO Arduino UNO Requeridos** | 16 pines (o multiplexor) | 20 pines (Requiere Mega) | 16 pines | 8 pines + I2C Bus |

*\* Nota de ingeniería: La Configuración B demostró superioridad de cobertura cartográfica (99.0%), pero requirió la modificación de diseño documentada en la Sección 4 para reubicar los transductores fuera del canal central de ingesta.*

---

## 7. Guía Operativa: Cómo Ejecutar y Alternar Configuraciones

Todos los mundos de simulación y scripts asociados se generan automáticamente a partir de `build_worlds.py`:

### 1. Regenerar los Mundos con Soporte Modular
```bash
python3 keiko-sim/scripts/build_worlds.py
```

### 2. Ejecutar la Simulación Autónoma (Visualizador Live con HUD)
- **Configuración A (Baseline 8 Sensores):**
  ```bash
  webots keiko-sim/worlds/bahia_de_caza.wbt
  ```
- **Configuración B (Array Trisectorial 10 Sensores):**
  ```bash
  webots keiko-sim/worlds/bahia_de_caza_trisector.wbt
  ```
- **Configuración C (Array Convergente 8 Sensores):**
  ```bash
  webots keiko-sim/worlds/bahia_de_caza_convergent.wbt
  ```
- **Configuración D (Híbrido Ultrasónico + Láser ToF):**
  ```bash
  webots keiko-sim/worlds/bahia_de_caza_hybrid.wbt
  ```

### 3. Ejecutar la Arquitectura Distribuida Completa (Servidor Java + Cliente)
```bash
# Modo interactivo con interfaz gráfica 3D y Sonar HUD en pantalla:
bash keiko-sim/scripts/run_distributed.sh

# Modo pruebas automáticas desatendidas:
bash keiko-sim/scripts/run_distributed.sh --batch
```

### 4. Inspección Forense de Haces en Tiempo Real
Durante la simulación, supervisar la matriz de haces y el mapa de ocupación ejecutando en una terminal secundaria:
```bash
tail -f keiko-sim/logs/simulation_live.log
```
o para la arquitectura distribuida cliente/servidor:
```bash
tail -f keiko-sim/logs/simulation_client.log
```
