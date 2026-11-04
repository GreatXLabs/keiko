# Picker, sensor del estómago y alimentación (Arduino Mega)

Bitácora de las pruebas de banco del 2026-11-02 y 2026-11-03 con el hardware
nuevo: Arduino Mega + shield L293D, picker con dos servos SG90 modificados a
360°, PCA9685, VL53L0X en el estómago y step-down XL4015. Reemplaza lo que
dicen los documentos viejos basados en el UNO + L298N
(`keiko-sim/docs/circuito_electronico.md`, `firmware/orquita_firmware`), que
quedan desactualizados.

---

## 1. Hardware y pines

### 1.1 Tracción (shield L293D, librería AFMotor)

| Borne | Motor |
| --- | --- |
| M1 | Delantero izquierdo |
| M2 | Trasero izquierdo |
| M3 | Delantero derecho |
| M4 | Trasero derecho |

- Los cuatro quedaron cableados al revés: en el firmware se invierten con
  `INVERTIDO = {true, true, true, true}` (verificado el 2026-11-03 con
  `prueba-traccion`, los cuatro van hacia adelante).
- El shield ocupa D3, D4, D5, D6, D7, D8, D11, D12 (y D9/D10 para sus
  servos). Tapa A0–A5: en el Mega quedan libres **A8–A15**.

### 1.2 Bus I2C (D20 = SDA, D21 = SCL)

En el Mega el I2C está en **D20/D21**, no en A4/A5 como en el UNO (los
sketches viejos `prueba-vl53l0x`, `prueba-i2c`, `detector-mixto` y
`prueba-xshut` todavía dicen A4/A5 y XSHUT en A0/A1).

| Dispositivo | Dirección | XSHUT |
| --- | --- | --- |
| VL53L0X izquierdo (afuera) | 0x30 | A8 |
| VL53L0X derecho (afuera) | 0x31 | A9 |
| VL53L0X estómago | 0x32 | A10 |
| PCA9685 | 0x40 | — |
| INA219 (si se agrega) | **0x41** (puentear A0) | — |

- Los VL53L0X arrancan todos en 0x29: cada uno necesita **su propio** XSHUT
  para prenderlos de a uno y cambiarles la dirección.
- XSHUT se suelta con `pinMode(pin, INPUT)` (el pull-up del módulo lo
  enciende); no se le mandan 5 V.
- El INA219 viene de fábrica en 0x40, igual que el PCA9685: hay que cambiarle
  la dirección antes de conectarlo.

### 1.3 PCA9685 y servos del picker

| Pin de la tira de 6 | Va a |
| --- | --- |
| GND | GND del Mega |
| OE | Nada (si toca 5 V apaga las salidas) |
| SCL | D21 |
| SDA | D20 |
| VCC | 5V del Mega (prende el LED: es la lógica) |
| V+ | **OUT+ del XL4015** (ver 2.3) |

- Servos del picker: **canal 2 = izquierdo, canal 3 = derecho**.
- Si el LED del PCA está apagado le falta VCC/GND (Dupont flojo): no genera
  pulsos aunque haya 5 V en la bornera.
- Al perder VCC el PCA9685 vuelve a arrancar **dormido** (bit SLEEP de MODE1)
  y deja de mandar pulsos. Los sketches nuevos lo revisan cada 500 ms y lo
  reconfiguran (`revisarPca()`).

### 1.4 Bluetooth HC-05 (Serial1)

| Pin del HC-05 | Va a |
| --- | --- |
| VCC | 5V del Mega |
| GND | GND |
| TXD | D19 (RX1) |
| RXD | D18 (TX1), directo, sin divisor (decisión del equipo; ver nota) |
| STATE | D30 (no sirve en este módulo, ver abajo) |
| EN | Nada |

- Se usa `Serial1` para que el USB (`Serial`) quede libre para subir sketches
  y depurar.
- Configuración actual (verificada por AT el 2026-11-04): nombre
  **keiko-greatx**, dirección `00:22:12:01:32:B0`, esclavo (`ROLE:0`), modo
  datos a **9600,0,0**, firmware `5.0-20220104` (clon).
- El PIN quedó en el de fábrica para las pruebas: **cambiarlo antes de la
  competencia** con `hc05-at` (`AT+PSWD=...`) y volver a emparejar. No anotar
  el PIN en el repo.
- **STATE no funciona** en este clon: está forzado a LOW siempre, con o sin
  conexión (probado con y sin pull-up en D30). El fail-safe se apoya solo en
  el heartbeat.
- Nota RXD: el RX del HC-05 es de 3,3 V. Sin divisor (1 kΩ / 2 kΩ) funciona,
  pero puede degradarse; llevar un módulo de repuesto.
- Modo AT: desenchufar VCC, mantener el botón del módulo, enchufar VCC y
  soltar cuando el LED parpadea lento (~2 s). En AT habla a 38400. `AT+RESET`
  vuelve al modo datos.
- Emparejar desde Linux: el panel de GNOME no muestra el módulo. Usar
  `bluetoothctl` con `menu scan` → `transport bredr` → `back` → `scan on`
  (si no, el módulo aparece y desaparece y el `pair` falla con
  `ConnectionAttemptFailed`), y después `pair` / `trust`. Prueba del enlace:
  socket RFCOMM al canal 1 (el `python3` del sistema trae `BTPROTO_RFCOMM`).

---

## 2. Alimentación

Esquema completo (cable por cable): artifact "alimentación orquita" en
claude.ai (`https://claude.ai/artifact/3wVay1qbbhfVBmCPBDrm6n`). Falta
actualizarlo con lo de 2.3.

### 2.1 Plan general

Batería 2S2P (7,4 V) → llave → fusible 5 A → reparto a:

- Shield **EXT_PWR** (jumper PWR sacado) para los motores.
- **XL4015 IN** → OUT regulado a **5,0–5,1 V** (ajustar sin carga con
  tester, nunca más de 5,5 V):
  - Cable 1: OUT+ / OUT− → **5V / GND de la tira doble del Mega** (la de
    D22–D53, arriba tiene dos 5V y abajo dos GND; no la tapa el shield).
  - Cable 2: OUT+ → V+ de los servos (PCA), con capacitor de 470–1000 µF.
- GND común entre batería, shield, XL4015, Mega y PCA.

Notas:

- **No usar Vin ni el plug** del Mega con 5 V (pasan por el regulador y
  necesitan 7 V o más).
- **Al programar por USB, sacar el cable 1** (o apagar la llave): si no, los
  5 V del USB y los del XL4015 se pelean y puede volver corriente a la PC.
- El datasheet del XL4015 pide 8 V de entrada mínima: con 2S queda al límite.

### 2.2 Cableado

- **Nada de protoboard para potencia** (batería, motores, servos): contactos
  de ~1 A, se calientan y se sueltan con la vibración. Usar borneras, Wago 221
  o soldadura con termocontraíble, cable AWG 20–22. Conector XT30/XT60 en la
  batería.
- Dupont: sirven para el 5V/GND del Mega (consume poco) y para sensores,
  fijados con silicona caliente. **No** para el V+ de los servos (picos de
  2–3 A).
- Sensores, HC-05 y VCC del PCA: a los agujeros +5V / GND del shield (al lado
  de A0–A5; soldar una tira de pines) o al segundo par 5V/GND de la tira
  doble. Verificar antes con continuidad que esos agujeros sean el 5V/GND del
  Mega.

### 2.3 Falla encontrada: la bornera del PCA9685 no pasa tensión

Con 5 V medidos en los tornillos de la bornera (+5 V, polaridad correcta), el
pin V+ de la tira daba **0 V** y los servos no giraban. La protección contra
polaridad invertida de la placa (o la soldadura de la bornera) está cortando.

- Solución: **OUT+ del XL4015 directo al pin V+ de la tira**. El GND puede
  seguir en la bornera.
- Para el robot final: soldar ahí un cable AWG 20–22 (o al pad del capacitor
  de la placa, que está sobre el riel V+) y el capacitor.
- Sin la protección, un cable invertido quema servos y placa: marcar el rojo.
- **Nunca** conectar el pin V+ al 5V del Mega (mezcla el XL4015 con el USB y
  los servos vuelven a reiniciar el Mega).

---

## 3. Servos SG90 modificados a 360°

- Modificación: sacar el tope y desacoplar el engranaje de salida del
  potenciómetro (se cortó el palito del engranaje final). El pulso pasa a ser
  velocidad/sentido alrededor de un neutro.
- Calibración (`prueba-servo-360`):

| Canal | Lado | Neutro | Recolecta con |
| --- | --- | --- | --- |
| 2 | Izquierdo | 1475 µs (quieto entre ~1465 y 1485) | pulsos bajos (`SENTIDO = 1`) |
| 3 | Derecho | 1500 µs (pote ajustado con 1500 aplicado) | pulsos altos (`SENTIDO = -1`) |

- Velocidad de trabajo: `VEL_US = {240, 200}` (distancia al neutro; distinta
  para que giren parejos, cerca del máximo del SG90).
- Para frenar, cortar la señal (`setPWM(canal, 0, 0)`) es lo más seguro.
- Arranque suave (10 pasos de 50 ms): con los servos colgados del USB el Mega
  se reiniciaba.

---

## 4. Trabado del picker

Las canicas se traban entre el aspa y la rampa (hueco de menos de 1 cm). Con
dos aspas rectas, si la canica llega cuando el aspa baja la golpea y la
empuja para afuera.

### 4.1 Detección por caída de tensión (solo con servos en el USB)

`prueba-trabado-vcc` y `picker-destrabe` miden el Vcc del Mega contra la
referencia interna de 1,1 V. Medido el 2026-11-03: frenados ~4870 mV,
girando ~4570 mV, uno trabado ~3960–4060 mV. Funcionó (detecta y hace el
destrabe oscilante: 80 ms atrás / 120 ms adelante durante 1 s, hasta 3
intentos), pero **con los servos en el XL4015 no ve nada**: el regulador
mantiene los 5 V aunque se traben. Lo que cambia es la corriente.

### 4.2 Medir corriente (pendiente, falta hardware)

- Opción recomendada: **INA219** en serie con el V+ de los servos
  (VIN+ ← OUT+ del XL4015, VIN− → V+ del PCA), I2C en 0x41. Mide el total de
  los servos del PCA: ignorar la lectura mientras se mueven los otros servos,
  o pasar por él solo el rojo de los del picker.
- Alternativa: shunt de **0,22 Ω 1 W** (0,1 Ω también sirve; 1/2 W solo si
  pasan únicamente los 2 servos del picker) + 4 resistencias de 10 kΩ como
  divisores hacia dos pines analógicos. Colores 0,22 Ω: 4 bandas
  rojo-rojo-plateado-dorado, 5 bandas negro-rojo-rojo-plateado-marrón; las de
  1 W suelen ser de cemento con "R22" impreso.
- Las resistencias de 1/4 W que hay a mano (cientos de Ω a 10 kΩ) no sirven
  como shunt.

### 4.3 Decisiones

- **No se cambian las aspas** (ni flexibles ni más aspas). El picker ya tiene
  embudo y guías.
- Se apuesta a la **velocidad de aproximación**: en los últimos 10–15 cm bajar
  a ~40–50 %, con el picker girando antes de llegar y sin frenar en seco.
- El trabado se detecta por el **resultado** (la canica no llega al
  estómago), ver 5.3.

---

## 5. Sensor del estómago (VL53L0X)

### 5.1 Montaje y lecturas

- Pegado al costado del canal, a la altura de la boca de la rampa, mirando a
  través del estómago. Modo continuo de 20 ms.
- Estómago vacío: **53–60 mm** según el arranque (se aprende cada vez).
- Picker girando sin canicas: 49–63 mm (ruido de ±7 mm).
- Con canica: **30–40 mm**.
- Las canicas blancas (lecheras) se ven bien; no hace falta la distancia
  exacta, solo el cambio.

### 5.2 Contar pasadas no sirve

Con un contador de cambios (umbral 15 mm): se tiraron 3 canicas y contó 7.
Las canicas van hacia adelante, bajan, vuelven, se quedan quietas frente al
sensor (una estuvo 8 s) y la siguiente las choca o las mueve. **No usar un
contador para escribir en `estomago.txt`.**

### 5.3 Captura armada (`picker-estomago`, cargado en el Mega)

1. Al entrar en CAPTURA se arma el detector: aprende la lectura de ese
   momento (~200 ms), esté vacío o con canicas quietas adelante.
2. El primer cambio de **≥ 10 mm en 2 lecturas seguidas** = entró una canica
   (o la nueva movió a una que estaba quieta). Avisa **una vez** y se desarma.
3. No vuelve a avisar hasta que lo armen de nuevo.

Resultado de banco: "iba bastante bien". Riesgo: si el robot se sacude con el
detector armado puede mover una canica quieta y dar una captura falsa.

### 5.4 Flujo propuesto para CAPTURA (a implementar en el servidor)

Idea del equipo (2026-11-03):

1. Al detectar una canica el robot gira hasta centrarla.
2. Cuando arranca a avanzar hacia ella, **se arma el detector** y empieza a
   correr un tiempo esperado:
   `t_esperado = distancia_a_la_canica / velocidad_de_aproximación + ~2 s` (lo
   que tarda el picker en meterla al estómago).
3. Si llega `CAPTURA` → se registra en `estomago.txt` → BÚSQUEDA o ESCAPE.
4. Si pasa `t_esperado` sin captura → se asume trabado → **destrabe
   oscilante** del picker (1 s atrás/adelante) y, como refuerzo, sacudir el
   robot (izquierda-derecha o rotarlo).
5. Hasta N intentos; si sigue sin entrar, volver a BÚSQUEDA (o reintentar la
   aproximación).

Recomendaciones sobre esa idea:

- **La lógica va en el servidor**, no en el Mega (AGENTS.md §4.1: el cliente
  no tiene lógica estratégica). El Mega solo arma/desarma, manda el `EVENT`
  de captura y ejecuta un `COMMAND` de destrabe.
- Dar margen al tiempo esperado (por ejemplo +50 %): en los últimos
  centímetros los sensores frontales pierden la canica (zona ciega) y la
  distancia inicial no es exacta.
- **Desarmar el detector mientras se sacude el robot** y volver a armarlo
  después: si no, la sacudida mueve canicas quietas y da capturas falsas.
- Sacudir con giros cortos y solo si hay lugar: chocar un muro u obstáculo
  son −5 y perder una canica ya capturada también son −5.
- Todo esto cuenta como actividad (no dispara el límite de 10 s de
  inactividad), pero el fail-safe de 500 ms sin heartbeat sigue mandando.

---

## 6. Sketches de esta tanda

| Sketch | Para qué |
| --- | --- |
| `prueba-traccion` | Probar los 4 motores del shield (1–4, i, a, r, z, d, p, +/−). |
| `prueba-picker` | Picker con L298N (ENA=45, IN1=22, IN2=23). **Ya no se usa**: el L298N se sacó. |
| `prueba-servo-360` | Calibrar neutro, sentido y velocidad de los servos 360° (canales 2 y 3). |
| `picker-girando` | Los dos servos del picker girando recolectando desde el arranque. |
| `prueba-trabado-vcc` | Medir la caída del Vcc del Mega con el picker girando / trabado. |
| `picker-destrabe` | Detección de trabado por Vcc + destrabe oscilante (solo con servos en el USB). |
| `picker-estomago` | Picker + VL53L0X del estómago con captura armada (`g`, `p`, `a`, `d`, `v`). |
| `prueba-hc05` | Puente USB ↔ Bluetooth (115200 / 9600), aviso de STATE y `PING n` cada 2 s. Probado en los dos sentidos. |
| `hc05-at` | Configurar el HC-05 en modo AT (38400) desde el monitor serie a 115200. |

Problemas de USB del banco:

- El CH340 del Mega daba error -71 en algunos puertos USB de la PC; en otro
  puerto anduvo bien. Si falla la subida, probar otro puerto antes que otro
  cable.
- Si el Serial Monitor del IDE está abierto, toma el puerto y la subida se
  cuelga: cerrarlo antes de subir.
- El buffer de recepción serie del Mega es de 64 bytes: no mandar ráfagas
  largas de comandos de una.

---

## 7. Pendientes

- [ ] Montar los sensores (se sigue en otra conversación).
- [ ] Bajar la velocidad en la aproximación final (servidor) y probar cuánto
      se traba en la práctica.
- [ ] Destrabe por tiempo sin captura (5.4) en el servidor + comandos
      armar/desarmar/destrabe en el firmware.
- [ ] Conseguir INA219 (o shunt de 0,22 Ω 1 W) si el trabado sigue siendo
      frecuente.
- [ ] Soldar el V+ del PCA y el capacitor (2.3); fijar los Dupont con
      silicona.
- [ ] Actualizar el artifact de alimentación con el V+ por el pin de la tira.
- [ ] Actualizar a Mega (D20/D21, XSHUT A8–A10) los sketches viejos y
      `keiko-sim/docs/circuito_electronico.md` / `firmware/orquita_firmware`.
- [x] Commitear todo esto en una rama propia (PR #12).
- [ ] Cambiar el PIN del HC-05 antes de la competencia (1.4).
- [ ] Pasar los ultrasonidos de `detector-mixto` a pines libres del Mega
      (propuesta D24–D29: TRIG/ECHO 24/25, 26/27, 28/29) y `US_ANG` a 0°.
