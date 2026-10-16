# Especificación Técnica de Circuito Electrónico y Cableado: Orquita 4WD

Documento técnico formal de ingeniería para el prototipo robótico **La Orquita** basado en el kit rover **4WD de dos pisos (Todomicro)** para la competencia en la Bahía de Caza (PSR / DS 2026).

---

## 1. Lista de Materiales (BOM - Hardware Físico)

| Componente | Cantidad | Especificaciones | Propósito |
| :--- | :---: | :--- | :--- |
| **Chasis Rover 4WD** | 1 | Acrílico doble bandeja (\(256 \times 150 \times 65\text{ mm}\)) | Estructura mecánica soporte de 2 niveles |
| **Motores DC con reductora** | 4 | Motorreductores TT relación 1:48, 3-6V DC | Tracción en las 4 ruedas (Skid-Steer) |
| **Ruedas con cubierta de goma** | 4 | Diámetro: \(65\text{ mm}\), Ancho: \(26\text{ mm}\) | Adherencia y tracción sobre la bahía |
| **Microcontrolador** | 1 | Arduino UNO R3 (o Nano / ESP32) | Cliente de control en tiempo real y Fail-Safe |
| **Driver de Motores** | 1 | Módulo Puente H **L298N** (doble canal, 2A por canal) | Control de velocidad (PWM) y sentido de giro |
| **Módulo Bluetooth** | 1 | **HC-05** (o HC-06) con perfil RFCOMM/SPP | Enlace inalámbrico serie con el Servidor Cerebro |
| **Sensores de Proximidad** | 4 | Ultrasonido **HC-SR04** (5V, \(2\text{ cm} - 400\text{ cm}\)) | Percepción en 2 planos (2 bajos para canicas, 2 altos para muros) |
| **Sensor de Captura** | 1 | Óptico infrarrojo (CNY70 / TCRT5000) o Microswitch fin de carrera | Detección física de canica en la cavidad bucal |
| **Fuente de Alimentación** | 1 | Pack 2x Baterías Li-Ion 18650 (7.4V nominal, 8.4V max) | Alimentación de potencia y lógica |
| **Regulador / Step-down** | 1 | Regulador 5V integrado en L298N (o LM2596) | 5V estables para Arduino y sensores |
| **Resistencias divisoras** | 2 | \(1\text{ k}\Omega\) y \(2\text{ k}\Omega\) (o \(1.2\text{ k}\Omega\) y \(2.2\text{ k}\Omega\)) | Adaptar nivel lógico de 5V (Arduino TX) a 3.3V (HC-05 RX) |

---

## 2. Asignación de Pines (Pinout Arduino UNO)

| Pin Arduino | Dispositivo | Función / Señal | Notas |
| :---: | :---: | :---: | :--- |
| **D0 (RX)** | Bluetooth HC-05 | TX del módulo hacia Arduino RX | Comunicación serie 9600 o 38400 baudios |
| **D1 (TX)** | Bluetooth HC-05 | Arduino TX hacia RX del módulo | **Requiere divisor resistivo a 3.3V** |
| **D2** | Sensor Bucal | Señal digital `CAPTURE_PIN` | Entrada con pull-up (LOW = captura detectada) |
| **D3 (PWM)** | Driver L298N | `ENA` (Velocidad Motores Izquierdos) | Control PWM de 0 a 255 |
| **D4** | Driver L298N | `IN1` (Dirección Motores Izquierdos) | Nivel lógico (HIGH/LOW) |
| **D5** | Driver L298N | `IN2` (Dirección Motores Izquierdos) | Nivel lógico (HIGH/LOW) |
| **D6** | Driver L298N | `IN3` (Dirección Motores Derechos) | Nivel lógico (HIGH/LOW) |
| **D7** | Driver L298N | `IN4` (Dirección Motores Derechos) | Nivel lógico (HIGH/LOW) |
| **D8** | Sensor HC-SR04 (Bajo Izq) | `TRIG_LOW_LEFT` | Pulso de disparo de \(10\ \mu\text{s}\) |
| **D9 (PWM)** | Driver L298N | `ENB` (Velocidad Motores Derechos) | Control PWM de 0 a 255 |
| **D10** | Sensor HC-SR04 (Bajo Izq) | `ECHO_LOW_LEFT` | Pulso medidor de eco |
| **D11** | Sensor HC-SR04 (Bajo Der) | `TRIG_LOW_RIGHT` | Pulso de disparo |
| **D12** | Sensor HC-SR04 (Bajo Der) | `ECHO_LOW_RIGHT` | Pulso medidor de eco |
| **A0** | Sensor HC-SR04 (Alto Izq) | `TRIG_HIGH_LEFT` (usado como GPIO) | Disparo sensor superior |
| **A1** | Sensor HC-SR04 (Alto Izq) | `ECHO_HIGH_LEFT` (usado como GPIO) | Eco sensor superior |
| **A2** | Sensor HC-SR04 (Alto Der) | `TRIG_HIGH_RIGHT` (usado como GPIO) | Disparo sensor superior |
| **A3** | Sensor HC-SR04 (Alto Der) | `ECHO_HIGH_RIGHT` (usado como GPIO) | Eco sensor superior |

---

## 3. Esquema de Alimentación y Aislamiento de Tierra

```text
[Pack Baterías 2x 18650 (7.4V)]
        |
        +--------[ Interruptor General ON/OFF ]
        |                   |
        |                   v
        |        +---------------------+
        |        |   Driver L298N      |
        +------->| 12V / VMS           |
                 | GND --------------- + ----> Tierra Común (GND)
                 | +5V Regulado Salida |
                 +---------------------+
                            |
           +----------------+----------------+
           |                                 |
           v                                 v
     [ Arduino 5V In ]              [ Riel VCC Sensores (5V) ]
     - Alimenta microcontrolador    - 4x HC-SR04
     - Alimenta HC-05               - 1x CNY70 / Sensor Captura
```

> [!CAUTION]
> **Tierra Común (Common GND):** El pin GND de la batería, el GND del módulo L298N y el GND del Arduino DEBEN estar unidos físicamente en un único nodo común. De lo contrario, las señales PWM de control de motor y el eco de los sensores oscilarán de forma errática.

> [!WARNING]
> **Divisor de Tensión en Bluetooth HC-05 RX:** El pin RX del módulo Bluetooth opera con nivel lógico de \(3.3\text{ V}\). Aunque el Arduino emite a \(5\text{ V}\), conectar directamente el pin TX de Arduino al pin RX del HC-05 quemará el receptor con el tiempo. Se debe colocar un divisor:
> ```text
> Arduino Pin D1 (TX) ---> [ R1 = 1kΩ ] ---> Nodo HC-05 (RX) ---> [ R2 = 2kΩ ] ---> GND
> Tensión resultante: 5V * (2k / (1k + 2k)) = 3.33V (Perfecto para HC-05)
> ```

---

## 4. Conexión de Motores en Paralelo (Configuración 4WD Skid-Steer)

El driver L298N posee 2 canales de salida (OUT1/OUT2 y OUT3/OUT4). Al disponer de 4 motores, se conectan en paralelo los motores del mismo flanco:

```text
Flanco Izquierdo:
- Motor Delantero Izquierdo + Motor Trasero Izquierdo en paralelo -> OUT1 y OUT2 del L298N
- Control de velocidad: Pin D3 (PWM ENA)
- Control de sentido: Pines D4 (IN1) y D5 (IN2)

Flanco Derecho:
- Motor Delantero Derecho + Motor Trasero Derecho en paralelo -> OUT3 y OUT4 del L298N
- Control de velocidad: Pin D9 (PWM ENB)
- Control de sentido: Pines D6 (IN3) y D7 (IN4)
```

### Tabla de Maniobras de Dirección:
| Movimiento | ENA | IN1 | IN2 | ENB | IN3 | IN4 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Avanzar** | 200 | HIGH | LOW | 200 | HIGH | LOW |
| **Retroceder** | 180 | LOW | HIGH | 180 | LOW | HIGH |
| **Giro en el lugar (Izquierda)** | 180 | LOW | HIGH | 180 | HIGH | LOW |
| **Giro en el lugar (Derecha)** | 180 | HIGH | LOW | 180 | LOW | HIGH |
| **Parada / Freno Activo** | 0 | LOW | LOW | 0 | LOW | LOW |

---

## 5. Ubicación Física de Componentes en el Chasis de 2 Pisos

1. **Piso Inferior (Bandeja Acrílica 1)**:
   - Sujeción de los 4 motorreductores TT con sus tornillos pasantes.
   - Montaje del módulo L298N en la parte central-trasera (para mantener el centro de gravedad bajo y balanceado).
   - En el frente: **Cavidad Bucal de Captura** con rampa de acrílico inclinada y el sensor de fin de carrera o barrera infrarroja.
   - En el borde frontal inferior (\(Z = 2.5\text{ cm}\)): los 2 sensores ultrasónicos bajos (`us_low_left` y `us_low_right`) orientados con ligera convergencia (\(\pm 5^\circ\)) hacia el frente.
2. **Piso Superior (Bandeja Acrílica 2)**:
   - Arduino UNO montado con separadores plásticos de \(5\text{ mm}\).
   - Módulo Bluetooth HC-05 ubicado con antena despejada hacia arriba.
   - Pack de baterías 18650 fijado con precintos al centro.
   - En el frente superior (\(Z = 9.5\text{ cm}\)): soporte impreso en 3D o acrílico para los 2 sensores ultrasónicos altos (`us_high_left` y `us_high_right`) orientados a \(\pm 20^\circ\).
3. **Fail-Safe de Hardware**:
   - En caso de desconexión Bluetooth o timeout > 500 ms, la rutina `loop()` del Arduino ejecuta inmediatamente `digitalWrite(IN1..IN4, LOW)` y `analogWrite(ENA/ENB, 0)` para clavar los motores a cero.
