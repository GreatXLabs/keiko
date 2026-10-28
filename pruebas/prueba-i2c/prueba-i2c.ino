/*
  Busca dispositivos en el bus I2C (SDA -> A4, SCL -> A5) y, si encuentra el
  PCA9685 en 0x40, muestra su modo y el pulso que tiene cargado en los
  canales 0 y 1.
*/

#include <Wire.h>

const uint8_t DIR_PCA = 0x40;

uint8_t leerRegistro(uint8_t dir, uint8_t reg) {
  Wire.beginTransmission(dir);
  Wire.write(reg);
  Wire.endTransmission();
  Wire.requestFrom(dir, (uint8_t)1);
  return Wire.available() ? Wire.read() : 0xFF;
}

void setup() {
  Serial.begin(9600);
  Wire.begin();
  delay(500);

  Serial.println(F("Buscando dispositivos I2C..."));
  uint8_t encontrados = 0;
  for (uint8_t dir = 1; dir < 127; dir++) {
    Wire.beginTransmission(dir);
    if (Wire.endTransmission() == 0) {
      Serial.print(F("  Encontrado en 0x"));
      Serial.println(dir, HEX);
      encontrados++;
    }
  }
  if (encontrados == 0) {
    Serial.println(F("  Ninguno: revisá SDA/SCL, VCC y GND"));
  }

  Wire.beginTransmission(DIR_PCA);
  if (Wire.endTransmission() != 0) {
    Serial.println(F("No responde el PCA9685 en 0x40"));
    return;
  }
  uint8_t modo1 = leerRegistro(DIR_PCA, 0x00);
  uint8_t prescale = leerRegistro(DIR_PCA, 0xFE);
  Serial.print(F("PCA9685 MODE1=0x"));
  Serial.print(modo1, HEX);
  Serial.println((modo1 & 0x10) ? F(" (dormido: no genera pulsos)") : F(" (activo)"));
  Serial.print(F("PCA9685 PRESCALE="));
  Serial.println(prescale);
  for (uint8_t canal = 0; canal < 2; canal++) {
    uint8_t base = 0x06 + 4 * canal;
    uint16_t off = leerRegistro(DIR_PCA, base + 2) | ((leerRegistro(DIR_PCA, base + 3) & 0x0F) << 8);
    Serial.print(F("  Canal "));
    Serial.print(canal);
    Serial.print(F(": OFF="));
    Serial.println(off);
  }
}

void loop() {}
