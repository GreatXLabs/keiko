#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

Adafruit_PWMServoDriver pwm = Adafruit_PWMServoDriver(); // addr 0x40 default
VL53L0X sensor; // addr 0x29 default

const uint8_t SERVO_CHANNEL = 0;
// ponytail: pulse counts for a generic SG90, real servos drift a few % — tune against yours with a multimeter/scope if the sweep clips before 10/170 deg
const uint16_t SERVO_MIN = 150;
const uint16_t SERVO_MAX = 600;
const uint8_t SCAN_MIN_DEG = 10;
const uint8_t SCAN_MAX_DEG = 170;
const uint8_t STEP_DEG = 2;
const uint16_t SETTLE_MS = 12; // ponytail: SG90 settle time cut for speed, raise if steps look jittery/overshooting

void setServoAngle(uint8_t angle) {
  uint16_t pulse = map(angle, 0, 180, SERVO_MIN, SERVO_MAX);
  pwm.setPWM(SERVO_CHANNEL, 0, pulse);
}

void scanStep(uint8_t angle) {
  setServoAngle(angle);
  delay(SETTLE_MS);
  uint16_t mm = sensor.readRangeContinuousMillimeters();
  Serial.print(angle);
  Serial.print(",");
  Serial.println(sensor.timeoutOccurred() ? 0 : mm);
}

void setup() {
  Serial.begin(115200);
  Wire.begin();

  pwm.begin();
  pwm.setPWMFreq(50);

  sensor.setTimeout(500);
  if (!sensor.init()) {
    Serial.println("VL53L0X not found");
    while (1) {}
  }
  sensor.setMeasurementTimingBudget(33000); // default "buena precision"; bajarlo a 20000 da mas velocidad pero mas ruido
  sensor.startContinuous();
}

void loop() {
  for (uint8_t a = SCAN_MIN_DEG; a <= SCAN_MAX_DEG; a += STEP_DEG) scanStep(a);
  for (uint8_t a = SCAN_MAX_DEG; a >= SCAN_MIN_DEG; a -= STEP_DEG) scanStep(a);
}
