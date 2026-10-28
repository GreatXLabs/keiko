/*
  Diagnóstico de los dos VL53L0X: los apaga con XSHUT (A0 = izq, A1 = der)
  y los prende de a uno, buscando qué direcciones I2C responden en cada caso.
  El PCA9685 aparece siempre en 0x40.
*/

#include <Wire.h>

const uint8_t XSHUT_IZQ = A0;
const uint8_t XSHUT_DER = A1;

void escanear(const __FlashStringHelper *titulo) {
  Serial.print(titulo);
  Serial.print(F(":"));
  uint8_t encontrados = 0;
  for (uint8_t dir = 1; dir < 127; dir++) {
    Wire.beginTransmission(dir);
    if (Wire.endTransmission() == 0) {
      Serial.print(F(" 0x"));
      Serial.print(dir, HEX);
      encontrados++;
    }
  }
  if (encontrados == 0) {
    Serial.print(F(" nada"));
  }
  Serial.println();
}

// LOW apaga el sensor; INPUT lo suelta y el pull-up del módulo lo prende
void xshut(uint8_t pin, bool prendido) {
  if (prendido) {
    pinMode(pin, INPUT);
  } else {
    pinMode(pin, OUTPUT);
    digitalWrite(pin, LOW);
  }
}

void setup() {
  Serial.begin(9600);
  Wire.begin();
  delay(300);

  xshut(XSHUT_IZQ, false);
  xshut(XSHUT_DER, false);
  delay(50);
  escanear(F("Los dos apagados   "));

  xshut(XSHUT_IZQ, true);
  delay(50);
  escanear(F("Solo izq (A0)      "));

  xshut(XSHUT_IZQ, false);
  xshut(XSHUT_DER, true);
  delay(50);
  escanear(F("Solo der (A1)      "));

  xshut(XSHUT_IZQ, true);
  delay(50);
  escanear(F("Los dos prendidos  "));
}

void loop() {}
