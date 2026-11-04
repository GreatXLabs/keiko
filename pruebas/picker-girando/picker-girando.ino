/*
  Picker girando: los dos servos SG90 modificados a 360° (canales 2 y 3 del
  PCA9685) arrancan a girar solos al prender el Mega y no paran.

  Conexiones:
    PCA9685 SDA -> D20, SCL -> D21 (I2C del Mega), VCC -> 5V, GND -> GND
    PCA9685 V+ (bornera) -> 5V para los servos, con GND común
    Servos picker -> canal 2 = izquierdo, canal 3 = derecho

  Neutro y sentido calibrados con pruebas/prueba-servo-360. Por el monitor
  serie (9600), opcional: 'p' frena (corta la señal), 'g' vuelve a girar.
*/

#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>

const uint8_t NUM_SERVOS = 2;
const uint8_t CANAL[NUM_SERVOS] = {2, 3};
const uint16_t NEUTRO[NUM_SERVOS] = {1475, 1500};
// 1 = recolecta con pulsos bajos (debajo del neutro), -1 = con pulsos altos.
// Verificado el 2026-11-02 con los servos montados: el izquierdo recolecta
// con pulsos bajos y el derecho con pulsos altos.
const int8_t SENTIDO[NUM_SERVOS] = {1, -1};
// Distancia al neutro: más lejos = más rápido y con más fuerza. Distinta en
// cada uno para que giren parejos (el izquierdo es más lento con el mismo
// valor). Igualados a ojo el 2026-11-02 en {120, 100}; subidos manteniendo
// la proporción.
const uint16_t VEL_US[NUM_SERVOS] = {240, 200};
// Arranque suave: la velocidad sube en PASOS_ARRANQUE pasos para no hundir
// los 5 V de golpe (con los servos colgados del USB el Mega se reiniciaba)
const uint8_t PASOS_ARRANQUE = 10;
const uint8_t MS_POR_PASO = 50;

Adafruit_PWMServoDriver pca;

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
  girar();
}

void loop() {
  if (!Serial.available()) {
    return;
  }
  switch (Serial.read()) {
    case 'p': frenar(); break;
    case 'g': girar(); break;
  }
}
