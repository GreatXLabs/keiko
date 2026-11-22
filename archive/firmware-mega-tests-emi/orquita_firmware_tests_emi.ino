/*
 * ============================================================================
 * FIRMWARE CLIENTE HARDWARE REAL - PROYECTO ORQUITAS (KEIKO)
 * Plataforma: Arduino Mega 2560 + Motor Shield L293D + PCA9685 + Bluetooth HC-05
 * ============================================================================
 *
 * Complementa de punta a punta a keiko-server (Neuroph 2.98 dual: 3US + 2Laser):
 *  - Traccion 4WD Skid-Steer: Shield L293D (libreria AFMotor, M1..M4).
 *  - Fila superior: 3 ultrasonidos fijos HC-SR04 (Izquierda, Centro, Derecha).
 *  - Fila inferior: 2 laseres ToF VL53L0X (0x30, 0x31) barriendo en espejo sobre
 *    2 servos SG90 conectados al PCA9685 (canales 0 y 1).
 *  - Picker recolector: 2 servos SG90 modificados a 360° en PCA9685 (canales 2 y 3)
 *    con rampa de arranque suave no bloqueante y recuperacion automatica (revisarPca).
 *  - Conteo de canicas en el estomago: 3er laser VL53L0X (0x32, XSHUT A10) con
 *    detector de "captura armada" y envio de EVENT:CAPTURA_LOBITO_<n> al Servidor.
 *  - Enlace Bluetooth SPP (HC-05 en Serial1 D18/D19 a 9600 bps) + Monitor USB
 *    libre en Serial (115200 bps) para diagnostico y pruebas de banco.
 *  - Fail-Safe reglamentario estricto (< 500 ms sin COMMAND ni HEARTBEAT -> frena todo).
 *
 * Librerias requeridas en Arduino IDE:
 *  - "Adafruit Motor Shield library" (v1, AFMotor.h)
 *  - "Adafruit PWM Servo Driver Library" (Adafruit_PWMServoDriver.h)
 *  - "VL53L0X" de Pololu (VL53L0X.h)
 *
 * Comandos rapidos por Monitor Serie USB (115200 bps) para pruebas en banco:
 *  - 'a' : armar manualmente el sensor del estomago (aprende linea base actual)
 *  - 'd' : desarmar el sensor del estomago
 *  - 'c' : simular envio de EVENT:CAPTURA al servidor (probar estomago.txt sin canica)
 *  - 'r' : reiniciar a 0 el contador local de canicas recolectadas
 *  - 'g' : encender manualmente el picker
 *  - 'p' : frenar manualmente el picker y la traccion
 *  - 'v' : activar/desactivar impresion continua de lecturas en el Monitor USB
 */

#include <Wire.h>
#include <AFMotor.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

// ============================================================================
// 1. CONFIGURACION RAPIDA DE HARDWARE Y PINES (EDITABLE POR EL EQUIPO)
// ============================================================================

#define KEIKO_VERSION "2.0.0-audit.1"
#define KEIKO_RIG     "mega-3us-2laser"

// Si se cambia a true, usa el cable USB (Serial) en lugar del HC-05 (Serial1)
// para hablar con keiko-server (ej: java -jar keiko-server.jar --serial /dev/ttyUSB0 --baud 115200)
#define USAR_USB_COMO_ENLACE_SERVIDOR false

const unsigned long USB_BAUDIOS = 115200;
const unsigned long BT_BAUDIOS  = 9600;   // HC-05 keiko-greatx configurado en 9600,0,0

#if USAR_USB_COMO_ENLACE_SERVIDOR
  #define ENLACE_SERVIDOR Serial
  #define DEBUG_HABILITADO false
#else
  #define ENLACE_SERVIDOR Serial1
  #define DEBUG_HABILITADO true
#endif

// --- Ultrasonidos superiores HC-SR04 (pines libres del Mega fuera del shield L293D) ---
// Orden obligatorio para el servidor: [0]=IZQUIERDA, [1]=CENTRO, [2]=DERECHA
// Cableado fisico verificado en el robot:
//   Izquierda: TRIG=24, ECHO=25 | Centro: TRIG=27, ECHO=26 | Derecha: TRIG=29, ECHO=28
const uint8_t NUM_US = 3;
const uint8_t US_TRIG[NUM_US] = {24, 27, 29};  // Invertidos medio y derecho
const uint8_t US_ECHO[NUM_US] = {25, 26, 28};
const unsigned long US_TIMEOUT_US = 25000UL; // Igual que pruebas/detector-mixto
const float US_MIN_CM = 2.0f;
const float US_MAX_CM = 250.0f;

// --- Bus I2C (Mega: D20=SDA, D21=SCL) y pines XSHUT de los 3 VL53L0X ---
const uint8_t NUM_TOF_FRONTALES = 2; // [0]=Izquierda, [1]=Derecha
const uint8_t PIN_XSHUT_FRONTAL[NUM_TOF_FRONTALES] = {A8, A9};
const uint8_t DIR_TOF_FRONTAL[NUM_TOF_FRONTALES]   = {0x30, 0x31};

const uint8_t PIN_XSHUT_ESTOMAGO = A10;
const uint8_t DIR_TOF_ESTOMAGO   = 0x32;
const float TOF_SIN_OBJETO_CM    = 999.0f; // Valor sin objeto/timeout (igual que detector-mixto y Red 2)

// --- PCA9685 (0x40): Servos de barrido laser (ch 0, 1) y Picker 360° (ch 2, 3) ---
const uint8_t DIR_PCA   = 0x40;
const uint8_t PCA_MODE1 = 0x00;
const uint8_t PCA_SLEEP = 0x10;

// Servos SG90 de los laseres frontales (parametros exactos de pruebas/detector-mixto)
const uint8_t SERVO_LASER_CANAL[NUM_TOF_FRONTALES]   = {0, 1};
const uint8_t SERVO_LASER_CENTRO[NUM_TOF_FRONTALES]  = {90, 90};
const int8_t  SERVO_LASER_SENTIDO[NUM_TOF_FRONTALES] = {-1, -1};
const uint16_t SERVO_US_0   = 500;
const uint16_t SERVO_US_180 = 2400;

const int8_t  BARRIDO_ANG_MIN  = -45; // Hacia el centro (fiel a pruebas/detector-mixto)
const int8_t  BARRIDO_ANG_MAX  = 60;  // Hacia afuera    (fiel a pruebas/detector-mixto)
const int8_t  BARRIDO_ANG_PASO = 5;
const unsigned long INTERVALO_PASO_SERVO_MS = 40; // PAUSA_SERVO_MS de pruebas/detector-mixto

// Servos SG90 360° del picker (calibrados en pruebas/picker-estomago)
const uint8_t  NUM_SERVOS_PICKER = 2;
const uint8_t  PICKER_CANAL[NUM_SERVOS_PICKER]   = {2, 3};
const uint16_t PICKER_NEUTRO[NUM_SERVOS_PICKER]  = {1475, 1500};
const int8_t   PICKER_SENTIDO[NUM_SERVOS_PICKER] = {1, -1};
const uint16_t PICKER_VEL_US[NUM_SERVOS_PICKER]  = {240, 200};
const uint8_t  PICKER_PASOS_ARRANQUE = 10;
const unsigned long PICKER_MS_POR_PASO = 50;
const unsigned long TIEMPO_EXTRA_PICKER_MS = 4000; // Mantiene giro tras APROXIMACION para terminar de tragar

// Motor DC auxiliar del picker con L298N (pines verificados en pruebas/prueba-picker)
const uint8_t PICKER_DC_ENA = 45;
const uint8_t PICKER_DC_IN1 = 22;
const uint8_t PICKER_DC_IN2 = 23;

// --- Traccion 4WD Shield L293D (calibrado en pruebas/prueba-traccion) ---
const uint8_t NUM_MOTORES = 4;
const bool MOTOR_ES_IZQUIERDO[NUM_MOTORES] = {true, true, false, false}; // M1,M2 izq | M3,M4 der
const bool MOTOR_INVERTIDO[NUM_MOTORES]    = {true, true, true, true};
bool invertirMotoresManual = false;
const float MAX_VEL_SERVIDOR = 8.0f;
const uint8_t PWM_DEADBAND   = 105; // Subido de 70 a 105 para compensar caida en celdas 7.4V recicladas + L293D

// --- Detector de canicas en el estomago ("Captura Armada" sobre VL53L0X 0x32) ---
const uint8_t  CAPTURAS_META_ESCAPE = 3;
const uint8_t  MUESTRAS_BASE_ESTOMAGO = 10; // Igual que pruebas/picker-estomago
const uint16_t UMBRAL_CAMBIO_ESTOMAGO_MM = 10; // Ruido normal ~7 mm; canica cambia >= 15 mm
const uint8_t  LECTURAS_CONFIRMAR_CAPTURA = 2;
const uint16_t LECTURA_INVALIDA_MM = 8000;
const unsigned long COOLDOWN_REARMADO_ESTOMAGO_MS = 2200; // Espera asentamiento tras tragar canica

// --- Tiempos operativos y Fail-Safe ---
const unsigned long FAILSAFE_TIMEOUT_MS     = 500; // Reglamentario estricto (< 500 ms)
const unsigned long INTERVALO_TELEMETRIA_MS = 100; // 10 Hz (~480 B/s, seguro a 9600 bps)
const unsigned long INTERVALO_ESTOMAGO_MS   = 25;  // Muestreo continuo 40 Hz del estomago
const unsigned long INTERVALO_CHEQUEO_PCA_MS = 500;
const uint8_t PIN_LED_ESTADO = LED_BUILTIN;

// ============================================================================
// 2. OBJETOS DE HARDWARE Y ESTADO GLOBAL
// ============================================================================

AF_DCMotor m1(1), m2(2), m3(3), m4(4);
AF_DCMotor *motores[NUM_MOTORES] = {&m1, &m2, &m3, &m4};

Adafruit_PWMServoDriver pca = Adafruit_PWMServoDriver(DIR_PCA);
VL53L0X tofFrontal[NUM_TOF_FRONTALES];
VL53L0X tofEstomago;

bool pcaDisponible = false;
bool tofFrontalOk[NUM_TOF_FRONTALES] = {false, false};
bool tofEstomagoOk = false;

// Filtro mediana(3) + EMA para cada ultrasonido
struct FiltroUltrasonico {
  float ventana[3];
  uint8_t idx;
  bool iniciado;
  float ema;

  void init() {
    idx = 0;
    iniciado = false;
    ema = US_MAX_CM;
    ventana[0] = ventana[1] = ventana[2] = US_MAX_CM;
  }

  float actualizar(float crudo) {
    crudo = constrain(crudo, US_MIN_CM, US_MAX_CM);
    if (!iniciado) {
      ventana[0] = ventana[1] = ventana[2] = crudo;
      ema = crudo;
      iniciado = true;
      return crudo;
    }
    ventana[idx] = crudo;
    idx = (idx + 1) % 3;
    float a = ventana[0], b = ventana[1], c = ventana[2];
    float mediana = (a > b) ? ((b > c) ? b : ((a > c) ? c : a))
                            : ((a > c) ? a : ((b > c) ? c : b));
    ema = 0.65f * mediana + 0.35f * ema;
    return ema;
  }
};

FiltroUltrasonico filtroUs[NUM_US];
float ultimaDistUsCm[NUM_US] = {US_MAX_CM, US_MAX_CM, US_MAX_CM};
float ultimaDistLaserCm[NUM_TOF_FRONTALES] = {TOF_SIN_OBJETO_CM, TOF_SIN_OBJETO_CM};

// Estado de barrido de servos frontales
int8_t anguloServoActual = BARRIDO_ANG_MIN;
int8_t direccionBarrido  = 1;
unsigned long ultimoPasoServoMs = 0;

// Estado no bloqueante del picker
bool pickerObjetivoEncendido = false;
bool pickerModoManual        = false;
uint8_t pickerEscalaActual   = 0;
unsigned long ultimoPasoPickerMs = 0;
unsigned long pickerApagarEnMs   = 0;

// Estado del detector de canicas del estomago
enum EstadoDetectorEstomago : uint8_t {
  ESTOMAGO_DESARMADO,
  ESTOMAGO_CALIBRANDO_BASE,
  ESTOMAGO_ARMADO,
  ESTOMAGO_COOLDOWN_POST_CAPTURA
};

EstadoDetectorEstomago estadoEstomago = ESTOMAGO_DESARMADO;
uint32_t acumuladorBaseMm = 0;
uint8_t  muestrasBaseTomadas = 0;
uint8_t  intentosBaseTotales = 0;
uint16_t baseEstomagoMm = 55;
uint8_t  lecturasCambioSeguidas = 0;
uint8_t  canicasRecolectadas = 0;
uint16_t ultimaLecturaEstomagoMm = 0;
unsigned long inicioCooldownEstomagoMs = 0;

// Estado de enlace y FSM del servidor
unsigned long ultimoMensajeServidorMs = 0;
unsigned long ultimaTelemetriaMs = 0;
unsigned long ultimoMuestreoEstomagoMs = 0;
unsigned long ultimoChequeoPcaMs = 0;
bool modoCompetencia = false;    // Arranca 100% QUIETO. Con 'k' (o CMD:TOGGLE) prende/apaga Modo Competencia
bool failSafeActivo = true;
bool verboseUsb = true;
bool usarSensoresReales = true;  // Arranca directamente con SENSORES REALES ('s' reales, '1'..'6' simulados)
bool traccionHabilitada = false; // Se activa automaticamente al encender modoCompetencia (o con 'm')
bool barridoHabilitado  = false; // Se activa automaticamente al encender modoCompetencia (o con 'b')
float vectorSimulado[6] = {90.0f, 95.0f, 85.0f, 39.5f, 39.8f, 0.0f};
const char *nombreModoActual = "SENSORES_REALES";
float ultimaVelIzq = 0.0f;
float ultimaVelDer = 0.0f;
String estadoServidor = "BUSQUEDA";
String estadoServidorPrevio = "BUSQUEDA";
char bufferRxServidor[96];
uint8_t largoRxServidor = 0;

// ============================================================================
// 3. UTILIDADES DE LOG POR USB
// ============================================================================

void logInfo(const __FlashStringHelper *msg) {
#if DEBUG_HABILITADO
  Serial.println(msg);
#else
  (void)msg;
#endif
}

void enviarHello() {
  ENLACE_SERVIDOR.print(F("HELLO:"));
  ENLACE_SERVIDOR.print(F(KEIKO_VERSION));
  ENLACE_SERVIDOR.print(F(","));
  ENLACE_SERVIDOR.println(F(KEIKO_RIG));
}

// ============================================================================
// 4. TRACCION 4WD (SHIELD L293D) Y FAIL-SAFE
// ============================================================================

uint8_t calcularPwm(float vel) {
  float vAbs = fabs(vel);
  if (vAbs < 0.05f) return 0;
  int pwm = PWM_DEADBAND + (int)((vAbs / MAX_VEL_SERVIDOR) * (255 - PWM_DEADBAND));
  return (uint8_t)constrain(pwm, 0, 255);
}

void comandarMotorIndividual(uint8_t k, float velocidadFlanco) {
  uint8_t pwm = calcularPwm(velocidadFlanco);
  if (pwm == 0) {
    motores[k]->setSpeed(0);
    motores[k]->run(RELEASE);
    return;
  }
  uint8_t sentido = (velocidadFlanco > 0.0f) ? FORWARD : BACKWARD;
  bool invertidoFinal = MOTOR_INVERTIDO[k] ^ invertirMotoresManual;
  if (invertidoFinal) {
    sentido = (sentido == FORWARD) ? BACKWARD : FORWARD;
  }
  motores[k]->setSpeed(pwm);
  motores[k]->run(sentido);
}

void frenarTraccion() {
  for (uint8_t k = 0; k < NUM_MOTORES; k++) {
    motores[k]->setSpeed(0);
    motores[k]->run(RELEASE);
  }
}

void aplicarTraccion(float vLeft, float vRight) {
  if (!modoCompetencia || !traccionHabilitada) {
    frenarTraccion();
    return;
  }
  if (vLeft < -0.1f && vRight < -0.1f) {
#if DEBUG_HABILITADO
    Serial.println(F("de reversa mami"));
#endif
  }
  for (uint8_t k = 0; k < NUM_MOTORES; k++) {
    comandarMotorIndividual(k, MOTOR_ES_IZQUIERDO[k] ? vLeft : vRight);
  }
}

// ============================================================================
// 5. PCA9685: SERVOS FRONTALES Y PICKER 360° NO BLOQUEANTE
// ============================================================================

void configurarPca() {
  Wire.beginTransmission(DIR_PCA);
  if (Wire.endTransmission() != 0) {
    pcaDisponible = false;
    return;
  }
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
  pcaDisponible = true;
}

void soltarTodosLosServosPca() {
  if (!pcaDisponible) return;
  for (uint8_t ch = 0; ch < 16; ch++) {
    pca.setPWM(ch, 0, 0);
  }
}

void posicionarServosLaser(int8_t anguloGrados) {
  if (!pcaDisponible) return;
  anguloGrados = constrain(anguloGrados, BARRIDO_ANG_MIN, BARRIDO_ANG_MAX);
  for (uint8_t k = 0; k < NUM_TOF_FRONTALES; k++) {
    int16_t servoDeg = constrain(SERVO_LASER_CENTRO[k] + SERVO_LASER_SENTIDO[k] * anguloGrados, 0, 180);
    uint16_t pulsoUs = (uint16_t)map(servoDeg, 0, 180, SERVO_US_0, SERVO_US_180);
    pca.writeMicroseconds(SERVO_LASER_CANAL[k], pulsoUs);
  }
}

void aplicarEscalaPicker(uint8_t escala) {
  // 1. Control del motor DC del picker (L298N: ENA=45, IN1=22, IN2=23)
  if (escala == 0) {
    analogWrite(PICKER_DC_ENA, 0);
    digitalWrite(PICKER_DC_IN1, LOW);
    digitalWrite(PICKER_DC_IN2, LOW);
  } else {
    digitalWrite(PICKER_DC_IN1, HIGH);
    digitalWrite(PICKER_DC_IN2, LOW);
    int pwm = map(escala, 1, PICKER_PASOS_ARRANQUE, 120, 255);
    analogWrite(PICKER_DC_ENA, pwm);
  }

  // 2. Control de los servos 360° en PCA9685 (canales 2 y 3)
  if (!pcaDisponible) return;
  if (escala == 0) {
    for (uint8_t k = 0; k < NUM_SERVOS_PICKER; k++) {
      pca.setPWM(PICKER_CANAL[k], 0, 0);
    }
    return;
  }
  for (uint8_t k = 0; k < NUM_SERVOS_PICKER; k++) {
    int16_t deltaUs = (int32_t)PICKER_VEL_US[k] * escala / PICKER_PASOS_ARRANQUE;
    pca.writeMicroseconds(PICKER_CANAL[k], PICKER_NEUTRO[k] - PICKER_SENTIDO[k] * deltaUs);
  }
}

void encenderPickerSuave() {
  pickerObjetivoEncendido = true;
}

void frenarPickerInmediato() {
  pickerObjetivoEncendido = false;
  pickerEscalaActual = 0;
  aplicarEscalaPicker(0);
}

void actualizarRampaPicker(unsigned long ahora) {
  if (pickerObjetivoEncendido && !pickerModoManual && estadoServidor == "BUSQUEDA"
      && pickerApagarEnMs != 0 && (long)(ahora - pickerApagarEnMs) >= 0) {
    pickerApagarEnMs = 0;
    frenarPickerInmediato();
    return;
  }
  if (!pickerObjetivoEncendido) {
    if (pickerEscalaActual != 0) {
      frenarPickerInmediato();
    }
    return;
  }
  if (pickerEscalaActual < PICKER_PASOS_ARRANQUE && (ahora - ultimoPasoPickerMs) >= PICKER_MS_POR_PASO) {
    ultimoPasoPickerMs = ahora;
    pickerEscalaActual++;
    aplicarEscalaPicker(pickerEscalaActual);
  }
}

// Reconfigura el PCA9685 si sufrio un micro-corte de VCC y quedo en modo SLEEP
void revisarPca() {
  Wire.beginTransmission(DIR_PCA);
  Wire.write(PCA_MODE1);
  if (Wire.endTransmission() != 0) {
    pcaDisponible = false;
    return;
  }
  Wire.requestFrom(DIR_PCA, (uint8_t)1);
  if (!Wire.available()) return;
  uint8_t m = Wire.read();
  if (!pcaDisponible || (m & PCA_SLEEP)) {
    configurarPca();
    if (modoCompetencia || barridoHabilitado) {
      posicionarServosLaser(anguloServoActual);
      aplicarEscalaPicker(pickerEscalaActual);
    } else {
      soltarTodosLosServosPca();
    }
#if DEBUG_HABILITADO
    Serial.println(F("[PCA9685] Salidas reconfiguradas y restauradas."));
#endif
  }
}

// ============================================================================
// 6. INICIALIZACION Y LECTURA DE SENSORES (3 US + 3 VL53L0X CON XSHUT)
// ============================================================================

void inicializarSensoresI2c() {
  // Apagar los 3 VL53L0X para que no colisionen en la direccion 0x29 de fabrica
  for (uint8_t k = 0; k < NUM_TOF_FRONTALES; k++) {
    pinMode(PIN_XSHUT_FRONTAL[k], OUTPUT);
    digitalWrite(PIN_XSHUT_FRONTAL[k], LOW);
  }
  pinMode(PIN_XSHUT_ESTOMAGO, OUTPUT);
  digitalWrite(PIN_XSHUT_ESTOMAGO, LOW);
  delay(15);

  // 1) Encender y direccionar ToF frontales (0x30 izq, 0x31 der)
  for (uint8_t k = 0; k < NUM_TOF_FRONTALES; k++) {
    pinMode(PIN_XSHUT_FRONTAL[k], INPUT); // Pull-up del modulo a 2.8/3.3V
    delay(40);
    tofFrontal[k].setTimeout(60);
    if (tofFrontal[k].init()) {
      tofFrontal[k].setAddress(DIR_TOF_FRONTAL[k]);
      tofFrontal[k].setSignalRateLimit(0.1f);
      tofFrontal[k].setVcselPulsePeriod(VL53L0X::VcselPeriodPreRange, 18);
      tofFrontal[k].setVcselPulsePeriod(VL53L0X::VcselPeriodFinalRange, 14);
      tofFrontal[k].setMeasurementTimingBudget(33000);
      tofFrontal[k].startContinuous();
      tofFrontalOk[k] = true;
#if DEBUG_HABILITADO
      Serial.print(F("[I2C] VL53L0X frontal #"));
      Serial.print(k);
      Serial.print(F(" OK en 0x"));
      Serial.println(DIR_TOF_FRONTAL[k], HEX);
#endif
    } else {
      tofFrontalOk[k] = false;
#if DEBUG_HABILITADO
      Serial.print(F("[WARN] No responde VL53L0X frontal #"));
      Serial.println(k);
#endif
    }
  }

  // 2) Encender y direccionar VL53L0X del estomago (0x32, modo continuo rapido 20 ms)
  pinMode(PIN_XSHUT_ESTOMAGO, INPUT);
  delay(40);
  tofEstomago.setTimeout(50);
  if (tofEstomago.init()) {
    tofEstomago.setAddress(DIR_TOF_ESTOMAGO);
    tofEstomago.setMeasurementTimingBudget(20000);
    tofEstomago.startContinuous();
    tofEstomagoOk = true;
    logInfo(F("[I2C] VL53L0X del estomago OK en 0x32"));
  } else {
    tofEstomagoOk = false;
    logInfo(F("[WARN] No responde VL53L0X del estomago (revisar XSHUT A10 / I2C)"));
  }
}

float leerUltrasonicoCm(uint8_t idx) {
  digitalWrite(US_TRIG[idx], LOW);
  delayMicroseconds(2);
  digitalWrite(US_TRIG[idx], HIGH);
  delayMicroseconds(10);
  digitalWrite(US_TRIG[idx], LOW);

  unsigned long duracion = pulseIn(US_ECHO[idx], HIGH, US_TIMEOUT_US);
  if (duracion == 0) {
    return US_MAX_CM;
  }
  float cm = (duracion * 0.0343f) * 0.5f;
  return constrain(cm, US_MIN_CM, US_MAX_CM);
}

float leerTofFrontalCm(uint8_t k) {
  if (!tofFrontalOk[k]) return TOF_SIN_OBJETO_CM;
  uint16_t mm = tofFrontal[k].readRangeContinuousMillimeters();
  if (tofFrontal[k].timeoutOccurred() || mm >= LECTURA_INVALIDA_MM) {
    return TOF_SIN_OBJETO_CM;
  }
  float cm = mm * 0.1f;
  return constrain(cm, 2.0f, TOF_SIN_OBJETO_CM);
}

// Retorna 0 si hubo oclusion total o lectura fuera de rango
uint16_t leerEstomagoMm() {
  if (!tofEstomagoOk) return 0;
  uint16_t mm = tofEstomago.readRangeContinuousMillimeters();
  if (tofEstomago.timeoutOccurred() || mm >= LECTURA_INVALIDA_MM) {
    return 0;
  }
  return mm;
}

// ============================================================================
// 7. DETECTOR Y CONTADOR DE CANICAS EN EL ESTOMAGO (CAPTURA ARMADA)
// ============================================================================

void solicitarArmadoEstomago() {
  if (!tofEstomagoOk || canicasRecolectadas >= CAPTURAS_META_ESCAPE || estadoServidor == "ESCAPE") {
    estadoEstomago = ESTOMAGO_DESARMADO;
    return;
  }
  acumuladorBaseMm = 0;
  muestrasBaseTomadas = 0;
  intentosBaseTotales = 0;
  lecturasCambioSeguidas = 0;
  estadoEstomago = ESTOMAGO_CALIBRANDO_BASE;
}

void desarmarEstomago() {
  estadoEstomago = ESTOMAGO_DESARMADO;
  lecturasCambioSeguidas = 0;
}

void notificarCapturaAlServidor(uint16_t lecturaMm) {
  canicasRecolectadas++;

  // Trama oficial reconocida por ManejadorCliente -> GestorEstomago -> estomago.txt
  ENLACE_SERVIDOR.print(F("EVENT:CAPTURA_LOBITO_"));
  ENLACE_SERVIDOR.println(canicasRecolectadas);

#if DEBUG_HABILITADO
  Serial.print(F("[ESTOMAGO] Canica recolectada #"));
  Serial.print(canicasRecolectadas);
  Serial.print(F("/"));
  Serial.print(CAPTURAS_META_ESCAPE);
  Serial.print(F(" | mm="));
  Serial.print(lecturaMm);
  Serial.print(F(" (base="));
  Serial.print(baseEstomagoMm);
  Serial.println(F(" mm) -> EVENT enviado al servidor"));
#endif

  if (canicasRecolectadas >= CAPTURAS_META_ESCAPE) {
    estadoEstomago = ESTOMAGO_DESARMADO;
  } else {
    inicioCooldownEstomagoMs = millis();
    estadoEstomago = ESTOMAGO_COOLDOWN_POST_CAPTURA;
  }
}

void actualizarDetectorEstomago(unsigned long ahora) {
  if (!tofEstomagoOk) return;
  if ((ahora - ultimoMuestreoEstomagoMs) < INTERVALO_ESTOMAGO_MS) return;
  ultimoMuestreoEstomagoMs = ahora;

  uint16_t mm = leerEstomagoMm();
  ultimaLecturaEstomagoMm = mm;

  switch (estadoEstomago) {
    case ESTOMAGO_DESARMADO:
      break;

    case ESTOMAGO_CALIBRANDO_BASE:
      intentosBaseTotales++;
      if (mm > 0) {
        acumuladorBaseMm += mm;
        muestrasBaseTomadas++;
      }
      if (muestrasBaseTomadas >= MUESTRAS_BASE_ESTOMAGO) {
        baseEstomagoMm = (uint16_t)(acumuladorBaseMm / muestrasBaseTomadas);
        lecturasCambioSeguidas = 0;
        estadoEstomago = ESTOMAGO_ARMADO;
#if DEBUG_HABILITADO
        Serial.print(F("[ESTOMAGO] Detector armado. Linea base = "));
        Serial.print(baseEstomagoMm);
        Serial.println(F(" mm"));
#endif
      } else if (intentosBaseTotales >= MUESTRAS_BASE_ESTOMAGO * 3) {
        // Reintentar tras breve pausa si el laser aun no estabilizo
        inicioCooldownEstomagoMs = ahora;
        estadoEstomago = ESTOMAGO_COOLDOWN_POST_CAPTURA;
      }
      break;

    case ESTOMAGO_ARMADO: {
      uint16_t delta = (mm == 0)
          ? UMBRAL_CAMBIO_ESTOMAGO_MM
          : ((mm > baseEstomagoMm) ? (mm - baseEstomagoMm) : (baseEstomagoMm - mm));

      if (delta >= UMBRAL_CAMBIO_ESTOMAGO_MM) {
        lecturasCambioSeguidas++;
        if (lecturasCambioSeguidas >= LECTURAS_CONFIRMAR_CAPTURA) {
          notificarCapturaAlServidor(mm);
        }
      } else {
        lecturasCambioSeguidas = 0;
      }
      break;
    }

    case ESTOMAGO_COOLDOWN_POST_CAPTURA:
      if ((ahora - inicioCooldownEstomagoMs) >= COOLDOWN_REARMADO_ESTOMAGO_MS) {
        if (!failSafeActivo && canicasRecolectadas < CAPTURAS_META_ESCAPE && estadoServidor != "ESCAPE") {
          solicitarArmadoEstomago();
        } else {
          estadoEstomago = ESTOMAGO_DESARMADO;
        }
      }
      break;
  }
}

// ============================================================================
// 8. PROTOCOLO DE RED CON KEIKO-SERVER (RFCOMM / SPP)
// ============================================================================

void actualizarEstadoOperativo(const String &nuevoEstado) {
  if (!modoCompetencia) {
    return;
  }
  estadoServidor = nuevoEstado;

  if (estadoServidor == "ESCAPE" || estadoServidor == "FINALIZACION") {
    desarmarEstomago();
    pickerModoManual = false;
    pickerApagarEnMs = 0;
    frenarPickerInmediato();
    anguloServoActual = 0;
    posicionarServosLaser(0);
    if (estadoServidor == "FINALIZACION") {
      frenarTraccion();
    }
  } else {
    // Armar el estomago si estaba desarmado
    if (estadoEstomago == ESTOMAGO_DESARMADO && canicasRecolectadas < CAPTURAS_META_ESCAPE) {
      solicitarArmadoEstomago();
    } else if (estadoServidor == "APROXIMACION" && estadoServidorPrevio != "APROXIMACION"
               && estadoEstomago == ESTOMAGO_ARMADO) {
      // Refrescar linea base justo al iniciar encarado hacia una canica nueva
      solicitarArmadoEstomago();
    }

    // El picker solo gira cuando el servidor ordena APROXIMACION / CAPTURA
    // (mas TIEMPO_EXTRA_PICKER_MS para terminar de tragar la canica)
    if (estadoServidor == "APROXIMACION" || estadoServidor == "CAPTURA") {
      pickerApagarEnMs = millis() + TIEMPO_EXTRA_PICKER_MS;
      encenderPickerSuave();
    } else if (!pickerModoManual) {
      if (pickerApagarEnMs == 0 || (long)(millis() - pickerApagarEnMs) >= 0) {
        pickerApagarEnMs = 0;
        frenarPickerInmediato();
      }
    }
  }

  estadoServidorPrevio = estadoServidor;
}

void activarModoCompetencia() {
  modoCompetencia = true;
  usarSensoresReales = true;
  nombreModoActual = "SENSORES_REALES";
  traccionHabilitada = true;
  barridoHabilitado = true;
  pickerModoManual = false;
  pickerApagarEnMs = 0;
  canicasRecolectadas = 0;
  estadoServidor = "BUSQUEDA";
  estadoServidorPrevio = "BUSQUEDA";
  ultimaVelIzq = 0.0f;
  ultimaVelDer = 0.0f;
  ultimoMensajeServidorMs = millis();
  failSafeActivo = false;

  for (uint8_t i = 0; i < NUM_US; i++) {
    filtroUs[i].init();
  }

  anguloServoActual = 0;
  direccionBarrido = 1;
  posicionarServosLaser(0);
  frenarPickerInmediato();

  if (tofEstomagoOk) {
    solicitarArmadoEstomago();
  }

  enviarHello();
  ENLACE_SERVIDOR.println(F("STATUS:MODO_COMPETENCIA_ON"));
  logInfo(F("\n>>> [COMPETENCIA ON] Modo Competencia ACTIVADO (Sensores + Barrido + Traccion + Picker listos)."));
}

void desactivarModoCompetencia() {
  modoCompetencia = false;
  traccionHabilitada = false;
  barridoHabilitado = false;
  pickerModoManual = false;
  pickerApagarEnMs = 0;
  ultimaVelIzq = 0.0f;
  ultimaVelDer = 0.0f;
  estadoServidor = "REPOSO";
  estadoServidorPrevio = "REPOSO";

  frenarTraccion();
  frenarPickerInmediato();
  soltarTodosLosServosPca();
  desarmarEstomago();

  ENLACE_SERVIDOR.println(F("STATUS:MODO_COMPETENCIA_OFF"));
  logInfo(F("\n>>> [COMPETENCIA OFF] Modo Reposo ACTIVADO (Todo quieto / PWM=0 / Motores libres)."));
}

void alternarModoCompetencia() {
  if (modoCompetencia) {
    desactivarModoCompetencia();
  } else {
    activarModoCompetencia();
  }
}

void imprimirMenuUsb() {
#if DEBUG_HABILITADO
  Serial.println(F("\n======================================================================"));
  Serial.println(F("  ORQUITA FIRMWARE DEFINITIVO - DOBLE RED NEURONAL (3US + 2LASER + ESTOMAGO)"));
  Serial.println(F("  [ARRANCA 100% QUIETO EN REPOSO - Enviar 'k' para PRENDER / APAGAR]"));
  Serial.println(F("======================================================================"));
  Serial.println(F("  [k]           : PRENDER / APAGAR MODO COMPETENCIA (toggle completo)"));
  Serial.println(F("  [p] / [x] / _ : APAGADO DE EMERGENCIA INMEDIATO (vuelve a reposo total)"));
  Serial.println(F("  [m]           : Habilitar / Deshabilitar solo TRACCION 4WD"));
  Serial.println(F("  [b] / [f]     : Alternar barrido ToF (-45°..+60°) / Fijar al frente (0°)"));
  Serial.println(F("  [g]           : Encender manualmente el PICKER 360°"));
  Serial.println(F("  [s] / [1]..[6]: Sensores reales / Escenarios simulados"));
  Serial.println(F("  [a]/[d]/[c]/[r]: Estomago (armar / desarmar / simular captura / reset)"));
  Serial.println(F("  [v] / [?]     : Alternar log continuo / Mostrar este menu"));
  Serial.println(F("======================================================================\n"));
#endif
}

void fijarEscenarioSimulado(float usL, float usC, float usR, float lasL, float lasR, float angS, const char *nombre) {
  usarSensoresReales = false;
  vectorSimulado[0] = usL;  vectorSimulado[1] = usC;  vectorSimulado[2] = usR;
  vectorSimulado[3] = lasL; vectorSimulado[4] = lasR; vectorSimulado[5] = angS;
  nombreModoActual = nombre;
#if DEBUG_HABILITADO
  Serial.print(F("\n>>> [MODO SIMULADO] "));
  Serial.println(nombreModoActual);
#endif
}

void ejecutarTeclaControl(char c) {
  switch (c) {
    case 'k':
    case 'K':
      alternarModoCompetencia();
      break;
    case 's':
    case 'S':
      usarSensoresReales = true;
      nombreModoActual = "SENSORES_REALES";
      logInfo(F("\n>>> [MODO] SENSORES REALES ACTIVADOS (3x HC-SR04 + 2x VL53L0X + Barrido [-45°..+60°])."));
      break;
    case '1':
      fijarEscenarioSimulado(90.0f, 95.0f, 85.0f, 39.5f, 39.8f, 0.0f, "1:ESPACIO_LIBRE");
      break;
    case '2':
      fijarEscenarioSimulado(90.0f, 95.0f, 85.0f, 11.2f, 11.5f, 0.0f, "2:LOBITO_CENTRO");
      break;
    case '3':
      fijarEscenarioSimulado(90.0f, 95.0f, 85.0f, 12.0f, 39.0f, 5.0f, "3:LOBITO_IZQ");
      break;
    case '4':
      fijarEscenarioSimulado(90.0f, 95.0f, 85.0f, 39.0f, 12.0f, 5.0f, "4:LOBITO_DER");
      break;
    case '5':
      fijarEscenarioSimulado(10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 0.0f, "5:BLOQUEO_MURO");
      break;
    case '6':
      fijarEscenarioSimulado(220.0f, 220.0f, 220.0f, 999.0f, 999.0f, 0.0f, "6:SALIDA");
      break;
    case 'm':
    case 'M':
      traccionHabilitada = !traccionHabilitada;
      if (!traccionHabilitada) {
        frenarTraccion();
      } else if (modoCompetencia && !failSafeActivo) {
        aplicarTraccion(ultimaVelIzq, ultimaVelDer);
      }
#if DEBUG_HABILITADO
      Serial.print(F("\n>>> [TRACCION 4WD] "));
      Serial.println(traccionHabilitada ? F("HABILITADA (ruedas activas)") : F("PAUSADA (modo seguro en mesa)"));
#endif
      break;
    case 'i':
    case 'I':
      invertirMotoresManual = !invertirMotoresManual;
      logInfo(invertirMotoresManual ? F("\n>>> [TRACCION] Sentido invertido (FORWARD <-> BACKWARD)")
                                   : F("\n>>> [TRACCION] Sentido de traccion restaurado normal."));
      break;
    case 'b':
    case 'B':
      barridoHabilitado = !barridoHabilitado;
      if (!barridoHabilitado && !modoCompetencia) {
        soltarTodosLosServosPca();
      }
#if DEBUG_HABILITADO
      Serial.print(F("\n>>> [SERVOS ToF] Barrido (-45° a +60°, paso 5°): "));
      Serial.println(barridoHabilitado ? F("ACTIVO") : F("PAUSADO"));
#endif
      break;
    case 'f':
    case 'F':
      barridoHabilitado = false;
      anguloServoActual = 0;
      posicionarServosLaser(0);
      logInfo(F("\n>>> [SERVOS ToF] Fijos al frente (ang = 0°, centro = 90°)."));
      break;
    case 'a':
    case 'A':
      solicitarArmadoEstomago();
      break;
    case 'd':
    case 'D':
      desarmarEstomago();
      logInfo(F("\n>>> [ESTOMAGO] Desarmado manual."));
      break;
    case 'c':
    case 'C':
      notificarCapturaAlServidor(35);
      break;
    case 'r':
    case 'R':
      canicasRecolectadas = 0;
      solicitarArmadoEstomago();
      logInfo(F("\n>>> [ESTOMAGO] Contador local reiniciado a 0/3."));
      break;
    case 'g':
    case 'G':
      pickerModoManual = true;
      pickerApagarEnMs = 0;
      encenderPickerSuave();
      logInfo(F("\n>>> [PICKER] Giro manual activado."));
      break;
    case 'p':
    case 'P':
    case 'x':
    case 'X':
    case ' ':
      desactivarModoCompetencia();
      break;
    case 'v':
    case 'V':
      verboseUsb = !verboseUsb;
      break;
    case '?':
      imprimirMenuUsb();
      break;
  }
}

void procesarLineaServidor(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  if (cmd == "CMD:TOGGLE") {
    alternarModoCompetencia();
    return;
  } else if (cmd == "CMD:START") {
    if (!modoCompetencia) activarModoCompetencia();
    return;
  } else if (cmd == "CMD:STOP") {
    desactivarModoCompetencia();
    return;
  } else if (cmd.startsWith("TEST:") && cmd.length() >= 6) {
    ejecutarTeclaControl(cmd.charAt(5));
    return;
  }

  if (cmd == "HEARTBEAT") {
    ultimoMensajeServidorMs = millis();
    failSafeActivo = false;
    return;
  }

  if (!modoCompetencia) {
    // En modo reposo ignoramos comandos de movimiento del servidor
    ultimoMensajeServidorMs = millis();
    failSafeActivo = false;
    return;
  }

  if (cmd.startsWith("COMMAND:")) {
    // Formato: COMMAND:<v_left>,<v_right>,<estado>
    int c1 = cmd.indexOf(',', 8);
    if (c1 > 8) {
      int c2 = cmd.indexOf(',', c1 + 1);
      float vLeft = cmd.substring(8, c1).toFloat();
      float vRight = (c2 > 0) ? cmd.substring(c1 + 1, c2).toFloat()
                              : cmd.substring(c1 + 1).toFloat();
      ultimoMensajeServidorMs = millis();
      failSafeActivo = false;
      if (c2 > 0) {
        String nuevoEst = cmd.substring(c2 + 1);
        nuevoEst.trim();
        actualizarEstadoOperativo(nuevoEst);
      }
      ultimaVelIzq = vLeft;
      ultimaVelDer = vRight;
      aplicarTraccion(vLeft, vRight);
    }
  } else if (cmd.startsWith("STATE_CHANGE:")) {
    String nuevoEst = cmd.substring(13);
    nuevoEst.trim();
    ultimoMensajeServidorMs = millis();
    failSafeActivo = false;
    actualizarEstadoOperativo(nuevoEst);
#if DEBUG_HABILITADO
    Serial.print(F("[SERVIDOR] STATE_CHANGE -> "));
    Serial.println(nuevoEst);
#endif
  } else if (cmd == "ARMAR") {
    solicitarArmadoEstomago();
  } else if (cmd == "DESARMAR") {
    desarmarEstomago();
  }
}

void procesarComandosDebugUsb() {
#if DEBUG_HABILITADO
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c != '\n' && c != '\r') {
      ejecutarTeclaControl(c);
    }
  }
#endif
}

// Drena el buffer del enlace con el servidor y verifica el Fail-Safe de 500 ms.
// Se invoca frecuentemente entre lecturas de sensores para no perder bytes a 9600 bps.
void atenderEnlaceYFailSafe() {
  while (ENLACE_SERVIDOR.available() > 0) {
    char c = (char)ENLACE_SERVIDOR.read();
    if (c == '\n' || c == '\r') {
      if (largoRxServidor > 0) {
        bufferRxServidor[largoRxServidor] = '\0';
        procesarLineaServidor(String(bufferRxServidor));
        largoRxServidor = 0;
      }
    } else if (largoRxServidor < sizeof(bufferRxServidor) - 1) {
      bufferRxServidor[largoRxServidor++] = c;
    } else {
      largoRxServidor = 0; // Descarte defensivo ante ruido en linea serie
    }
  }

  procesarComandosDebugUsb();

  if (!modoCompetencia) {
    return;
  }

  unsigned long ahora = millis();
  if ((ahora - ultimoMensajeServidorMs) > FAILSAFE_TIMEOUT_MS) {
    if (!failSafeActivo) {
      failSafeActivo = true;
      pickerApagarEnMs = 0;
      ultimaVelIzq = 0.0f;
      ultimaVelDer = 0.0f;
      frenarTraccion();
      if (!pickerModoManual) {
        frenarPickerInmediato();
      }
      ENLACE_SERVIDOR.println(F("ERROR:FAILSAFE_TIMEOUT"));
      logInfo(F("[FAIL-SAFE] >500 ms sin enlace con el servidor. Motores frenados."));
    }
  }
}

void muestrearYTransmitirTelemetria(unsigned long ahora) {
  if (!modoCompetencia) {
    // En modo REPOSO solo enviamos HEARTBEAT para mantener vivo el enlace Bluetooth
    // sin activar navegacion ni mover absolutamente nada.
    ENLACE_SERVIDOR.println(F("HEARTBEAT"));
    return;
  }

  float txUs[3];
  float txLaser[2];
  float txAng;

  if (usarSensoresReales) {
    for (uint8_t i = 0; i < NUM_US; i++) {
      ultimaDistUsCm[i] = filtroUs[i].actualizar(leerUltrasonicoCm(i));
      txUs[i] = ultimaDistUsCm[i];
      atenderEnlaceYFailSafe();
    }
    for (uint8_t k = 0; k < NUM_TOF_FRONTALES; k++) {
      ultimaDistLaserCm[k] = leerTofFrontalCm(k);
      txLaser[k] = ultimaDistLaserCm[k];
      atenderEnlaceYFailSafe();
    }
    txAng = (float)anguloServoActual;
  } else {
    txUs[0] = vectorSimulado[0];
    txUs[1] = vectorSimulado[1];
    txUs[2] = vectorSimulado[2];
    txLaser[0] = vectorSimulado[3];
    txLaser[1] = vectorSimulado[4];
    txAng = vectorSimulado[5];
  }

  // Formato exacto de 7 campos esperado por keiko-server:
  // TELEMETRY:<us_izq>,<us_cen>,<us_der>,<laser_izq>,<laser_der>,<ang_servo>,<ts_ms>\n
  ENLACE_SERVIDOR.print(F("TELEMETRY:"));
  ENLACE_SERVIDOR.print(txUs[0], 1);
  ENLACE_SERVIDOR.print(',');
  ENLACE_SERVIDOR.print(txUs[1], 1);
  ENLACE_SERVIDOR.print(',');
  ENLACE_SERVIDOR.print(txUs[2], 1);
  ENLACE_SERVIDOR.print(',');
  ENLACE_SERVIDOR.print(txLaser[0], 1);
  ENLACE_SERVIDOR.print(',');
  ENLACE_SERVIDOR.print(txLaser[1], 1);
  ENLACE_SERVIDOR.print(',');
  ENLACE_SERVIDOR.print(txAng, 1);
  ENLACE_SERVIDOR.print(',');
  ENLACE_SERVIDOR.println(ahora);

#if DEBUG_HABILITADO
  if (verboseUsb) {
    Serial.print('['); Serial.print(nombreModoActual); Serial.print(F("] US=["));
    Serial.print(txUs[0], 0); Serial.print('/');
    Serial.print(txUs[1], 0); Serial.print('/');
    Serial.print(txUs[2], 0);
    Serial.print(F("] L=["));
    Serial.print(txLaser[0], 1); Serial.print('/');
    Serial.print(txLaser[1], 1);
    Serial.print(F(" @")); Serial.print((int)txAng);
    Serial.print(F("°] Est=")); Serial.print(ultimaLecturaEstomagoMm);
    Serial.print(F("mm Caps=")); Serial.print(canicasRecolectadas);
    Serial.print(F("/3 FSM=")); Serial.print(estadoServidor);
    Serial.print(F(" Cmd=(")); Serial.print(ultimaVelIzq, 1);
    Serial.print(','); Serial.print(ultimaVelDer, 1);
    Serial.print(F(") Mot=")); Serial.print(traccionHabilitada ? F("ON") : F("OFF"));
    Serial.print(F(" BT=")); Serial.println(failSafeActivo ? F("ESPERANDO_SERVER") : F("OK"));
  }
#endif
}

void actualizarBarridoServos(unsigned long ahora) {
  if (!barridoHabilitado) {
    return;
  }
  // En modo competencia se bloquea solo ante FailSafe o al escapar/finalizar
  if (modoCompetencia && (failSafeActivo || estadoServidor == "ESCAPE" || estadoServidor == "FINALIZACION")) {
    return;
  }
  if ((ahora - ultimoPasoServoMs) < INTERVALO_PASO_SERVO_MS) {
    return;
  }
  ultimoPasoServoMs = ahora;

  anguloServoActual += direccionBarrido * BARRIDO_ANG_PASO;
  if (anguloServoActual >= BARRIDO_ANG_MAX) {
    anguloServoActual = BARRIDO_ANG_MAX;
    direccionBarrido = -1;
  } else if (anguloServoActual <= BARRIDO_ANG_MIN) {
    anguloServoActual = BARRIDO_ANG_MIN;
    direccionBarrido = 1;
  }
  posicionarServosLaser(anguloServoActual);
}

void actualizarLedEstado(unsigned long ahora) {
  if (!modoCompetencia) {
    // Pulso corto cada 2 segundos indicando REPOSO SEGURO
    digitalWrite(PIN_LED_ESTADO, ((ahora % 2000) < 100) ? HIGH : LOW);
  } else if (failSafeActivo) {
    digitalWrite(PIN_LED_ESTADO, LOW);
  } else if (estadoServidor == "ESCAPE") {
    digitalWrite(PIN_LED_ESTADO, ((ahora % 250) < 125) ? HIGH : LOW);
  } else if (estadoServidor == "FINALIZACION") {
    digitalWrite(PIN_LED_ESTADO, HIGH);
  } else {
    digitalWrite(PIN_LED_ESTADO, ((ahora % 1000) < 500) ? HIGH : LOW);
  }
}

// ============================================================================
// 9. SETUP Y LOOP PRINCIPAL
// ============================================================================

void setup() {
  Serial.begin(USB_BAUDIOS);
#if !USAR_USB_COMO_ENLACE_SERVIDOR
  Serial1.begin(BT_BAUDIOS);
#endif

  pinMode(PIN_LED_ESTADO, OUTPUT);
  digitalWrite(PIN_LED_ESTADO, LOW);

  pinMode(PICKER_DC_ENA, OUTPUT);
  pinMode(PICKER_DC_IN1, OUTPUT);
  pinMode(PICKER_DC_IN2, OUTPUT);
  analogWrite(PICKER_DC_ENA, 0);
  digitalWrite(PICKER_DC_IN1, LOW);
  digitalWrite(PICKER_DC_IN2, LOW);

  // Arrancar 100% QUIETO: motores en RELEASE y los 16 canales del PCA9685 en PWM=0
  modoCompetencia = false;
  traccionHabilitada = false;
  barridoHabilitado = false;
  estadoServidor = "REPOSO";
  frenarTraccion();

  for (uint8_t i = 0; i < NUM_US; i++) {
    pinMode(US_TRIG[i], OUTPUT);
    digitalWrite(US_TRIG[i], LOW);
    pinMode(US_ECHO[i], INPUT);
    filtroUs[i].init();
  }

  Wire.begin();
  Wire.setWireTimeout(25000, true);

  configurarPca();
  frenarPickerInmediato();
  soltarTodosLosServosPca();

  inicializarSensoresI2c();
  desarmarEstomago();

  ultimoMensajeServidorMs = millis();
  enviarHello();
  imprimirMenuUsb();
  logInfo(F(">>> [REPOSO SEGURO] Todo apagado (PWM=0, Motores libres). Envia 'k' para iniciar/detener Modo Competencia."));
}

void loop() {
  atenderEnlaceYFailSafe();

  unsigned long ahora = millis();

  if (modoCompetencia || pickerModoManual) {
    actualizarRampaPicker(ahora);
  }
  if (modoCompetencia || barridoHabilitado) {
    actualizarBarridoServos(ahora);
  }
  if (modoCompetencia) {
    actualizarDetectorEstomago(ahora);
  }

  unsigned long intervaloTx = modoCompetencia ? INTERVALO_TELEMETRIA_MS : 300UL;
  if ((ahora - ultimaTelemetriaMs) >= intervaloTx) {
    ultimaTelemetriaMs = ahora;
    muestrearYTransmitirTelemetria(ahora);
  }

  if ((ahora - ultimoChequeoPcaMs) >= INTERVALO_CHEQUEO_PCA_MS) {
    ultimoChequeoPcaMs = ahora;
    revisarPca();
  }

  actualizarLedEstado(ahora);
}
