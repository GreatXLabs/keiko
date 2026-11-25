/*
  Orquita (Cliente - Actor A-01): firmware para Arduino Mega 2560
  (shield L293D, PCA9685, 3x HC-SR04, 3x VL53L0X, HC-05 en Serial1 @ 9600 bps).

  Alineado al DCU y Consigna:
    - RF-01: mide 3 HC-SR04 + 2 VL53L0X en barrido (en BUSQUEDA; omite barrido en ESCAPE)
             y transmite TELEMETRY por Bluetooth.
    - RF-04: parsea COMMAND:<vL>,<vR>,<ESTADO> y aplica Fail-Safe (< 500 ms) deteniendo motores.
    - RF-05: detecta ingesta física en el estómago por tasa de señal MCPS (sin bloqueo) y
             emite EVENT:CAPTURA_LOBITO_<n>.
    - RF-08: recibe STATE_CHANGE:ESCAPE / estado ESCAPE y detiene el barrido de servomotores.

  Diseño no bloqueante: cada vuelta del loop() ejecuta como máximo una tarea lenta.
*/

#include <Wire.h>
#include <AFMotor.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

#define KEIKO_VERSION "2.0.9"
#define KEIKO_RIG     "mega-3us-2laser"
#define ENLACE        Serial1          // HC-05 Bluetooth RFCOMM/SPP
const unsigned long BAUD_ENLACE = 9600;

// ---------------- Tracción 4WD ----------------
AF_DCMotor m1(1), m2(2), m3(3), m4(4);
AF_DCMotor *motor[4] = {&m1, &m2, &m3, &m4};
const bool ES_IZQUIERDO[4] = {true, true, false, false};
const bool INVERTIDO[4] = {true, true, true, true};
const float MAX_VEL_SERVIDOR = 8.0f;
const uint8_t PWM_ZONA_MUERTA = 105;
const uint8_t PWM_TOPE = 255;

// ---------------- Servos (PCA9685 0x40) ----------------
Adafruit_PWMServoDriver pca;
const uint8_t DIR_PCA = 0x40;
const uint8_t LASER_CANAL[2] = {0, 1};
const int8_t LASER_SENTIDO[2] = {-1, 1};                 // -45° al centro, +60° afuera
const uint16_t SERVO_US_0 = 500, SERVO_US_180 = 2400;
const int8_t BARRIDO_MIN = -45, BARRIDO_MAX = 60, BARRIDO_PASO = 2;
const unsigned long BARRIDO_PASO_MS = 28;
const unsigned long LASER_ASENTAR_MS = 8;

const uint8_t PICKER_CANAL[2] = {2, 3};
const uint16_t PICKER_NEUTRO[2] = {1485, 1500};
const int8_t PICKER_SENTIDO[2] = {1, -1};
const uint16_t PICKER_VEL_US[2] = {360, 300};
const uint8_t PICKER_PASOS_ARRANQUE = 10;
const unsigned long PICKER_MS_POR_PASO = 50;
const unsigned long PICKER_EXTRA_MS = 2500;
const unsigned long PICKER_MAX_MS = 10000;

// ---------------- Láseres VL53L0X ----------------
const uint8_t XSHUT[3] = {A8, A9, A10};                  // izq, der, estómago
const uint8_t DIRECCION[3] = {0x30, 0x31, 0x32};
const char *NOMBRE_TOF[3] = {"LASER_IZQ", "LASER_DER", "ESTOMAGO"};
const uint32_t TOF_PRESUPUESTO_US = 30000;
const unsigned long TOF_PERIODO_MS = 25;
const uint32_t TOF_ESTOMAGO_PRESUPUESTO_US = 20000;
const uint16_t TOF_SIN_ECO_MM = 8000;
const float SIN_ECO_CM = 999.0f;

// ---------------- Ultrasonidos HC-SR04 ----------------
const uint8_t US_TRIG[3] = {24, 26, 28};
const uint8_t US_ECHO[3] = {25, 27, 29};
const unsigned long US_TIMEOUT_US = 15000UL;
const float LASER_B_CM[2] = {-2.5f, -4.0f};
const float US_A[3] = {1.04f, 1.04f, 1.04f};
const float US_B[3] = {0.25f, 0.77f, 0.57f};
const unsigned long US_ENTRE_DISPAROS_MS = 20;

// ---------------- Estómago ----------------
const uint8_t CAPTURAS_META = 3;
const uint8_t MUESTRAS_BASE = 10;
const uint8_t UMBRAL_SENAL_PCT = 20;
const uint8_t LECTURAS_CONFIRMAR = 1;
const unsigned long CAPTURA_ENFRIAMIENTO_MS = 3000;

// ---------------- Protocolo ----------------
const unsigned long TELEMETRIA_MS = 100;
const unsigned long FAILSAFE_MS = 500;
const unsigned long HEARTBEAT_REPOSO_MS = 250;

// ---------------- Estado ----------------
VL53L0X tof[3];
bool tofOk[3];
float usCm[3] = {SIN_ECO_CM, SIN_ECO_CM, SIN_ECO_CM};
float laserCm[2] = {SIN_ECO_CM, SIN_ECO_CM};
uint16_t estomagoSenal = 0;     // Tasa de retorno en MCPS (punto fijo 9.7); 0 = sin dato
uint8_t usTurno = 0;
unsigned long usUltimo = 0, tofUltimo[3] = {0, 0, 0};

bool corrida = false;
char estado[16] = "BUSQUEDA";
unsigned long ultimoServidor = 0;
bool failSafe = false;

int8_t angulo = 0;
int8_t sentidoBarrido = 1;
unsigned long ultimoPasoBarrido = 0, ultimoMovServo = 0;
bool pcaOk = false;

uint8_t pickerEscala = 0;
bool pickerQuiere = false;
unsigned long pickerApagarMs = 0, pickerUltimoPaso = 0, pickerInicioMs = 0;
bool pickerBloqueado = false;

enum Detector : uint8_t { DESARMADO, CALIBRANDO, ARMADO };
Detector detector = DESARMADO;
uint32_t baseAcum = 0;
uint8_t baseN = 0, cambiosSeguidos = 0;
uint16_t baseSenal = 0;
uint8_t capturas = 0;
unsigned long ultimaCaptura = 0;

char rx[64];
uint8_t rxLen = 0;
unsigned long ultimaTelemetria = 0, ultimoHeartbeat = 0;

// ============================================================ tracción (RF-04)
uint8_t pwmDeVelocidad(float v) {
  float a = fabs(v);
  if (a < 0.05f) return 0;
  int rango = (int)PWM_TOPE - (int)PWM_ZONA_MUERTA;
  if (rango < 0) rango = 0;
  int pwm = (int)PWM_ZONA_MUERTA + (int)((a / MAX_VEL_SERVIDOR) * rango);
  return (uint8_t)constrain(pwm, 0, 255);
}

void mover(uint8_t k, float v) {
  uint8_t pwm = pwmDeVelocidad(v);
  if (pwm == 0) { motor[k]->run(RELEASE); return; }
  uint8_t sentido = (v > 0) ? FORWARD : BACKWARD;
  if (INVERTIDO[k]) sentido = (sentido == FORWARD) ? BACKWARD : FORWARD;
  motor[k]->setSpeed(pwm);
  motor[k]->run(sentido);
}

void traccion(float vL, float vR) {
  for (uint8_t k = 0; k < 4; k++) mover(k, ES_IZQUIERDO[k] ? vL : vR);
}

void frenarMotores() {
  for (uint8_t k = 0; k < 4; k++) motor[k]->run(RELEASE);
}

// ============================================================ servos y picker
void moverServoLaser(uint8_t k, int16_t grados) {
  grados = constrain(grados, 0, 180);
  pca.writeMicroseconds(LASER_CANAL[k], map(grados, 0, 180, SERVO_US_0, SERVO_US_180));
}

void posicionarLaseres(int8_t ang) {
  for (uint8_t k = 0; k < 2; k++) moverServoLaser(k, 90 + LASER_SENTIDO[k] * ang);
  angulo = ang;
  ultimoMovServo = millis();
}

void configurarPca() {
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
}

void aplicarPicker() {
  for (uint8_t k = 0; k < 2; k++) {
    if (pickerEscala == 0) { pca.setPWM(PICKER_CANAL[k], 0, 0); continue; }
    int16_t d = (int32_t)PICKER_VEL_US[k] * pickerEscala / PICKER_PASOS_ARRANQUE;
    pca.writeMicroseconds(PICKER_CANAL[k], PICKER_NEUTRO[k] - PICKER_SENTIDO[k] * d);
  }
}

void vigilarPca() {
  Wire.beginTransmission(DIR_PCA); Wire.write((uint8_t)0x00);
  if (Wire.endTransmission() != 0) return;
  Wire.requestFrom(DIR_PCA, (uint8_t)1);
  if (!Wire.available() || !(Wire.read() & 0x10)) return;
  configurarPca(); aplicarPicker(); posicionarLaseres(angulo);
  ENLACE.println(F("ERROR:PCA_REINICIADO"));
}

void pickerQuerer(bool si, unsigned long extraMs = 0) {
  if (si && pickerBloqueado) return;
  pickerQuiere = si;
  if (si) pickerApagarMs = millis() + extraMs;
}

void detenerPicker() { pickerQuiere = false; pickerApagarMs = 0; }
void cortarPicker() { detenerPicker(); pickerBloqueado = true; }

void atenderPicker(unsigned long ahora) {
  bool debeGirar = pickerQuiere || (pickerApagarMs && (long)(ahora - pickerApagarMs) < 0);
  if (debeGirar && pickerEscala == 0) pickerInicioMs = ahora;
  if (debeGirar && pickerEscala > 0 && ahora - pickerInicioMs >= PICKER_MAX_MS) {
    cortarPicker(); debeGirar = false;
    ENLACE.println(F("ERROR:PICKER_TOPE_10S"));
  }
  if (debeGirar && pickerEscala < PICKER_PASOS_ARRANQUE && ahora - pickerUltimoPaso >= PICKER_MS_POR_PASO) {
    pickerEscala++; pickerUltimoPaso = ahora; if (pcaOk) aplicarPicker();
  } else if (!debeGirar && pickerEscala > 0) {
    pickerEscala = 0; pickerApagarMs = 0; if (pcaOk) aplicarPicker();
  }
}

bool estadoFinal() { return !strcmp(estado, "ESCAPE") || !strcmp(estado, "FINALIZACION"); }

// RF-01 / RF-08: En ESCAPE o FINALIZACION detiene el barrido de servomotores.
void atenderBarrido(unsigned long ahora) {
  if (!pcaOk || !corrida || estadoFinal() || ahora - ultimoPasoBarrido < BARRIDO_PASO_MS) return;
  ultimoPasoBarrido = ahora;
  int8_t sig = angulo + sentidoBarrido * BARRIDO_PASO;
  if (sig > BARRIDO_MAX || sig < BARRIDO_MIN) { sentidoBarrido = -sentidoBarrido; sig = angulo + sentidoBarrido * BARRIDO_PASO; }
  posicionarLaseres(sig);
}

// ============================================================ sensores (RF-01)
float leerUsCm(uint8_t i) {
  digitalWrite(US_TRIG[i], LOW); delayMicroseconds(2);
  digitalWrite(US_TRIG[i], HIGH); delayMicroseconds(10);
  digitalWrite(US_TRIG[i], LOW);
  unsigned long us = pulseIn(US_ECHO[i], HIGH, US_TIMEOUT_US);
  if (!us) return SIN_ECO_CM;
  return max(0.0f, US_A[i] * (us * 0.0343f / 2.0f) + US_B[i]);
}

bool leerEstomago() {
  if ((tof[2].readReg(VL53L0X::RESULT_INTERRUPT_STATUS) & 0x07) == 0) return false;
  uint8_t b[12] = {0};
  Wire.beginTransmission(DIRECCION[2]);
  Wire.write(VL53L0X::RESULT_RANGE_STATUS);
  bool ok = Wire.endTransmission() == 0 && Wire.requestFrom(DIRECCION[2], (uint8_t)12) == 12;
  for (uint8_t k = 0; ok && k < 12; k++) b[k] = Wire.read();
  tof[2].writeReg(VL53L0X::SYSTEM_INTERRUPT_CLEAR, 0x01);
  estomagoSenal = ok ? max((uint16_t)1, (uint16_t)(b[6] << 8 | b[7])) : 0;
  return true;
}

void atenderSensores(unsigned long ahora) {
  if (tofOk[2] && leerEstomago()) { tofUltimo[2] = ahora; return; }
  if (ahora - usUltimo >= US_ENTRE_DISPAROS_MS) {
    usUltimo = ahora;
    usCm[usTurno] = leerUsCm(usTurno);
    usTurno = (usTurno + 1) % 3;
    return;
  }
  if (estadoFinal()) return;
  for (uint8_t i = 0; i < 2; i++) {
    if (!tofOk[i] || ahora - tofUltimo[i] < TOF_PERIODO_MS) continue;
    if (ahora - ultimoMovServo < LASER_ASENTAR_MS) continue;
    tofUltimo[i] = ahora;
    uint16_t mm = tof[i].readRangeContinuousMillimeters();
    bool ok = !tof[i].timeoutOccurred() && mm < TOF_SIN_ECO_MM;
    laserCm[i] = ok ? max(0.0f, mm / 10.0f + LASER_B_CM[i]) : SIN_ECO_CM;
    return;
  }
}

// ============================================================ estómago (RF-05)
void armarEstomago() {
  if (!tofOk[2] || capturas >= CAPTURAS_META) { detector = DESARMADO; return; }
  baseAcum = 0; baseN = 0; cambiosSeguidos = 0; detector = CALIBRANDO;
}

void atenderEstomago() {
  static unsigned long ultimaMuestra = 0;
  if (detector == DESARMADO || estomagoSenal == 0 || tofUltimo[2] == ultimaMuestra) return;
  if (ultimaCaptura && millis() - ultimaCaptura < CAPTURA_ENFRIAMIENTO_MS) return;
  ultimaMuestra = tofUltimo[2];
  if (detector == CALIBRANDO) {
    baseAcum += estomagoSenal;
    if (++baseN >= MUESTRAS_BASE) { baseSenal = baseAcum / baseN; detector = ARMADO; }
    return;
  }
  uint32_t d = estomagoSenal > baseSenal ? estomagoSenal - baseSenal : baseSenal - estomagoSenal;
  cambiosSeguidos = (d * 100 >= (uint32_t)baseSenal * UMBRAL_SENAL_PCT) ? cambiosSeguidos + 1 : 0;
  if (cambiosSeguidos >= LECTURAS_CONFIRMAR) {
    capturas++; ultimaCaptura = millis();
    ENLACE.print(F("EVENT:CAPTURA_LOBITO_")); ENLACE.println(capturas);
    cortarPicker();
    detector = DESARMADO;
  }
}

// ============================================================ protocolo
void enviarHello() {
  ENLACE.print(F("HELLO:"));
  ENLACE.print(F(KEIKO_VERSION));
  ENLACE.print(F(","));
  ENLACE.println(F(KEIKO_RIG));
}

void empezarCorrida() {
  if (corrida) { ultimoServidor = millis(); failSafe = false; return; }
  corrida = true; failSafe = false; capturas = 0; ultimaCaptura = 0; pickerBloqueado = false;
  strcpy(estado, "BUSQUEDA");
  ultimoServidor = millis();
  sentidoBarrido = 1;
  if (pcaOk) posicionarLaseres(BARRIDO_MIN);
  armarEstomago();
  enviarHello();
  for (uint8_t i = 0; i < 3; i++)
    if (!tofOk[i]) { ENLACE.print(F("ERROR:SENSOR_")); ENLACE.println(NOMBRE_TOF[i]); }
}

void terminarCorrida() {
  corrida = false; frenarMotores(); detenerPicker(); detector = DESARMADO;
  strcpy(estado, "BUSQUEDA");
  if (pcaOk) posicionarLaseres(0);
}

void nuevoEstado(const char *e) {
  bool cambio = strcmp(estado, e) != 0;
  strncpy(estado, e, sizeof(estado) - 1); estado[sizeof(estado) - 1] = 0;
  if (!strcmp(estado, "ERROR_SEGURO")) {
    frenarMotores(); detenerPicker();
    return;
  }
  if (estadoFinal()) {
    detector = DESARMADO; detenerPicker();
    if (cambio && pcaOk) posicionarLaseres(0);
    if (!strcmp(estado, "FINALIZACION")) frenarMotores();
    return;
  }
  if (detector == DESARMADO) armarEstomago();
  if (!strcmp(estado, "APROXIMACION") || !strcmp(estado, "CAPTURA")) pickerQuerer(true, PICKER_EXTRA_MS);
  else { pickerBloqueado = false; pickerQuerer(false); }
}

void procesarLinea(char *l) {
  if (!strcmp(l, "CMD:START")) { empezarCorrida(); return; }
  if (!strcmp(l, "CMD:STOP")) { terminarCorrida(); return; }
  if (!strcmp(l, "HEARTBEAT")) { ultimoServidor = millis(); failSafe = false; return; }
  if (!corrida) return;
  if (!strncmp(l, "COMMAND:", 8)) {
    char *c1 = strchr(l + 8, ',');
    if (!c1) return;
    char *c2 = strchr(c1 + 1, ',');
    float vL = atof(l + 8), vR = atof(c1 + 1);
    ultimoServidor = millis(); failSafe = false;
    if (c2) nuevoEstado(c2 + 1);
    traccion(vL, vR);
  } else if (!strncmp(l, "STATE_CHANGE:", 13)) {
    ultimoServidor = millis(); failSafe = false;
    nuevoEstado(l + 13);
  }
}

void atenderEnlace() {
  while (ENLACE.available()) {
    char c = ENLACE.read();
    if (c == '\n' || c == '\r') {
      if (rxLen) { rx[rxLen] = 0; procesarLinea(rx); rxLen = 0; }
    } else if (rxLen < sizeof(rx) - 1) {
      rx[rxLen++] = c;
    } else {
      rxLen = 0;
    }
  }
  // RF-04 (Curso alterno 1.1-1.2): pérdida de enlace -> estado seguro (detención inmediata)
  unsigned long ahora = millis();
  if (corrida && !failSafe && ahora - ultimoServidor > FAILSAFE_MS) {
    failSafe = true;
    frenarMotores(); detenerPicker();
    ENLACE.println(F("ERROR:FAILSAFE_TIMEOUT"));
  }
}

void enviarTelemetria(unsigned long ahora) {
  if (ahora - ultimaTelemetria >= TELEMETRIA_MS) {
    if (ENLACE.availableForWrite() < 56) return;
    ultimaTelemetria = ahora;
    ENLACE.print(F("TELEMETRY:"));
    for (uint8_t i = 0; i < 3; i++) { ENLACE.print(usCm[i], 1); ENLACE.print(','); }
    ENLACE.print(laserCm[0], 1); ENLACE.print(',');
    ENLACE.print(laserCm[1], 1); ENLACE.print(',');
    ENLACE.print(angulo); ENLACE.print(',');
    ENLACE.println(ahora);
    return;
  }
  if (!corrida && ahora - ultimoHeartbeat >= HEARTBEAT_REPOSO_MS) {
    if (ENLACE.availableForWrite() < 14) return;
    ultimoHeartbeat = ahora;
    ENLACE.println(F("HEARTBEAT"));
  }
}

// ============================================================ setup / loop
void setup() {
  ENLACE.begin(BAUD_ENLACE);
  Wire.begin();
  configurarPca();
  Wire.beginTransmission(DIR_PCA);
  pcaOk = (Wire.endTransmission() == 0);
  if (!pcaOk) ENLACE.println(F("ERROR:PCA9685_NO_RESPONDE"));

  for (uint8_t i = 0; i < 3; i++) { pinMode(US_TRIG[i], OUTPUT); pinMode(US_ECHO[i], INPUT); }
  for (uint8_t i = 0; i < 3; i++) { pinMode(XSHUT[i], OUTPUT); digitalWrite(XSHUT[i], LOW); }
  delay(20);
  for (uint8_t i = 0; i < 3; i++) {
    pinMode(XSHUT[i], INPUT); delay(20);
    tof[i].setTimeout(100);
    tofOk[i] = tof[i].init();
    if (tofOk[i]) {
      tof[i].setAddress(DIRECCION[i]);
      tof[i].setMeasurementTimingBudget(i == 2 ? TOF_ESTOMAGO_PRESUPUESTO_US : TOF_PRESUPUESTO_US);
      tof[i].startContinuous();
    } else {
      ENLACE.print(F("ERROR:SENSOR_")); ENLACE.println(NOMBRE_TOF[i]);
    }
  }
  for (uint8_t k = 0; k < 4; k++) motor[k]->setSpeed(0);
  frenarMotores();
  if (pcaOk) { posicionarLaseres(0); aplicarPicker(); }
  enviarHello();
}

void loop() {
  atenderEnlace();
  atenderSensores(millis());
  atenderEstomago();
  atenderBarrido(millis());
  atenderPicker(millis());
  static unsigned long vigilaPca = 0;
  unsigned long ahora = millis();
  if (pcaOk && ahora - vigilaPca >= 500) { vigilaPca = ahora; vigilarPca(); }
  enviarTelemetria(millis());
}
