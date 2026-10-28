/*
  Prueba de los 2 servos en el PCA9685 (canal 0 = izquierdo, 1 = derecho)

  Al arrancar los deja en el centro (90°). Por el monitor serie:
    'c' -> los dos al centro (90°)
    'a' -> los dos a 0°
    'z' -> los dos a 180°
    's' -> barrido lento completo 0° -> 180° -> 0°
    'b' -> barrido como el del detector (-45° hacia el centro a +60° hacia
           afuera, la izquierda y la derecha en espejo), 2 veces
*/

#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>

const uint8_t NUM_SERVOS = 2;
const uint8_t SERVO_CANAL[NUM_SERVOS] = {0, 1};
const char *NOMBRE_SERVO[NUM_SERVOS] = {"izq", "der"};
// Ancho de pulso para 0° y 180° (típico de un SG90)
const uint16_t SERVO_US_0 = 500;
const uint16_t SERVO_US_180 = 2400;
// Igual que en detector-mixto: centro y sentido de giro de cada servo
const uint8_t SERVO_CENTRO[NUM_SERVOS] = {90, 90};
const int8_t SERVO_SENTIDO[NUM_SERVOS] = {1, -1};

Adafruit_PWMServoDriver pca;

void moverServo(uint8_t k, int16_t grados) {
  grados = constrain(grados, 0, 180);
  pca.writeMicroseconds(SERVO_CANAL[k], map(grados, 0, 180, SERVO_US_0, SERVO_US_180));
}

void moverAmbos(int16_t grados) {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    moverServo(k, grados);
  }
  Serial.print(F("Los dos servos a "));
  Serial.print(grados);
  Serial.println(F("°"));
}

void barridoCompleto() {
  Serial.println(F("Barrido completo 0 -> 180 -> 0"));
  for (int16_t g = 0; g <= 180; g += 2) {
    moverServo(0, g);
    moverServo(1, g);
    delay(30);
  }
  for (int16_t g = 180; g >= 0; g -= 2) {
    moverServo(0, g);
    moverServo(1, g);
    delay(30);
  }
  Serial.println(F("Fin del barrido"));
}

void barridoDetector() {
  Serial.println(F("Barrido del detector: -45 (centro) a +60 (afuera)"));
  for (uint8_t vuelta = 0; vuelta < 2; vuelta++) {
    for (int8_t ang = -45; ang <= 60; ang += 15) {
      for (uint8_t k = 0; k < NUM_SERVOS; k++) {
        moverServo(k, SERVO_CENTRO[k] + SERVO_SENTIDO[k] * ang);
      }
      Serial.print(F("  ang "));
      Serial.println(ang);
      delay(400);
    }
  }
  moverAmbos(90);
}

void setup() {
  Serial.begin(9600);
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
  moverAmbos(90);
  Serial.println(F("Comandos: c centro, a 0°, z 180°, s barrido completo, b barrido del detector"));
}

void loop() {
  if (!Serial.available()) {
    return;
  }
  switch (Serial.read()) {
    case 'c': moverAmbos(90); break;
    case 'a': moverAmbos(0); break;
    case 'z': moverAmbos(180); break;
    case 's': barridoCompleto(); break;
    case 'b': barridoDetector(); break;
  }
}
