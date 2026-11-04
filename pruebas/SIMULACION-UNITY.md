# Sensores del auto: datos para la simulación en Unity

Este documento tiene todo lo necesario para modelar en Unity los sensores del frente del auto: dónde están, cómo se mueven, cómo miden y cómo decide el Arduino si lo que ve es una canica o un obstáculo.

Los valores salen de dos fuentes: lo que está cargado en el Arduino y lo que medimos en las pruebas de banco. Lo marcado **[MEDIR]** no lo tengo medido con precisión y conviene confirmarlo con regla sobre el auto real. Las fotos están en `fotos_auto/`.

---

## 1. Qué hay en el frente

| Sensor | Cantidad | Dónde | Para qué |
|---|---|---|---|
| HC-SR04 (ultrasonido) | 3 | Fila de arriba del frente: izquierda, medio y derecha | Detectar obstáculos (cosas altas) |
| VL53L0X (láser ToF) | 2 | Abajo, uno en cada punta, colgando de un servo | Detectar la canica |
| Servo SG90 | 2 | Pegado al frente, en cada punta, con el eje vertical | Hacer que cada láser barra de lado a lado |

El centro del frente, abajo, queda **libre a propósito**: ahí va el mecanismo de recolección. No hay que poner sensores en esa zona.

**La canica:** vidrio **blanco opaco** ("lechera"), brillosa, **16 mm de diámetro** (radio 8 mm).

> **Cambio de canicas (2026-11-03):** antes se usaban canicas de vidrio transparente; ahora son blancas opacas del mismo tamaño. Las mediciones del láser de la sección 5.3 se tomaron con las **transparentes** y hay que volver a medirlas. Para el ultrasonido no cambia nada (mismo material y tamaño).

Fotos:
- `fotos_auto/04-frente.jpg`: vista de frente. Se ven los 3 ultrasonidos arriba y los 2 servos con los láser en las puntas de abajo.
- `fotos_auto/03-lateral-servo-y-laser.jpg`: de costado. El servo está pegado al frente con el eje para abajo, y el láser cuelga del aspa mirando al frente, casi al ras del piso.
- `fotos_auto/02-vista-superior.jpg`: desde arriba. Se ve la fila de ultrasonidos, con los de los costados girados hacia afuera.
- `fotos_auto/01-vista-3-cuartos.jpg`: vista general.

---

## 2. Sistema de coordenadas

Para Unity (Y para arriba, Z hacia adelante, X a la derecha):

- **Origen:** centro del frente del auto, a nivel del piso.
- **X:** positivo hacia la **derecha** del auto (mirando en el sentido de avance).
- **Y:** altura sobre el piso.
- **Z:** positivo hacia **adelante**.

> Ojo: el código del Arduino usa la convención contraria en X (positivo = izquierda). Los números de este documento ya están pasados a la convención de Unity.

---

## 3. Posición y orientación de cada sensor

### 3.1 Ultrasonidos de arriba (HC-SR04)

| Sensor | X (cm) | Y altura (cm) | Z (cm) | Giro horizontal (yaw) | Inclinación (pitch) |
|---|---|---|---|---|---|
| US izquierda | −5,4 | 8,5 ± 0,2 | 0 | 30° hacia afuera (hacia la izquierda) | 0° (derecho) |
| US medio | 0 | 8,5 ± 0,2 | 0 | 0° (al frente) | 0° |
| US derecha | +5,4 | 8,5 ± 0,2 | 0 | 30° hacia afuera (hacia la derecha) | 0° |

- La altura es al **centro de los cilindros**. Cada módulo mide 45 × 20 mm y tiene dos cilindros (emisor y receptor) separados ~26 mm.
- La posición X es al centro de cada módulo, o sea al punto medio entre sus dos cilindros.
- La zona de montaje del frente es de **155 mm de ancho × 100 mm de alto**.

### 3.2 Láser de abajo (VL53L0X) con servo

| Sensor | X del eje del servo (cm) | Z del eje del servo (cm) | Y altura del chip (cm) | Chip a 0° (X, Z en cm) |
|---|---|---|---|---|
| Láser izquierdo | −8,3 | +0,8 | 1,8 | (−8,3; +2,8) |
| Láser derecho | +8,3 | +0,8 | 1,8 | (+8,3; +2,8) |

- El **eje del servo es vertical**. El láser está pegado al aspa, **a 2,0 cm del eje**, y mira hacia afuera del eje (hacia donde apunta). Por eso **el chip se desplaza al girar**: describe un arco de 2 cm de radio alrededor del eje.
  - Posición del chip para un ángulo de mira θ (medido desde el frente, positivo hacia la derecha del auto): X = X_eje + 2,0 × sen(θ), Z = 0,8 + 2,0 × cos(θ).
  - A 0° el chip **sobresale 2,8 cm** del panel frontal.
- El láser **mira al frente** cuando el barrido está en 0°.
- Las medidas son del **láser izquierdo**. El derecho está despegado: se asume simétrico.
- **Inclinación (pitch):** la idea es que quede derecho o 1–2° hacia abajo. En la práctica es lo que más afecta la detección (ver sección 5.3). Para simular, conviene dejarla como parámetro ajustable.

---

## 4. Barrido de los láser

Los dos servos se mueven **a la vez y en espejo**.

| Parámetro | Valor |
|---|---|
| Ángulo 0° | Los dos láser miran derecho al frente |
| Ángulo negativo | Giran **hacia el centro** del auto (el izquierdo hacia la derecha y el derecho hacia la izquierda) |
| Ángulo positivo | Giran **hacia afuera** (el izquierdo hacia la izquierda y el derecho hacia la derecha) |
| Rango | De **−45°** (hacia el centro) a **+60°** (hacia afuera) |
| Paso | **5°** → 22 posiciones |
| Recorrido | Ida y vuelta (ping-pong): −45 → +60 → −45 → … |
| Pausa por paso | 40 ms para que el servo frene, más el tiempo de medir |

**¿Por qué −45° hacia el centro?** Con los dos láser mirando al frente queda un hueco sin cubrir en el medio. Girándolos hacia adentro cubren la zona central cercana, justo antes de que la canica entre al recolector.

**Tiempo por paso (estimado, no medido):** 40 ms de servo, más los 3 ultrasonidos de a uno (hasta ~25 ms cada uno más 15 ms de pausa), más los 2 láser (~33 ms cada uno). En total, entre **150 y 225 ms por paso**, o sea unos **3–5 s para ir de un extremo al otro**. Es lento. Si el auto se mueve mientras barre, la posición calculada de la canica se desfasa. Vale la pena que la simulación lo muestre.

El paso del barrido (5° contra 10–15°) **todavía no está definido**. Sería útil que la simulación compare los dos: con más grados por paso barre más rápido, pero la canica entra en menos lecturas.

---

## 5. Cómo mide cada sensor (para modelarlo)

### 5.1 Ultrasonido HC-SR04

- **Cono:** unos **30° de apertura** (15° hacia cada lado del eje). En la práctica es más ancho a corta distancia.
- **Rango:** 2 cm a 400 cm. Si no vuelve eco en 25 ms, el código toma **400 cm** ("nada").
- **Qué devuelve:** la distancia al **primer eco fuerte** dentro del cono, que normalmente es el objeto grande más cercano.
- **Con la canica:** en el auto real **solo la ve a 5–7 cm o menos**. Más lejos el eco es muy débil y no la detecta. Para la simulación, la canica conviene que sea "invisible" para los ultrasonidos más allá de ~7 cm.
- **El piso:** en ángulo rasante casi no devuelve eco. Con los sensores a 8,5 cm de altura, no debería verse.

Modelo simple sugerido: varios raycasts dentro del cono de 30°, y que devuelva la distancia mínima a cualquier objeto que no sea la canica (o la canica solo si está a menos de ~7 cm).

### 5.2 Láser VL53L0X

- **Cono (campo de visión):** **~25°** de apertura total (12,5° hacia cada lado del eje), tanto horizontal como vertical.
- **Qué devuelve:** **una sola distancia, que es como un promedio ponderado de todo lo que hay en el cono.** No separa objetos: si en el cono están la canica y el piso, o la canica y una pared, devuelve algo intermedio. **Es lo más importante para modelar bien.**
- **Rango útil:** si no ve nada en rango, el código toma **999 cm** ("nada").
- **Frecuencia:** ~30 Hz (33 ms por medición).
- **Reflectividad de la canica:** con las canicas transparentes de antes, la canica devolvía poca luz comparada con el piso o una pared, y un objeto grande detrás de la canica, dentro del mismo cono, **se llevaba la lectura**. Las canicas blancas opacas actuales devuelven bastante más luz, así que ese efecto debería ser mucho menor. Al ser brillosas, según el ángulo pueden dar algún reflejo especular. **[MEDIR]** con las canicas nuevas.

Modelo sugerido:
1. Lanzar muchos raycasts dentro del cono de 25°, por ejemplo una grilla de 7 × 7.
2. A cada rayo que pega le das un peso: la reflectividad de la superficie dividida por la distancia al cuadrado, multiplicada por un factor que baje hacia el borde del cono.
3. La lectura es el **promedio de las distancias ponderado por esos pesos**.
4. Reflectividad relativa para arrancar: piso y paredes 1,0; canica blanca opaca ~1,0 o más (la transparente de antes era ~0,3). **Hay que ajustarla** hasta que el modelo reproduzca la tabla de la sección 5.3, una vez que se vuelva a medir con las canicas blancas.

### 5.3 Datos medidos del láser (para calibrar el modelo)

> Estas tablas se midieron con las **canicas transparentes** anteriores. Con las blancas opacas la canica debería distinguirse mejor del fondo y verse a más distancia. Quedan como referencia hasta volver a medir.

**Prueba buena:** un solo láser fijo, bien alineado, mirando al frente, sobre la mesa.

| Situación | Lectura |
|---|---|
| Sin canica (lo que ve es el piso o mesa) | ~37 cm |
| Canica a 9 cm | ~11 cm |
| Canica a 15 cm | ~19 cm |
| Canica a 20 cm | ~34 cm, prácticamente se pierde |

Fijate que **siempre lee de más**: la canica se "mezcla" con el piso que está más atrás.

**Prueba con el montaje en el auto (servos), apenas inclinados hacia arriba:**

| Situación | Lectura |
|---|---|
| Sin canica, al frente | ~53–54 cm |
| Canica a 8 cm | 35–39 cm, **no la distingue bien** |

**Prueba con el sensor muy bajo (~15–20 mm) y mirando un poco al piso:**

| Situación | Lectura |
|---|---|
| Sin canica | ~19 cm |
| Canica a 10 cm | ~16 cm, casi indistinguible |

**Conclusión para la simulación:** la detección depende muchísimo de la **inclinación y la altura** del láser. Un grado de diferencia cambia todo. El punto ideal es que, sin canica, lea **~37–42 cm** al frente. Ahí el piso aparece lejos, pero la canica todavía entra en el cono. La simulación debería permitir variar el pitch y la altura del láser para ver ese efecto.

**Alcance confiable de detección de la canica:** **~15 cm** con el láser bien ajustado.

---

## 6. Lógica de decisión (lo que hace el Arduino, explicado sin código)

### 6.1 Calibración al arrancar
1. Con el piso **despejado**, el auto hace **3 barridos completos**.
2. Para cada láser y **cada ángulo** guarda la lectura del medio de las 3 (la mediana). Esa es la **distancia de reposo** de ese ángulo.
3. El reposo representa "lo que se ve cuando no hay nada": el piso, partes del propio auto, etc.

> Si durante la calibración hay algo adelante (una caja, la canica), queda guardado como "normal" y después no se detecta, o al revés, genera falsas detecciones. En la simulación conviene calibrar con la escena vacía.

### 6.2 En cada paso del barrido
1. Mueve los servos al ángulo del paso y espera 40 ms.
2. Mide los 3 ultrasonidos, de a uno.
3. Mide los 2 láser.
4. **Obstáculos por ultrasonido:** si cualquiera de los 3 de arriba mide menos de **25 cm**, avisa "OBSTÁCULO a la izquierda/medio/derecha".
5. **Para cada láser, ¿hay algo bajo?** Hay algo si se cumplen las dos condiciones:
   - lee **18 cm o menos**, y
   - lee **al menos 8 cm menos** que su reposo en ese ángulo.
6. Si hay algo, **calcula dónde está**, en el plano del piso:
   - X = X del eje del servo + (2,0 + distancia) × sen(ángulo hacia donde mira)
   - Z = 0,8 + (2,0 + distancia) × cos(ángulo hacia donde mira)
   - El 2,0 es el brazo del eje al chip y el 0,8 es cuánto está el eje delante del panel.
7. Busca el **ultrasonido de arriba que mejor apunta** a ese punto, el de menor diferencia angular.
8. **Decide:**
   - Si ese ultrasonido también ve algo a esa distancia (con 6 cm de margen) → **"obstáculo alto"** (por ejemplo, una mano o una pared).
   - Si no → **"CANICA DETECTADA"**, con lado (izquierda si X < −3 cm, derecha si X > +3 cm, si no, medio) y su posición en cm.

### 6.3 Limitaciones conocidas (para que la simulación las muestre)
- **Objetos bajos = falsas canicas.** Cualquier cosa más baja que los ultrasonidos de arriba (8,5 cm) y que el láser vea se clasifica como canica.
- **Un objeto grande detrás de la canica tapa la canica.** El láser informa el objeto de atrás. Si el de arriba lo ve, se clasifica como obstáculo.
- **Cosas altas cerca de la canica.** El cono ancho del ultrasonido puede hacer que una canica real se informe como "obstáculo alto".
- **Alcance corto (~15 cm) y barrido lento.** El auto tiene que recorrer la arena para encontrar las canicas, no las ve de lejos.
- **Partes del propio auto** (cables, ruedas, el chasis) pueden entrar en el cono del láser cuando gira hacia afuera. Por eso se calibra por ángulo.

---

## 7. Cosas que conviene que la simulación ayude a decidir

1. **Paso del barrido:** 5° contra 10° o 15°. ¿Se pierde alguna canica? ¿Cuánto más rápido barre?
2. **Inclinación y altura óptimas del láser** con el modelo de promedio en el cono.
3. **Rango del barrido:** ¿hace falta llegar a +60° hacia afuera? ¿Alcanza −45° hacia el centro para tapar el hueco del medio?
4. **Estrategia de movimiento:** con ~15 cm de alcance, ¿conviene avanzar y barrer, frenar y barrer, o girar el auto en vez de usar los servos?
5. **Cuántos obstáculos bajos** de la arena real se confundirían con una canica.

---

## 8. Datos que faltan

Hay que medirlos sobre el auto y completarlos acá:

- [ ] Medidas del chasis (largo × ancho × alto) y posición de las ruedas.
- [x] Altura del centro de los cilindros de los ultrasonidos: 8,5 ± 0,2 cm.
- [x] Altura del centro del chip del láser: 1,8 cm (medido en el izquierdo).
- [x] Cuánto sobresale el láser del frente: 2,8 cm. Distancia del chip al eje del servo: 2,0 cm.
- [x] Distancia de cada eje de servo al centro del chasis: 8,3 cm.
- [x] Ultrasonidos: 5,4 cm entre centros y los de los costados girados 30° hacia afuera (confirmado).
- [ ] Inclinación real de cada láser (se puede sacar de la lectura al frente sin nada adelante).
- [ ] Ancho y posición del mecanismo de recolección (la zona libre del centro).
- [ ] Medidas exactas de la arena. Lo que se sabe (bitácora 01): semicírculo de ~1 m de radio, salida de 20 cm, piso de baldosa bordó opaca. Objetivo: juntar 3 canicas y salir.
- [ ] Volver a medir el láser (tablas de la sección 5.3) con las canicas blancas opacas y ajustar la reflectividad del modelo.
- [ ] Obstáculos: cuántos, alto, ancho y forma.
