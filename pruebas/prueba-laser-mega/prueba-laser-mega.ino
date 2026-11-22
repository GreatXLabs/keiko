/*
  Prueba de los 3 VL53L0X (láser) en el Arduino Mega

  Conexiones:
    I2C: SDA -> D20, SCL -> D21, VIN -> 5V (si el módulo tiene regulador), GND
    XSHUT izquierdo -> A8   (queda en 0x30)
    XSHUT derecho   -> A9   (queda en 0x31)
    XSHUT estómago  -> A10  (queda en 0x32)
  El PCA9685 de los servos está en 0x40 y no se toca.

  Imprime los 3 en mm cada 100 ms (9600). "---" = sin eco o fuera de rango.
  Por el monitor serie:
    'i' -> escanea el bus I2C y dice qué direcciones responden
*/

#include <Wire.h>
#include <VL53L0X.h>

const uint8_t NUM = 3;
const uint8_t XSHUT[NUM] = {A8, A9, A10};
const uint8_t DIRECCION[NUM] = {0x30, 0x31, 0x32};
const char *NOMBRE[NUM] = {"izq", "der", "estomago"};
const uint16_t SIN_ECO = 8000;  // el VL53L0X devuelve ~8190 sin eco

VL53L0X sensor[NUM];
bool ok[NUM];

void escanearI2c() {
  Serial.print(F("I2C:"));
  for (uint8_t dir = 1; dir < 127; dir++) {
    Wire.beginTransmission(dir);
    if (Wire.endTransmission() == 0) { Serial.print(F(" 0x")); Serial.print(dir, HEX); }
  }
  Serial.println();
}

void setup() {
  Serial.begin(9600);
  Wire.begin();
  for (uint8_t i = 0; i < NUM; i++) { pinMode(XSHUT[i], OUTPUT); digitalWrite(XSHUT[i], LOW); }
  delay(20);
  // Se prenden de a uno: todos arrancan en 0x29, así que cada uno recibe su dirección antes del siguiente
  for (uint8_t i = 0; i < NUM; i++) {
    pinMode(XSHUT[i], INPUT);  // alta impedancia: el módulo lo sube solo
    delay(20);
    sensor[i].setTimeout(100);
    ok[i] = sensor[i].init();
    if (ok[i]) {
      sensor[i].setAddress(DIRECCION[i]);
      sensor[i].setMeasurementTimingBudget(20000);
      sensor[i].startContinuous();
    }
    Serial.print(NOMBRE[i]);
    Serial.println(ok[i] ? F(": OK") : F(": NO RESPONDE (revisar XSHUT, VIN, SDA/SCL)"));
  }
  escanearI2c();
}

void loop() {
  if (Serial.available() && Serial.read() == 'i') escanearI2c();
  for (uint8_t i = 0; i < NUM; i++) {
    Serial.print(NOMBRE[i]);
    Serial.print(F(": "));
    if (!ok[i]) { Serial.print(F("N/A    ")); continue; }
    uint16_t mm = sensor[i].readRangeContinuousMillimeters();
    if (sensor[i].timeoutOccurred() || mm >= SIN_ECO) Serial.print(F("---    "));
    else { Serial.print(mm); Serial.print(F(" mm  ")); }
  }
  Serial.println();
  delay(100);
}
