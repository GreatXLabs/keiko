/*
  Prueba de un VL53L0X (ToF láser) para detectar la canica de vidrio

  Conexiones:
    VIN  -> 5V (si el módulo tiene regulador; si no, a 3.3V)
    GND  -> GND
    SDA  -> A4
    SCL  -> A5
    XSHUT y GPIO1 sin conectar

  Montaje: un solo sensor fijo, a ~15-20 mm del piso, mirando derecho.

  Imprime la distancia ~10 veces por segundo. Por el monitor serie:
    'n' -> modo normal (el de fábrica)
    'l' -> modo largo alcance (más sensible a blancos que devuelven poca luz,
           como el vidrio, pero también ve más el piso)
*/

#include <Wire.h>
#include <VL53L0X.h>

VL53L0X sensor;

// El sensor devuelve ~8190 cuando no ve nada dentro del rango
const uint16_t SIN_OBJETO_MM = 8000;
const unsigned int PAUSA_MS = 100;

void configurarModo(bool largo) {
  sensor.stopContinuous();
  if (largo) {
    sensor.setSignalRateLimit(0.1);
    sensor.setVcselPulsePeriod(VL53L0X::VcselPeriodPreRange, 18);
    sensor.setVcselPulsePeriod(VL53L0X::VcselPeriodFinalRange, 14);
  } else {
    sensor.setSignalRateLimit(0.25);
    sensor.setVcselPulsePeriod(VL53L0X::VcselPeriodPreRange, 14);
    sensor.setVcselPulsePeriod(VL53L0X::VcselPeriodFinalRange, 10);
  }
  sensor.setMeasurementTimingBudget(33000);
  sensor.startContinuous();
  Serial.println(largo ? F("Modo: LARGO ALCANCE") : F("Modo: NORMAL"));
}

void setup() {
  Serial.begin(9600);
  Wire.begin();
  sensor.setTimeout(500);
  if (!sensor.init()) {
    Serial.println(F("No se encontró el VL53L0X: revisá SDA/SCL y alimentación"));
    while (true) {}
  }
  configurarModo(false);
}

void loop() {
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'n') configurarModo(false);
    if (c == 'l') configurarModo(true);
  }

  uint16_t mm = sensor.readRangeContinuousMillimeters();
  if (sensor.timeoutOccurred()) {
    Serial.println(F("Timeout: el sensor no respondió"));
  } else if (mm >= SIN_OBJETO_MM) {
    Serial.println(F("Sin objeto"));
  } else {
    Serial.print(F("Distancia: "));
    Serial.print(mm);
    Serial.println(F(" mm"));
  }
  delay(PAUSA_MS);
}
