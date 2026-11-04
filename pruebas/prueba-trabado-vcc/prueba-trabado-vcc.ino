/*
  Prueba: ¿se puede detectar que el picker se trabó mirando cuánto cae la
  alimentación del Mega?

  Solo tiene sentido con el V+ de los servos colgado del 5V del Mega: un
  servo trabado consume más y hunde esos 5 V. El Mega mide su propia
  alimentación (Vcc) contra su referencia interna de 1.1 V, sin hardware
  extra. Con los servos alimentados aparte (XL4015) esto no ve nada.

  Los servos giran igual que en picker-girando y cada 100 ms se imprime
  "vcc <mV> min <mV>" (min = mínimo de las lecturas de ese intervalo).
  Por el monitor serie (9600): 'p' frena, 'g' vuelve a girar.
*/

#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>

const uint8_t NUM_SERVOS = 2;
const uint8_t CANAL[NUM_SERVOS] = {2, 3};
const uint16_t NEUTRO[NUM_SERVOS] = {1475, 1500};
const int8_t SENTIDO[NUM_SERVOS] = {1, -1};
const uint16_t VEL_US[NUM_SERVOS] = {240, 200};
const uint8_t PASOS_ARRANQUE = 10;
const uint8_t MS_POR_PASO = 50;
const uint16_t MS_REPORTE = 100;

// Calibración típica de la referencia interna (1.1 V * 1023 * 1000). Varía
// unos % entre placas: sirve para comparar, no como valor absoluto.
const uint32_t REF_INTERNA = 1125300UL;

Adafruit_PWMServoDriver pca;

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

void girar() {
  for (uint8_t paso = 1; paso <= PASOS_ARRANQUE; paso++) {
    for (uint8_t k = 0; k < NUM_SERVOS; k++) {
      int16_t vel = (int32_t)VEL_US[k] * paso / PASOS_ARRANQUE;
      pca.writeMicroseconds(CANAL[k], NEUTRO[k] - SENTIDO[k] * vel);
    }
    delay(MS_POR_PASO);
  }
  Serial.println(F("Picker girando"));
}

void frenar() {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    pca.setPWM(CANAL[k], 0, 0);
  }
  Serial.println(F("Picker frenado"));
}

void setup() {
  Serial.begin(9600);
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
  leerVccMv();  // la primera lectura tras cambiar de canal sale mal
  girar();
}

void loop() {
  static uint32_t ultimoReporte = 0;
  static uint32_t suma = 0;
  static uint16_t n = 0;
  static uint16_t minimo = 0xFFFF;

  uint16_t mv = leerVccMv();
  suma += mv;
  n++;
  minimo = min(minimo, mv);

  if (millis() - ultimoReporte >= MS_REPORTE) {
    ultimoReporte = millis();
    Serial.print(F("vcc "));
    Serial.print(suma / n);
    Serial.print(F(" min "));
    Serial.println(minimo);
    suma = 0;
    n = 0;
    minimo = 0xFFFF;
  }

  if (Serial.available()) {
    switch (Serial.read()) {
      case 'p': frenar(); break;
      case 'g': girar(); break;
    }
  }
}
