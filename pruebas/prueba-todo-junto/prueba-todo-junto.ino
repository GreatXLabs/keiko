/*
  Prueba de caída de tensión: todo funcionando a la vez en el Arduino Mega.
  Motores de tracción (shield L293D), picker (servos 360), barrido de los láseres,
  3 láseres VL53L0X y 3 ultrasonidos. Los comandos entran por USB o por el HC-05
  (Serial1, 9600) y cada 250 ms sale una línea de estado por los dos.

  Comandos:
    W A S D  mueven (mantener la tecla; frena a los 450 ms sin teclas)
    espacio  frena los motores
    g        prende/apaga el picker
    l        prende/apaga el barrido de los láseres (-45..+60, paso 5, 40 ms)
    + / -    sube o baja la velocidad de los motores
    z        pone en cero el contador de arranques

  Línea de estado: t = segundos desde el arranque, boots = arranques guardados en
  EEPROM (si sube solo, el Mega se reinició: caída de tensión), pca = veces que
  se reinició el PCA9685, vlTO = timeouts de los láseres.
*/

#include <Wire.h>
#include <EEPROM.h>
#include <AFMotor.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

// Motores
AF_DCMotor m1(1), m2(2), m3(3), m4(4);
AF_DCMotor *motor[4] = {&m1, &m2, &m3, &m4};
const bool ES_IZQUIERDO[4] = {true, true, false, false};
const bool INVERTIDO[4] = {true, true, true, true};
const uint16_t HOLD_MS = 450;
uint8_t velocidad = 180;
unsigned long ultimaTecla = 0;
bool enMarcha = false;
char ultimaDir = '-';

// Servos
Adafruit_PWMServoDriver pca;
const uint8_t DIR_PCA = 0x40;
const uint8_t LASER_CANAL[2] = {0, 1};
const int8_t LASER_SENTIDO[2] = {1, -1};
const uint8_t PICKER_CANAL[2] = {2, 3};
const uint16_t PICKER_NEUTRO[2] = {1485, 1500};
const int8_t PICKER_SENTIDO[2] = {1, -1};
const uint16_t PICKER_VEL[2] = {360, 300};
bool pickerOn = false, barrido = false;
int8_t angulo = -45;
int8_t paso = 5;
unsigned long ultimoPaso = 0;
uint16_t pcaReinicios = 0;

// Láseres
const uint8_t XSHUT[3] = {A8, A9, A10};
const uint8_t DIRECCION[3] = {0x30, 0x31, 0x32};
VL53L0X laser[3];
bool laserOk[3];
int16_t laserMm[3] = {-1, -1, -1};
uint16_t vlTimeouts = 0;

// Ultrasonidos
const uint8_t US_TRIG[3] = {24, 26, 28};
const uint8_t US_ECHO[3] = {25, 27, 29};
float usCm[3] = {-1, -1, -1};
uint8_t usTurno = 0;

unsigned long ultimoEstado = 0;
uint16_t boots;

void correr(uint8_t k, uint8_t sentido) {
  if (INVERTIDO[k] && sentido != RELEASE) sentido = (sentido == FORWARD) ? BACKWARD : FORWARD;
  motor[k]->run(sentido);
}
void manejar(uint8_t izq, uint8_t der, char d) {
  for (uint8_t k = 0; k < 4; k++) correr(k, ES_IZQUIERDO[k] ? izq : der);
  ultimaTecla = millis(); enMarcha = true; ultimaDir = d;
}
void frenar() {
  for (uint8_t k = 0; k < 4; k++) motor[k]->run(RELEASE);
  enMarcha = false; ultimaDir = '-';
}
void fijarVel(int16_t v) {
  velocidad = constrain(v, 0, 255);
  for (uint8_t k = 0; k < 4; k++) motor[k]->setSpeed(velocidad);
}

void configurarPca() {
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
}
void moverLaser(uint8_t k, int16_t grados) {
  grados = constrain(grados, 0, 180);
  pca.writeMicroseconds(LASER_CANAL[k], map(grados, 0, 180, 500, 2400));
}
void aplicarPicker() {
  for (uint8_t k = 0; k < 2; k++) {
    if (pickerOn) pca.writeMicroseconds(PICKER_CANAL[k], PICKER_NEUTRO[k] - PICKER_SENTIDO[k] * PICKER_VEL[k]);
    else pca.setPWM(PICKER_CANAL[k], 0, 0);
  }
}
// Si el PCA se reinició por una caída de tensión arranca dormido
void revisarPca() {
  Wire.beginTransmission(DIR_PCA); Wire.write((uint8_t)0x00);
  if (Wire.endTransmission() != 0) return;
  Wire.requestFrom(DIR_PCA, (uint8_t)1);
  if (!Wire.available() || !(Wire.read() & 0x10)) return;
  configurarPca(); aplicarPicker(); pcaReinicios++;
}

float leerUs(uint8_t i) {
  digitalWrite(US_TRIG[i], LOW); delayMicroseconds(2);
  digitalWrite(US_TRIG[i], HIGH); delayMicroseconds(10);
  digitalWrite(US_TRIG[i], LOW);
  unsigned long us = pulseIn(US_ECHO[i], HIGH, 25000UL);
  return us ? us * 0.0343 / 2.0 : -1;
}

void escribir(const char *s) { Serial.println(s); Serial1.println(s); }

void setup() {
  Serial.begin(9600); Serial1.begin(9600);
  boots = EEPROM.read(0) | (EEPROM.read(1) << 8);
  if (boots == 0xFFFF) boots = 0;
  boots++;
  EEPROM.update(0, boots & 0xFF); EEPROM.update(1, boots >> 8);
  Wire.begin();
  configurarPca();
  for (uint8_t i = 0; i < 3; i++) { pinMode(US_TRIG[i], OUTPUT); pinMode(US_ECHO[i], INPUT); }
  for (uint8_t i = 0; i < 3; i++) { pinMode(XSHUT[i], OUTPUT); digitalWrite(XSHUT[i], LOW); }
  delay(20);
  for (uint8_t i = 0; i < 3; i++) {
    pinMode(XSHUT[i], INPUT); delay(20);
    laser[i].setTimeout(100);
    laserOk[i] = laser[i].init();
    if (laserOk[i]) {
      laser[i].setAddress(DIRECCION[i]);
      laser[i].setMeasurementTimingBudget(20000);
      laser[i].startContinuous();
    }
  }
  fijarVel(velocidad); frenar();
  for (uint8_t k = 0; k < 2; k++) moverLaser(k, 90);
  char b[60];
  snprintf(b, sizeof b, "ARRANQUE boots=%u lasers=%d%d%d", boots, laserOk[0], laserOk[1], laserOk[2]);
  escribir(b);
}

void loop() {
  if (enMarcha && millis() - ultimaTecla > HOLD_MS) frenar();

  int c = -1;
  if (Serial.available()) c = Serial.read();
  else if (Serial1.available()) c = Serial1.read();
  if (c >= 'A' && c <= 'Z') c += 32;
  switch (c) {
    case 'w': manejar(FORWARD, FORWARD, 'W'); break;
    case 's': manejar(BACKWARD, BACKWARD, 'S'); break;
    case 'a': manejar(BACKWARD, FORWARD, 'A'); break;
    case 'd': manejar(FORWARD, BACKWARD, 'D'); break;
    case ' ': frenar(); break;
    case 'g': pickerOn = !pickerOn; aplicarPicker(); break;
    case 'l': barrido = !barrido; if (!barrido) { for (uint8_t k = 0; k < 2; k++) moverLaser(k, 90); } break;
    case '+': fijarVel(velocidad + 25); break;
    case '-': fijarVel(velocidad - 25); break;
    case 'z': boots = 0; EEPROM.update(0, 0); EEPROM.update(1, 0); break;
  }

  // Barrido de los láseres, espejado, sin pausas bloqueantes
  if (barrido && millis() - ultimoPaso >= 40) {
    ultimoPaso = millis();
    for (uint8_t k = 0; k < 2; k++) moverLaser(k, 90 + LASER_SENTIDO[k] * angulo);
    angulo += paso;
    if (angulo >= 60 || angulo <= -45) paso = -paso;
  }

  // Un ultrasonido por vuelta (los tres no se pisan) y los 3 láseres
  usCm[usTurno] = leerUs(usTurno);
  usTurno = (usTurno + 1) % 3;
  for (uint8_t i = 0; i < 3; i++) {
    if (!laserOk[i]) continue;
    uint16_t mm = laser[i].readRangeContinuousMillimeters();
    if (laser[i].timeoutOccurred()) { vlTimeouts++; laserMm[i] = -1; }
    else laserMm[i] = (mm >= 8000) ? -1 : mm;
  }

  if (millis() - ultimoEstado >= 250) {
    ultimoEstado = millis();
    revisarPca();
    char b[120];
    snprintf(b, sizeof b, "t=%lus boots=%u pca=%u vlTO=%u | US %d/%d/%d cm | L %d/%d/%d mm | mot=%c v=%u pk=%d bar=%d",
             millis() / 1000, boots, pcaReinicios, vlTimeouts,
             (int)usCm[0], (int)usCm[1], (int)usCm[2], laserMm[0], laserMm[1], laserMm[2],
             ultimaDir, velocidad, pickerOn, barrido);
    escribir(b);
  }
}
