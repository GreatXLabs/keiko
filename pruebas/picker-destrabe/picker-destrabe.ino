/*
  Picker con destrabe automático: los dos servos SG90 modificados a 360°
  (PCA9685 canal 2 = izquierdo, canal 3 = derecho) giran recolectando y, si
  una canica traba el aspa, hacen empujones (atrás y de nuevo adelante).

  Cómo detecta el trabado: los servos están colgados del 5V del Mega, y un
  servo trabado consume más y hunde esos 5 V. El Mega mide su propia
  alimentación (Vcc) contra su referencia interna de 1.1 V. Medido el
  2026-11-03: frenados ~4870 mV, girando libres ~4570 mV, uno trabado
  ~3960 mV. Con los servos alimentados aparte (XL4015) esto NO detecta nada:
  ahí hace falta un sensor de corriente (INA219).

  Funcionamiento:
    1. Arranque suave y espera MS_ASENTAR.
    2. Aprende la tensión normal girando libre durante MS_APRENDER (si hay
       una canica trabada en ese momento, la base sale mal: mandar 'g').
    3. Si la tensión queda UMBRAL_MV por debajo de la base durante
       MS_TRABADO, hace un intento de destrabe: durante MS_INTENTO oscila
       rápido (MS_OSC_ATRAS para atrás, MS_OSC_ADELANTE para adelante). Los
       retrocesos son cortos y el empuje un poco más largo, para sacudir la
       canica sin que se escape para afuera de la rampa.
    4. Si se traba MAX_INTENTOS veces seguidas (sin MS_RESET_INTENTOS de
       giro libre entre medio), frena para no quemar el servo.
  Además revisa cada MS_CHEQUEO_PCA si el PCA9685 se reinició (por una caída
  de tensión arranca dormido y deja de mandar pulsos) y lo reconfigura.

  Por el monitor serie (9600):
    'g' -> arranca (o rearranca tras un bloqueo) y vuelve a aprender la base
    'p' -> frena
    'v' -> muestra / oculta la tensión cada 200 ms
*/

#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>

const uint8_t NUM_SERVOS = 2;
const uint8_t CANAL[NUM_SERVOS] = {2, 3};
const uint16_t NEUTRO[NUM_SERVOS] = {1475, 1500};
// 1 = recolecta con pulsos bajos, -1 = con pulsos altos
const int8_t SENTIDO[NUM_SERVOS] = {1, -1};
// Distancia al neutro (igual que picker-girando)
const uint16_t VEL_US[NUM_SERVOS] = {240, 200};
const uint8_t PASOS_ARRANQUE = 10;
const uint8_t MS_POR_PASO = 50;

const uint16_t MS_ASENTAR = 300;
const uint16_t MS_APRENDER = 1000;
const uint16_t UMBRAL_MV = 300;
const uint16_t MS_TRABADO = 200;
const uint16_t MS_INTENTO = 1000;
const uint16_t MS_OSC_ATRAS = 80;
const uint16_t MS_OSC_ADELANTE = 120;
// Al terminar de oscilar el servo acelera y también hunde la tensión:
// durante este tiempo no se mira
const uint16_t MS_GRACIA = 500;
const uint8_t MAX_INTENTOS = 3;
const uint16_t MS_RESET_INTENTOS = 3000;
const uint16_t MS_VENTANA = 20;
const uint16_t MS_CHEQUEO_PCA = 500;
const uint16_t MS_VERBOSE = 200;

const uint8_t DIR_PCA = 0x40;
const uint8_t PCA_MODE1 = 0x00;
const uint8_t PCA_SLEEP = 0x10;

// Calibración típica de la referencia interna (1.1 V * 1023 * 1000)
const uint32_t REF_INTERNA = 1125300UL;

enum Estado : uint8_t { FRENADO, APRENDIENDO, GIRANDO, OSCILANDO, GRACIA, BLOQUEADO };

Adafruit_PWMServoDriver pca;

Estado estado = FRENADO;
uint32_t tEstado = 0;
uint16_t baseMv = 0;
uint32_t sumaAprendizaje = 0;
uint16_t nAprendizaje = 0;
uint32_t tBajo = 0;  // desde cuándo está por debajo del umbral (0 = no lo está)
uint8_t intentos = 0;
uint32_t tUltimoDestrabe = 0;
int8_t dirOscilacion = 1;  // hacia dónde va ahora la oscilación
uint32_t tFase = 0;        // desde cuándo va en esa dirección
bool verbose = false;

uint16_t leerVccMv() {
  // Canal del ADC = referencia interna de 1.1 V, medida contra AVcc
  ADMUX = _BV(REFS0) | _BV(MUX4) | _BV(MUX3) | _BV(MUX2) | _BV(MUX1);
  ADCSRB &= ~_BV(MUX5);
  delayMicroseconds(300);
  ADCSRA |= _BV(ADSC);
  while (bit_is_set(ADCSRA, ADSC)) {
  }
  return REF_INTERNA / ADC;
}

void configurarPca() {
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
}

// 0xFF = el PCA no responde
uint8_t leerModo1() {
  Wire.beginTransmission(DIR_PCA);
  Wire.write(PCA_MODE1);
  if (Wire.endTransmission() != 0) {
    return 0xFF;
  }
  Wire.requestFrom(DIR_PCA, (uint8_t)1);
  return Wire.available() ? Wire.read() : 0xFF;
}

// dir: 1 = recolectar, -1 = atrás. escala de 1 a PASOS_ARRANQUE.
void mandarPulsos(int8_t dir, uint8_t escala = PASOS_ARRANQUE) {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    int16_t vel = (int32_t)VEL_US[k] * escala / PASOS_ARRANQUE;
    pca.writeMicroseconds(CANAL[k], NEUTRO[k] - dir * SENTIDO[k] * vel);
  }
}

void cambiarEstado(Estado nuevo) {
  estado = nuevo;
  tEstado = millis();
}

void arrancar() {
  for (uint8_t paso = 1; paso <= PASOS_ARRANQUE; paso++) {
    mandarPulsos(1, paso);
    delay(MS_POR_PASO);
  }
  sumaAprendizaje = 0;
  nAprendizaje = 0;
  intentos = 0;
  tBajo = 0;
  cambiarEstado(APRENDIENDO);
  Serial.println(F("Picker girando, aprendiendo la tensión normal..."));
}

void frenar(Estado nuevo) {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    pca.setPWM(CANAL[k], 0, 0);
  }
  cambiarEstado(nuevo);
}

void trabado(uint16_t mv) {
  Serial.print(F("Trabado: "));
  Serial.print(mv);
  Serial.print(F(" mV (base "));
  Serial.print(baseMv);
  Serial.println(F(")"));
  if (intentos >= MAX_INTENTOS) {
    frenar(BLOQUEADO);
    Serial.println(F("Sigue trabado después de los empujones: frenado. Destrabalo a mano y mandá 'g'"));
    return;
  }
  intentos++;
  tUltimoDestrabe = millis();
  Serial.print(F("Destrabe "));
  Serial.print(intentos);
  Serial.print(F("/"));
  Serial.println(MAX_INTENTOS);
  dirOscilacion = -1;
  mandarPulsos(dirOscilacion);
  tFase = millis();
  cambiarEstado(OSCILANDO);
}

void procesar(uint16_t mv) {
  uint32_t ahora = millis();
  switch (estado) {
    case APRENDIENDO:
      if (ahora - tEstado < MS_ASENTAR) {
        return;
      }
      sumaAprendizaje += mv;
      nAprendizaje++;
      if (ahora - tEstado >= MS_ASENTAR + MS_APRENDER) {
        baseMv = sumaAprendizaje / nAprendizaje;
        Serial.print(F("Base aprendida: "));
        Serial.print(baseMv);
        Serial.print(F(" mV, trabado por debajo de "));
        Serial.print(baseMv - UMBRAL_MV);
        Serial.println(F(" mV"));
        cambiarEstado(GIRANDO);
      }
      break;
    case GIRANDO:
      if (intentos > 0 && ahora - tUltimoDestrabe >= MS_RESET_INTENTOS) {
        intentos = 0;
      }
      if (mv < baseMv - UMBRAL_MV) {
        if (tBajo == 0) {
          tBajo = ahora;
        } else if (ahora - tBajo >= MS_TRABADO) {
          tBajo = 0;
          trabado(mv);
        }
      } else {
        tBajo = 0;
      }
      break;
    case OSCILANDO:
      if (ahora - tEstado >= MS_INTENTO) {
        mandarPulsos(1);
        cambiarEstado(GRACIA);
      } else if (ahora - tFase >= (dirOscilacion < 0 ? MS_OSC_ATRAS : MS_OSC_ADELANTE)) {
        dirOscilacion = -dirOscilacion;
        mandarPulsos(dirOscilacion);
        tFase = ahora;
      }
      break;
    case GRACIA:
      if (ahora - tEstado >= MS_GRACIA) {
        tBajo = 0;
        cambiarEstado(GIRANDO);
      }
      break;
    case FRENADO:
    case BLOQUEADO:
      break;
  }
}

void revisarPca() {
  uint8_t modo1 = leerModo1();
  if (modo1 == 0xFF || !(modo1 & PCA_SLEEP)) {
    return;
  }
  configurarPca();
  if (estado == OSCILANDO) {
    mandarPulsos(dirOscilacion);
  } else if (estado != FRENADO && estado != BLOQUEADO) {
    mandarPulsos(1);
  }
  Serial.println(F("El PCA9685 se había reiniciado: reconfigurado"));
}

void setup() {
  Serial.begin(9600);
  configurarPca();
  leerVccMv();  // la primera lectura tras cambiar de canal sale mal
  arrancar();
}

void loop() {
  static uint32_t tVentana = 0;
  static uint32_t tChequeoPca = 0;
  static uint32_t tVerbose = 0;
  static uint32_t suma = 0;
  static uint16_t n = 0;

  suma += leerVccMv();
  n++;
  uint32_t ahora = millis();
  if (ahora - tVentana >= MS_VENTANA) {
    uint16_t mv = suma / n;
    procesar(mv);
    if (verbose && ahora - tVerbose >= MS_VERBOSE) {
      tVerbose = ahora;
      Serial.print(F("vcc "));
      Serial.println(mv);
    }
    tVentana = ahora;
    suma = 0;
    n = 0;
  }

  if (ahora - tChequeoPca >= MS_CHEQUEO_PCA) {
    tChequeoPca = ahora;
    revisarPca();
  }

  if (Serial.available()) {
    switch (Serial.read()) {
      case 'g': arrancar(); break;
      case 'p':
        frenar(FRENADO);
        Serial.println(F("Picker frenado"));
        break;
      case 'v': verbose = !verbose; break;
    }
  }
}
