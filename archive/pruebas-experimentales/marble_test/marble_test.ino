#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

Adafruit_PWMServoDriver pwm = Adafruit_PWMServoDriver();
VL53L0X sensor;

const uint16_t SERVO_MIN = 150;
const uint16_t SERVO_MAX = 600;

void setup() {
  Serial.begin(115200);
  Wire.begin();

  pwm.begin();
  pwm.setPWMFreq(50);
  pwm.setPWM(0, 0, map(90, 0, 180, SERVO_MIN, SERVO_MAX)); // servo fijo mirando al frente

  sensor.setTimeout(500);
  if (!sensor.init()) {
    Serial.println("VL53L0X not found");
    while (1) {}
  }
  sensor.setMeasurementTimingBudget(33000);
  sensor.startContinuous();
}

void loop() {
  uint16_t mm = sensor.readRangeContinuousMillimeters();
  if (sensor.timeoutOccurred()) {
    Serial.println("TIMEOUT");
  } else {
    Serial.println(mm);
  }
}
