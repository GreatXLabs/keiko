/*
  Orquita: firmware cliente para Arduino Mega 2560 (shield L293D, PCA9685, 3 VL53L0X,
  3 HC-SR04, HC-05 en Serial1). Sin lógica estratégica: lee sensores, manda
  telemetría y ejecuta lo que ordena el servidor.

  Diseño: loop() sin bloqueos largos. Cada vuelta hace como mucho UNA tarea lenta
  (un ultrasonido, o leer un láser ya listo). Los motores y el picker siguen
  andando por PWM de hardware mientras se leen los sensores.

  Protocolo (líneas ASCII terminadas en \n, como keiko-server):
    Robot -> servidor
      HELLO:<version>,<rig>
      TELEMETRY:<us_izq>,<us_cen>,<us_der>,<laser_izq>,<laser_der>,<ang_servo>,<ts_ms>
          distancias en cm; 999 = sin eco (no significa "libre")
      EVENT:CAPTURA_LOBITO_<n>
      HEARTBEAT   (en reposo)
      ERROR:<detalle>
    Servidor -> robot
      COMMAND:<vL>,<vR>,<ESTADO>   velocidades en unidades del servidor (±MAX_VEL_SERVIDOR)
      STATE_CHANGE:<ESTADO>
      HEARTBEAT
      CMD:START / CMD:STOP         empieza / termina la corrida
  Fail-safe: más de 500 ms sin COMMAND ni HEARTBEAT durante la corrida -> frena todo.

  Manejo manual para pruebas (solo en reposo, minúsculas, sin \n):
    w a s d mover (mantener), espacio frena, k empezar corrida, x terminar
  Poner PERMITIR_MANUAL en 0 para la competencia (reglamento: sin órdenes por consola).

  Valores validados en el robot real (2026-11-19): ver constantes con "validado".
*/

#include <Wire.h>
#include <AFMotor.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

#define KEIKO_VERSION "2.0.4"
#define KEIKO_RIG     "mega-3us-2laser"

#define PERMITIR_MANUAL 1       // [SOLO PRUEBAS / DEBUG - poner en 0 en codigo final de competencia]
#define DEPURAR_ESTOMAGO 1      // [SOLO PRUEBAS / DEBUG - envia DBG:estomago=... en paralelo sin pisar TELEMETRY]
#define ESPEJAR_USB 0           // [SOLO PRUEBAS / DEBUG - copia la telemetria al USB (115200)]
#define ENLACE Serial1          // HC-05
const unsigned long BAUD_ENLACE = 9600;

// ---------------- Tracción ----------------
AF_DCMotor m1(1), m2(2), m3(3), m4(4);
AF_DCMotor *motor[4] = {&m1, &m2, &m3, &m4};
const bool ES_IZQUIERDO[4] = {true, true, false, false};
const bool INVERTIDO[4] = {true, true, true, true};      // validado
const float MAX_VEL_SERVIDOR = 8.0f;
// [SOLO PRUEBAS / DEBUG - ajustable por CMD:PWM:<min>,<max> para calibrar arranque en piso y luego fijar como const]
uint8_t pwmZonaMuerta = 105;
uint8_t pwmTope = 255;
const uint8_t PWM_MANUAL = 180;                          // validado: capturó canicas
const unsigned long MANUAL_HOLD_MS = 450;

// ---------------- Servos (PCA9685) ----------------
Adafruit_PWMServoDriver pca;
const uint8_t DIR_PCA = 0x40;
const uint8_t LASER_CANAL[2] = {0, 1};
const int8_t LASER_SENTIDO[2] = {-1, 1};                 // validado fisicamente: -45 al centro, +60 afuera
const uint16_t SERVO_US_0 = 500, SERVO_US_180 = 2400;
const int8_t BARRIDO_MIN = -45, BARRIDO_MAX = 60, BARRIDO_PASO = 1;  // 1° por paso sincronizado con VL53L0X
const unsigned long BARRIDO_PASO_MS = 30;
const unsigned long LASER_ASENTAR_MS = 10;               // espera tras mover antes de medir

const uint8_t PICKER_CANAL[2] = {2, 3};
const uint16_t PICKER_NEUTRO[2] = {1485, 1500};          // validado
const int8_t PICKER_SENTIDO[2] = {1, -1};
const uint16_t PICKER_VEL_US[2] = {360, 300};            // validado: capturó canicas
const uint8_t PICKER_PASOS_ARRANQUE = 10;
const unsigned long PICKER_MS_POR_PASO = 50;
const unsigned long PICKER_EXTRA_MS = 4000;              // sigue girando tras APROXIMACION para tragar
const unsigned long PICKER_MAX_MS = 10000;               // tope duro de giro continuo (regla del usuario)

// ---------------- Láseres VL53L0X ----------------
const uint8_t XSHUT[3] = {A8, A9, A10};                  // izq, der, estómago
const uint8_t DIRECCION[3] = {0x30, 0x31, 0x32};
const char *NOMBRE_TOF[3] = {"LASER_IZQ", "LASER_DER", "ESTOMAGO"};
const uint32_t TOF_PRESUPUESTO_US = 30000;
const unsigned long TOF_PERIODO_MS = 30;
// Estómago (detector de canicas): 20 ms es el mínimo útil del VL53L0X
const uint32_t TOF_ESTOMAGO_PRESUPUESTO_US = 20000;
const unsigned long TOF_ESTOMAGO_PERIODO_MS = 20;
const uint16_t TOF_SIN_ECO_MM = 8000;
const float SIN_ECO_CM = 999.0f;

// ---------------- Ultrasonidos HC-SR04 ----------------
const uint8_t US_TRIG[3] = {24, 26, 28};                 // validado (no los de tests-emi)
const uint8_t US_ECHO[3] = {25, 27, 29};
const unsigned long US_TIMEOUT_US = 15000UL;             // ~2,5 m, alcanza para el rango del servidor
// Calibración a mano (2026-11-21): real = A*lectura + B
const float LASER_B_CM[2] = {-2.5f, -4.0f};
const float US_A[3] = {1.04f, 1.04f, 1.04f};
const float US_B[3] = {0.25f, 0.77f, 0.57f};
const unsigned long US_ENTRE_DISPAROS_MS = 20;           // un sensor a la vez: no se pisan

// ---------------- Estómago ----------------
const uint8_t CAPTURAS_META = 3;
const uint8_t MUESTRAS_BASE = 10;
const uint16_t UMBRAL_MM = 10;
const uint8_t LECTURAS_CONFIRMAR = 4;
const unsigned long CAPTURA_ENFRIAMIENTO_MS = 3000;      // sin calibrar ni detectar tras una captura

// ---------------- Protocolo ----------------
const unsigned long TELEMETRIA_MS = 100;
const unsigned long FAILSAFE_MS = 500;
const unsigned long HEARTBEAT_REPOSO_MS = 250;

// ---------------- Estado ----------------
VL53L0X tof[3];
bool tofOk[3];
float usCm[3] = {SIN_ECO_CM, SIN_ECO_CM, SIN_ECO_CM};
float laserCm[2] = {SIN_ECO_CM, SIN_ECO_CM};
uint16_t estomagoMm = 0;
uint8_t usTurno = 0;
unsigned long usUltimo = 0, tofUltimo[3] = {0, 0, 0};

bool corrida = false;
char estado[16] = "BUSQUEDA";
unsigned long ultimoServidor = 0;
bool failSafe = false;

int8_t angulo = 0;
bool laserFijo = false;                       // [SOLO PRUEBAS / DEBUG] LASER:<grados|HOLD>
int8_t laserFijoAng = 0;
bool barridoEnReposo = false;                 // [SOLO PRUEBAS / DEBUG] permite barrido servo en !corrida
int8_t sentidoBarrido = 1;
unsigned long ultimoPasoBarrido = 0, ultimoMovServo = 0;
bool pcaOk = false;

uint8_t pickerEscala = 0;       // 0 = frenado, 1..PASOS = arrancando/girando
bool pickerQuiere = false;
unsigned long pickerApagarMs = 0, pickerUltimoPaso = 0, pickerInicioMs = 0;
bool pickerManual = false;      // [SOLO PRUEBAS / DEBUG] PICKER:ON del operador
bool pickerBloqueado = false;   // cortado por captura o por tope: no rearranca hasta salir de APROXIMACION/CAPTURA

enum Detector : uint8_t { DESARMADO, CALIBRANDO, ARMADO };
Detector detector = DESARMADO;
uint32_t baseAcum = 0;
uint8_t baseN = 0, cambiosSeguidos = 0;
uint16_t baseMm = 0;
uint8_t capturas = 0;
unsigned long ultimaCaptura = 0;   // 0 = ninguna en esta corrida

char rx[64];
uint8_t rxLen = 0;
unsigned long manualUltima = 0;
bool manualMoviendo = false;
unsigned long ultimaTelemetria = 0, ultimoHeartbeat = 0;

// ============================================================ tracción
uint8_t pwmDeVelocidad(float v) {
  float a = fabs(v);
  if (a < 0.05f) return 0;
  int rango = (int)pwmTope - (int)pwmZonaMuerta;
  if (rango < 0) rango = 0;
  int pwm = (int)pwmZonaMuerta + (int)((a / MAX_VEL_SERVIDOR) * rango);
  return (uint8_t)constrain(pwm, 0, 255);
}

void mover(uint8_t k, float v, uint8_t pwmFijo) {
  uint8_t pwm = pwmFijo ? pwmFijo : pwmDeVelocidad(v);
  if (pwm == 0) { motor[k]->run(RELEASE); return; }
  uint8_t sentido = (v > 0) ? FORWARD : BACKWARD;
  if (INVERTIDO[k]) sentido = (sentido == FORWARD) ? BACKWARD : FORWARD;
  motor[k]->setSpeed(pwm);
  motor[k]->run(sentido);
}

void traccion(float vL, float vR, uint8_t pwmFijo = 0) {
  for (uint8_t k = 0; k < 4; k++) mover(k, ES_IZQUIERDO[k] ? vL : vR, pwmFijo);
}
void frenarMotores() { for (uint8_t k = 0; k < 4; k++) motor[k]->run(RELEASE); manualMoviendo = false; }

// ============================================================ servos
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
// Si el PCA se reinició por caída de tensión arranca dormido: reconfigurar.
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
  if (si) pickerApagarMs = millis() + extraMs;   // al soltarlo sigue girando hasta este plazo
}
void detenerPicker() { pickerQuiere = false; pickerApagarMs = 0; pickerManual = false; }
void cortarPicker() { detenerPicker(); pickerBloqueado = true; }   // por captura o por tope de tiempo
// Arranque suave y apagado sin bloquear
void atenderPicker(unsigned long ahora) {
  bool debeGirar = pickerQuiere || (pickerApagarMs && (long)(ahora - pickerApagarMs) < 0);
  if (debeGirar && pickerEscala == 0) pickerInicioMs = ahora;                 // empieza un giro
  if (debeGirar && pickerEscala > 0 && ahora - pickerInicioMs >= PICKER_MAX_MS) {
    cortarPicker(); debeGirar = false;                                         // tope de 10 s
    ENLACE.println(F("ERROR:PICKER_TOPE_10S"));
  }
  if (debeGirar && pickerEscala < PICKER_PASOS_ARRANQUE && ahora - pickerUltimoPaso >= PICKER_MS_POR_PASO) {
    pickerEscala++; pickerUltimoPaso = ahora; if (pcaOk) aplicarPicker();
  } else if (!debeGirar && pickerEscala > 0) {
    pickerEscala = 0; pickerApagarMs = 0; if (pcaOk) aplicarPicker();
  }
}

bool estadoFinal() { return !strcmp(estado, "ESCAPE") || !strcmp(estado, "FINALIZACION"); }

void atenderBarrido(unsigned long ahora) {
  if (!pcaOk || (!corrida && !barridoEnReposo) || laserFijo || (!barridoEnReposo && estadoFinal()) || ahora - ultimoPasoBarrido < BARRIDO_PASO_MS) return;
  ultimoPasoBarrido = ahora;
  int8_t sig = angulo + sentidoBarrido * BARRIDO_PASO;
  if (sig > BARRIDO_MAX || sig < BARRIDO_MIN) { sentidoBarrido = -sentidoBarrido; sig = angulo + sentidoBarrido * BARRIDO_PASO; }
  posicionarLaseres(sig);
}

// ============================================================ sensores
float leerUsCm(uint8_t i) {
  digitalWrite(US_TRIG[i], LOW); delayMicroseconds(2);
  digitalWrite(US_TRIG[i], HIGH); delayMicroseconds(10);
  digitalWrite(US_TRIG[i], LOW);
  unsigned long us = pulseIn(US_ECHO[i], HIGH, US_TIMEOUT_US);
  if (!us) return SIN_ECO_CM;
  return max(0.0f, US_A[i] * (us * 0.0343f / 2.0f) + US_B[i]);
}

// Una sola tarea lenta por vuelta
void atenderSensores(unsigned long ahora) {
  if (ahora - usUltimo >= US_ENTRE_DISPAROS_MS) {
    usUltimo = ahora;
    usCm[usTurno] = leerUsCm(usTurno);
    usTurno = (usTurno + 1) % 3;
    return;
  }
  // Los láseres de barrido miden solo con el servo ya asentado
  for (uint8_t i = 0; i < 3; i++) {
    if (!tofOk[i] || ahora - tofUltimo[i] < (i == 2 ? TOF_ESTOMAGO_PERIODO_MS : TOF_PERIODO_MS)) continue;
    if (i < 2 && ahora - ultimoMovServo < LASER_ASENTAR_MS) continue;
    tofUltimo[i] = ahora;
    uint16_t mm = tof[i].readRangeContinuousMillimeters();
    bool ok = !tof[i].timeoutOccurred() && mm < TOF_SIN_ECO_MM;
    if (i < 2) laserCm[i] = ok ? max(0.0f, mm / 10.0f + LASER_B_CM[i]) : SIN_ECO_CM;
    else estomagoMm = ok ? mm : 0;
    return;
  }
}

// ============================================================ estómago
void armarEstomago() {
  if (!tofOk[2] || capturas >= CAPTURAS_META) { detector = DESARMADO; return; }
  baseAcum = 0; baseN = 0; cambiosSeguidos = 0; detector = CALIBRANDO;
}
void atenderEstomago() {
  static unsigned long ultimaMuestra = 0;
  if (detector == DESARMADO || estomagoMm == 0 || tofUltimo[2] == ultimaMuestra) return;
  if (ultimaCaptura && millis() - ultimaCaptura < CAPTURA_ENFRIAMIENTO_MS) return;   // el picker aún mueve la canica
  ultimaMuestra = tofUltimo[2];
  if (detector == CALIBRANDO) {
    baseAcum += estomagoMm;
    if (++baseN >= MUESTRAS_BASE) { baseMm = baseAcum / baseN; detector = ARMADO; }
    return;
  }
  uint16_t d = estomagoMm > baseMm ? estomagoMm - baseMm : baseMm - estomagoMm;
  cambiosSeguidos = (d >= UMBRAL_MM) ? cambiosSeguidos + 1 : 0;
  if (cambiosSeguidos >= LECTURAS_CONFIRMAR) {
    capturas++; ultimaCaptura = millis();
    ENLACE.print(F("EVENT:CAPTURA_LOBITO_")); ENLACE.println(capturas);
    cortarPicker();         // la canica ya está adentro: el picker deja de girar
    detector = DESARMADO;   // el servidor decide si hay que volver a armar
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
  corrida = true; failSafe = false; capturas = 0; ultimaCaptura = 0; pickerBloqueado = false;
  strcpy(estado, "BUSQUEDA");
  ultimoServidor = millis();
  sentidoBarrido = 1; posicionarLaseres(laserFijo ? laserFijoAng : BARRIDO_MIN);
  armarEstomago();
  enviarHello();
  for (uint8_t i = 0; i < 3; i++)             // el aviso de setup() sale antes de que conecte el Bluetooth
    if (!tofOk[i]) { ENLACE.print(F("ERROR:SENSOR_")); ENLACE.println(NOMBRE_TOF[i]); }
}
void terminarCorrida() {
  corrida = false; barridoEnReposo = false; frenarMotores(); detenerPicker(); detector = DESARMADO;
  strcpy(estado, "BUSQUEDA");
  if (pcaOk) posicionarLaseres(0);
}

void nuevoEstado(const char *e) {
  bool cambio = strcmp(estado, e) != 0;       // cada COMMAND (10/s) repite el estado: no recentrar en cada uno
  strncpy(estado, e, sizeof(estado) - 1); estado[sizeof(estado) - 1] = 0;
  if (estadoFinal()) {
    detector = DESARMADO; detenerPicker();
    if (cambio && pcaOk) posicionarLaseres(0);
    if (!strcmp(estado, "FINALIZACION")) frenarMotores();
    return;
  }
  if (detector == DESARMADO) armarEstomago();
  if (pickerManual) return;
  if (!strcmp(estado, "APROXIMACION") || !strcmp(estado, "CAPTURA")) pickerQuerer(true, PICKER_EXTRA_MS);
  else { pickerBloqueado = false; pickerQuerer(false); }   // salió del estado: se puede volver a usar
}

void procesarLinea(char *l) {
  if (!strcmp(l, "CMD:START")) { empezarCorrida(); return; }
  if (!strcmp(l, "CMD:STOP")) { terminarCorrida(); return; }
  if (!strcmp(l, "HEARTBEAT")) { ultimoServidor = millis(); failSafe = false; return; }
#if PERMITIR_MANUAL
  // [SOLO PRUEBAS / DEBUG - retirar en competencia]
  if (!strncmp(l, "CMD:PWM:", 8)) {
    char *c1 = strchr(l + 8, ',');
    int minP = constrain(atoi(l + 8), 0, 255);
    int maxP = c1 ? constrain(atoi(c1 + 1), minP, 255) : pwmTope;
    pwmZonaMuerta = (uint8_t)minP;
    pwmTope = (uint8_t)maxP;
    return;
  }
  if (!strncmp(l, "LASER:", 6)) {             // LASER:SWEEP retoma barrido (incluso en reposo); LASER:HOLD/<grados> lo congela
    if (!strcmp(l + 6, "SWEEP")) {
      laserFijo = false; barridoEnReposo = true; ultimoPasoBarrido = 0;
      if (!pcaOk) ENLACE.println(F("ERROR:PCA9685_NO_RESPONDE"));
    }
    else if (!strcmp(l + 6, "HOLD")) { laserFijo = true; laserFijoAng = angulo; barridoEnReposo = false; }
    else { laserFijo = true; barridoEnReposo = false; laserFijoAng = constrain(atoi(l + 6), -90, 90); if (pcaOk) posicionarLaseres(laserFijoAng); }
    return;
  }
  if (!strcmp(l, "PICKER:ON")) { pickerBloqueado = false; pickerManual = true; if (capturas >= CAPTURAS_META) capturas = 0; armarEstomago(); pickerQuerer(true); return; }
  if (!strcmp(l, "PICKER:OFF")) { detenerPicker(); return; }
#endif
  if (!corrida) return;                       // en reposo se ignora todo lo demás
  if (!strncmp(l, "COMMAND:", 8)) {
    char *c1 = strchr(l + 8, ',');
    if (!c1) return;                          // trama corrupta: se descarta
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

void manual(char c) {
#if PERMITIR_MANUAL
  if (c == 'k') { empezarCorrida(); return; }
  if (c == 'x') { terminarCorrida(); return; }
  if (corrida) return;
  switch (c) {
    case 'w': traccion(1, 1, PWM_MANUAL); break;
    case 's': traccion(-1, -1, PWM_MANUAL); break;
    case 'a': traccion(-1, 1, PWM_MANUAL); break;
    case 'd': traccion(1, -1, PWM_MANUAL); break;
    case ' ': frenarMotores(); return;
    default: return;
  }
  manualMoviendo = true; manualUltima = millis();
#endif
}

void atenderEnlace() {
  while (ENLACE.available()) {
    char c = ENLACE.read();
    if (c == '\n' || c == '\r') {
      if (rxLen) { rx[rxLen] = 0; procesarLinea(rx); rxLen = 0; }
    } else if (rxLen == 0 && strchr("wasdkx ", c)) {
      manual(c);                                // tecla suelta, sin salto de línea
    } else if (rxLen < sizeof(rx) - 1) {
      rx[rxLen++] = c;
    } else {
      rxLen = 0;                                // ruido: descarte defensivo
    }
  }
  // millis() fresco: procesarLinea() puede haber guardado una marca más nueva que la del inicio del loop
  unsigned long ahora = millis();
  if (manualMoviendo && ahora - manualUltima > MANUAL_HOLD_MS) frenarMotores();
  if (corrida && !failSafe && ahora - ultimoServidor > FAILSAFE_MS) {
    failSafe = true;
    frenarMotores(); detenerPicker();
    ENLACE.println(F("ERROR:FAILSAFE_TIMEOUT"));
  }
}

void imprimirTelemetria(Print &p, unsigned long ahora) {
  p.print(F("TELEMETRY:"));
  for (uint8_t i = 0; i < 3; i++) { p.print(usCm[i], 1); p.print(','); }
  p.print(laserCm[0], 1); p.print(',');
  p.print(laserCm[1], 1); p.print(',');
  p.print(angulo); p.print(',');
  p.println(ahora);
}

void enviarTelemetria(unsigned long ahora) {
  // Una sola trama por vuelta del loop() para no saturar el buffer TX de 64B del Mega @ 9600 bps.
  if (ahora - ultimaTelemetria >= TELEMETRIA_MS) {
    if (ENLACE.availableForWrite() < 56) return;
    ultimaTelemetria = ahora;
    imprimirTelemetria(ENLACE, ahora);
#if ESPEJAR_USB
    imprimirTelemetria(Serial, ahora);          // para comparar con lo que llega por Bluetooth
#endif
    return;
  }
  if (!corrida && ahora - ultimoHeartbeat >= HEARTBEAT_REPOSO_MS) {
    if (ENLACE.availableForWrite() < 14) return;
    ultimoHeartbeat = ahora;
    ENLACE.println(F("HEARTBEAT"));
    return;
  }
#if DEPURAR_ESTOMAGO
  // [SOLO PRUEBAS / DEBUG] Envia lectura del ToF de estomago en paralelo sin pisar TELEMETRY
  static unsigned long ultimoDbg = 0;
  if (ahora - ultimoDbg >= 200 && ENLACE.availableForWrite() >= 48) {
    ultimoDbg = ahora;
    ENLACE.print(F("DBG:estomago=")); ENLACE.print(estomagoMm);
    ENLACE.print(F(" base=")); ENLACE.print(baseMm);
    ENLACE.print(F(" det=")); ENLACE.print((int)detector);
    ENLACE.print(F(" cambios=")); ENLACE.println(cambiosSeguidos);
  }
#endif
}

// ============================================================ setup / loop
void setup() {
  ENLACE.begin(BAUD_ENLACE);
  Serial.begin(115200);
  Wire.begin();
  configurarPca();
  Wire.beginTransmission(DIR_PCA);
  pcaOk = (Wire.endTransmission() == 0);
  if (!pcaOk) ENLACE.println(F("ERROR:PCA9685_NO_RESPONDE"));

  for (uint8_t i = 0; i < 3; i++) { pinMode(US_TRIG[i], OUTPUT); pinMode(US_ECHO[i], INPUT); }
  for (uint8_t i = 0; i < 3; i++) { pinMode(XSHUT[i], OUTPUT); digitalWrite(XSHUT[i], LOW); }
  delay(20);
  for (uint8_t i = 0; i < 3; i++) {
    pinMode(XSHUT[i], INPUT); delay(20);              // el módulo sube XSHUT solo
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
  Serial.println(F("Orquita lista. Reposo: CMD:START o 'k'."));
}

void loop() {
  // Cada etapa lee millis() de nuevo: si no, una marca guardada dentro de una etapa
  // queda "en el futuro" para las siguientes y la resta sin signo da un valor enorme.
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
