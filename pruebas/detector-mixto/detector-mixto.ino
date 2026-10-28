/*
  Detector de canicas mixto: 3 HC-SR04 arriba + 2 VL53L0X abajo con servo

  Disposición (vista de frente):

        IZQUIERDA     MEDIO      DERECHA
  ARRIBA   US izq      US med      US der      (ultrasonido, obstáculos)
  ABAJO    ToF izq  -------------  ToF der     (láser en cada punta, con servo)

  Conexiones:
    US izq (arriba)  TRIG/ECHO -> 13 / 12
    US med (arriba)  TRIG/ECHO -> 11 / 10
    US der (arriba)  TRIG/ECHO -> 9 / 8
    ToF izq y der    SDA -> A4, SCL -> A5 (los dos en paralelo), VIN -> 5V
    ToF izq XSHUT -> A0      ToF der XSHUT -> A1
    PCA9685          SDA -> A4, SCL -> A5 (mismo bus que los ToF), VCC -> 5V
                     servo izq -> canal 0, servo der -> canal 1
                     V+ (bornera) -> fuente de 5V aparte, con GND común

  Barrido: los dos servos se mueven a la vez. Ángulo 0 = mirando derecho al
  frente; positivo = hacia afuera (la izquierda gira a la izquierda y la
  derecha a la derecha); negativo = hacia el centro. En cada paso se miden
  los 3 ultrasonidos (eso le da tiempo al servo a frenar) y después los ToF.

  Lógica: al arrancar se calibra el reposo de cada ToF en cada ángulo. Si un
  ToF lee bastante menos que su reposo, hay algo a esa distancia. Se calcula
  dónde está ese punto y se mira el ultrasonido de arriba que apunta para ese
  lado: si también ve algo a esa distancia es un obstáculo alto; si no, es
  una canica. Además, cada ultrasonido de arriba avisa por su cuenta si ve
  un obstáculo cerca. Por el monitor serie: 'c' recalibra, 'f' deja los
  láser quietos mirando al frente (para probar) y 'b' vuelve a barrer.
  Calibrar con el piso despejado: el reposo tiene que ser solo el piso.
*/

#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

// ---------- Ultrasonidos de arriba ----------
const uint8_t NUM_US = 3;
const uint8_t US_TRIG[NUM_US] = {13, 11, 9};
const uint8_t US_ECHO[NUM_US] = {12, 10, 8};
const char *LADOS[3] = {"IZQUIERDA", "MEDIO", "DERECHA"};
// Posición lateral (cm, positivo = izquierda) y hacia dónde apunta (grados)
const float US_X_CM[NUM_US] = {5.4, 0.0, -5.4};
const float US_ANG[NUM_US] = {30.0, 0.0, -30.0};
const float US_DIST_MAX_CM = 400.0;
const unsigned long US_TIMEOUT_US = 25000UL;
const unsigned int PAUSA_ENTRE_US_MS = 15;
// Un ultrasonido de arriba que ve algo más cerca que esto avisa de obstáculo
const float US_OBSTACULO_CM = 25.0;

// ---------- ToF de abajo ----------
const uint8_t NUM_TOF = 2;  // 0 = izquierda, 1 = derecha
const uint8_t TOF_XSHUT[NUM_TOF] = {A0, A1};
const uint8_t TOF_DIRECCION[NUM_TOF] = {0x30, 0x31};
// Posición del eje de cada servo (cm): lateral desde el centro del chasis
// (positivo = izquierda) y hacia adelante desde el panel frontal
const float TOF_X_CM[NUM_TOF] = {8.3, -8.3};
const float TOF_EJE_Y_CM = 0.8;
// El chip va pegado al aspa, adelante del eje: gira describiendo un arco
const float TOF_BRAZO_CM = 2.0;
const char *NOMBRE_TOF[NUM_TOF] = {"ToF izq", "ToF der"};
// Más allá de esto el ToF ya no distingue la canica del fondo
const float TOF_ALCANCE_CM = 18.0;
// Caída mínima respecto del reposo para considerar que hay algo
const float TOF_UMBRAL_CM = 8.0;
const float TOF_SIN_OBJETO_CM = 999.0;

// ---------- Servos ----------
const uint8_t SERVO_CANAL[NUM_TOF] = {0, 1};  // canales del PCA9685
// Ancho de pulso para 0° y 180° (típico de un SG90)
const uint16_t SERVO_US_0 = 500;
const uint16_t SERVO_US_180 = 2400;
// Valor del servo con el sensor mirando al frente, y sentido de giro:
// si un servo gira para el lado contrario, cambiá su signo a -1
const uint8_t SERVO_CENTRO[NUM_TOF] = {90, 90};
const int8_t SERVO_SENTIDO[NUM_TOF] = {-1, -1};
const int8_t ANG_MIN = -45;  // hacia el centro
const int8_t ANG_MAX = 60;   // hacia afuera
const int8_t ANG_PASO = 5;
const uint8_t NUM_ANG = (ANG_MAX - ANG_MIN) / ANG_PASO + 1;
// Tiempo mínimo para que el servo llegue y frene (el resto lo cubren los US)
const unsigned int PAUSA_SERVO_MS = 40;

// Si el ultrasonido de arriba ve algo hasta esta distancia más allá del
// punto detectado por el ToF, se considera que es el mismo objeto (alto)
const float MARGEN_OBSTACULO_CM = 6.0;
const uint8_t BARRIDOS_CALIBRACION = 3;

VL53L0X tof[NUM_TOF];
Adafruit_PWMServoDriver pca;  // dirección 0x40 (de fábrica)
float reposoTof[NUM_TOF][NUM_ANG];

float medirUltrasonido(uint8_t i) {
  digitalWrite(US_TRIG[i], LOW);
  delayMicroseconds(2);
  digitalWrite(US_TRIG[i], HIGH);
  delayMicroseconds(10);
  digitalWrite(US_TRIG[i], LOW);

  unsigned long duracion = pulseIn(US_ECHO[i], HIGH, US_TIMEOUT_US);
  if (duracion == 0) {
    return US_DIST_MAX_CM;
  }
  return duracion * 0.0343 / 2.0;
}

void medirUltrasonidos(float dist[]) {
  for (uint8_t i = 0; i < NUM_US; i++) {
    dist[i] = medirUltrasonido(i);
    delay(PAUSA_ENTRE_US_MS);
  }
}

float medirTof(uint8_t k) {
  uint16_t mm = tof[k].readRangeSingleMillimeters();
  if (tof[k].timeoutOccurred() || mm >= 8000) {
    return TOF_SIN_OBJETO_CM;
  }
  return mm / 10.0;
}

int8_t anguloDePaso(uint8_t paso) {
  return ANG_MIN + paso * ANG_PASO;
}

void moverServos(int8_t ang) {
  for (uint8_t k = 0; k < NUM_TOF; k++) {
    int16_t grados = constrain(SERVO_CENTRO[k] + SERVO_SENTIDO[k] * ang, 0, 180);
    pca.writeMicroseconds(SERVO_CANAL[k], map(grados, 0, 180, SERVO_US_0, SERVO_US_180));
  }
}

// Ángulo en el sistema común (positivo = hacia la izquierda)
float anguloGlobal(uint8_t k, int8_t ang) {
  return k == 0 ? ang : -ang;
}

// Modo largo alcance: más sensible al vidrio, que devuelve poca luz
bool iniciarTof(uint8_t k) {
  pinMode(TOF_XSHUT[k], INPUT);  // lo suelta: el pull-up del módulo lo enciende
  delay(50);
  tof[k].setTimeout(500);
  if (!tof[k].init()) {
    return false;
  }
  tof[k].setAddress(TOF_DIRECCION[k]);
  tof[k].setSignalRateLimit(0.1);
  tof[k].setVcselPulsePeriod(VL53L0X::VcselPeriodPreRange, 18);
  tof[k].setVcselPulsePeriod(VL53L0X::VcselPeriodFinalRange, 14);
  tof[k].setMeasurementTimingBudget(33000);
  return true;
}

// Ordena las muestras y devuelve la del medio
float mediana(float valores[], uint8_t n) {
  for (uint8_t i = 1; i < n; i++) {
    float v = valores[i];
    int8_t j = i - 1;
    while (j >= 0 && valores[j] > v) {
      valores[j + 1] = valores[j];
      j--;
    }
    valores[j + 1] = v;
  }
  return valores[n / 2];
}

void calibrar() {
  Serial.println(F("Calibrando: no pongas nada frente a los sensores..."));
  float muestras[NUM_TOF][NUM_ANG][BARRIDOS_CALIBRACION];
  for (uint8_t b = 0; b < BARRIDOS_CALIBRACION; b++) {
    for (uint8_t p = 0; p < NUM_ANG; p++) {
      moverServos(anguloDePaso(p));
      delay(PAUSA_SERVO_MS + 100);
      for (uint8_t k = 0; k < NUM_TOF; k++) {
        muestras[k][p][b] = medirTof(k);
      }
    }
  }
  for (uint8_t k = 0; k < NUM_TOF; k++) {
    Serial.print(F("  Reposo "));
    Serial.print(NOMBRE_TOF[k]);
    Serial.print(F(":"));
    for (uint8_t p = 0; p < NUM_ANG; p++) {
      reposoTof[k][p] = mediana(muestras[k][p], BARRIDOS_CALIBRACION);
      Serial.print(F(" "));
      Serial.print(anguloDePaso(p));
      Serial.print(F("°="));
      Serial.print(reposoTof[k][p], 0);
    }
    Serial.println(F(" cm"));
  }
}

// Decide si lo que vio el ToF k en el paso p es una canica y la informa
void evaluar(uint8_t k, uint8_t p, float d, const float distUs[]) {
  if (d > TOF_ALCANCE_CM || reposoTof[k][p] - d < TOF_UMBRAL_CM) {
    return;
  }

  // Posición del punto detectado (cm): x lateral (+ izquierda), y al frente
  float rad = radians(anguloGlobal(k, anguloDePaso(p)));
  // (desde el eje del servo: el brazo hasta el chip y después la distancia medida)
  float x = TOF_X_CM[k] + (TOF_BRAZO_CM + d) * sin(rad);
  float y = TOF_EJE_Y_CM + (TOF_BRAZO_CM + d) * cos(rad);

  // Ultrasonido de arriba que mejor apunta a ese punto
  uint8_t us = 0;
  float mejorDif = 360.0;
  for (uint8_t i = 0; i < NUM_US; i++) {
    float angPunto = degrees(atan2(x - US_X_CM[i], y));
    float dif = fabs(angPunto - US_ANG[i]);
    if (dif < mejorDif) {
      mejorDif = dif;
      us = i;
    }
  }
  float distAlPunto = sqrt(sq(x - US_X_CM[us]) + sq(y));
  bool obstaculoAlto = distUs[us] <= distAlPunto + MARGEN_OBSTACULO_CM;

  uint8_t lado = x > 3.0 ? 0 : (x < -3.0 ? 2 : 1);
  Serial.print(obstaculoAlto ? F("  -> obstáculo alto a la ") : F("  -> CANICA DETECTADA a la "));
  Serial.print(LADOS[lado]);
  Serial.print(F(" ("));
  Serial.print(NOMBRE_TOF[k]);
  Serial.print(F(", x="));
  Serial.print(x, 1);
  Serial.print(F(" cm, adelante="));
  Serial.print(y, 1);
  Serial.println(F(" cm)"));
}

void setup() {
  Serial.begin(9600);
  for (uint8_t i = 0; i < NUM_US; i++) {
    pinMode(US_TRIG[i], OUTPUT);
    digitalWrite(US_TRIG[i], LOW);
    pinMode(US_ECHO[i], INPUT);
  }

  // Apaga los dos ToF y los enciende de a uno para darles direcciones distintas
  Wire.begin();
  // Si el bus I2C se traba (un cable flojo), corta y lo reinicia en vez de colgarse
  Wire.setWireTimeout(25000, true);
  for (uint8_t k = 0; k < NUM_TOF; k++) {
    pinMode(TOF_XSHUT[k], OUTPUT);
    digitalWrite(TOF_XSHUT[k], LOW);
  }

  // Los servos se centran antes que nada: sirve para montar los sensores
  // mirando al frente aunque los ToF todavía no estén conectados
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
  moverServos(0);
  delay(500);

  for (uint8_t k = 0; k < NUM_TOF; k++) {
    if (!iniciarTof(k)) {
      Serial.print(F("No se encontró el "));
      Serial.print(NOMBRE_TOF[k]);
      Serial.println(F(": revisá SDA/SCL, XSHUT y alimentación"));
      while (true) {}
    }
  }

  calibrar();
  Serial.println(F("Detector mixto listo"));
}

void loop() {
  // Barrido de ida y vuelta
  static uint8_t paso = 0;
  static int8_t sentido = 1;
  static bool fijo = false;

  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'c') calibrar();
    if (c == 'f') fijo = true;
    if (c == 'b') fijo = false;
  }

  // Modo fijo: los dos láser quietos mirando al frente, lecturas seguidas
  if (fijo) {
    moverServos(0);
    Serial.print(F("FIJO 0° | ToF izq: "));
    Serial.print(medirTof(0), 1);
    Serial.print(F(" | ToF der: "));
    Serial.println(medirTof(1), 1);
    return;
  }

  moverServos(anguloDePaso(paso));
  delay(PAUSA_SERVO_MS);

  float distUs[NUM_US];
  medirUltrasonidos(distUs);
  float distTof[NUM_TOF];
  for (uint8_t k = 0; k < NUM_TOF; k++) {
    distTof[k] = medirTof(k);
  }

  Serial.print(F("ang "));
  Serial.print(anguloDePaso(paso));
  Serial.print(F("° | ToF izq: "));
  Serial.print(distTof[0], 1);
  Serial.print(F(" | ToF der: "));
  Serial.print(distTof[1], 1);
  Serial.print(F(" | arriba: "));
  for (uint8_t i = 0; i < NUM_US; i++) {
    Serial.print(distUs[i], 0);
    Serial.print(i < NUM_US - 1 ? F(" / ") : F(" cm\n"));
  }

  for (uint8_t i = 0; i < NUM_US; i++) {
    if (distUs[i] < US_OBSTACULO_CM) {
      Serial.print(F("  -> OBSTÁCULO a la "));
      Serial.print(LADOS[i]);
      Serial.print(F(" a "));
      Serial.print(distUs[i], 0);
      Serial.println(F(" cm (ultrasonido)"));
    }
  }

  for (uint8_t k = 0; k < NUM_TOF; k++) {
    evaluar(k, paso, distTof[k], distUs);
  }

  if ((sentido > 0 && paso == NUM_ANG - 1) || (sentido < 0 && paso == 0)) {
    sentido = -sentido;
  }
  paso += sentido;
}
