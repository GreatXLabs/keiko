/*
  Picker + sensor del estómago: los dos servos del picker giran recolectando
  y un VL53L0X mirando dentro del estómago confirma la captura.

  Cómo detecta: no cuenta pasadas. Las canicas van y vuelven dentro del
  estómago y a veces quedan quietas frente al sensor, así que contar cambios
  da capturas de más. En cambio:
    1. Al entrar en CAPTURA se "arma" el detector ('a'): aprende la lectura
       que hay en ese momento (vacío, o con canicas ya quietas adelante).
    2. El primer cambio grande respecto de esa lectura = entró una canica
       nueva (o la nueva movió a una que estaba quieta). Avisa UNA vez y se
       desarma.
    3. No vuelve a avisar hasta que lo armen de nuevo (en el robot real, el
       servidor al entrar otra vez en CAPTURA).
  Ojo: si el robot se sacude mientras está armado puede mover una canica
  quieta y dar una captura falsa.

  Conexiones (Arduino Mega):
    PCA9685 SDA -> D20, SCL -> D21, VCC -> 5V, GND -> GND
    PCA9685 V+  -> XL4015 (5 V de los servos), GND común
    Servos picker -> canal 2 = izquierdo, canal 3 = derecho
    VL53L0X estómago: VIN -> 5V, GND, SDA -> D20, SCL -> D21, XSHUT -> A10
    VL53L0X izq / der (si están conectados): XSHUT -> A8 / A9. En esta prueba
    quedan apagados.

  Sin destrabe: con los servos alimentados del XL4015 la caída de tensión
  del Mega no ve los trabados (haría falta un INA219).

  Por el monitor serie (9600):
    'g' -> gira el picker      'p' -> frena el picker
    'a' -> arma el detector (aprende la lectura actual y espera una canica)
    'd' -> desarma el detector
    'v' -> muestra / oculta las lecturas crudas
*/

#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>
#include <VL53L0X.h>

// ---- Picker (igual que picker-girando) ----
const uint8_t NUM_SERVOS = 2;
const uint8_t CANAL[NUM_SERVOS] = {2, 3};
const uint16_t NEUTRO[NUM_SERVOS] = {1475, 1500};
const int8_t SENTIDO[NUM_SERVOS] = {1, -1};
const uint16_t VEL_US[NUM_SERVOS] = {240, 200};
const uint8_t PASOS_ARRANQUE = 10;
const uint8_t MS_POR_PASO = 50;
const uint16_t MS_CHEQUEO_PCA = 500;
const uint8_t DIR_PCA = 0x40;
const uint8_t PCA_MODE1 = 0x00;
const uint8_t PCA_SLEEP = 0x10;

// ---- Sensor del estómago ----
const uint8_t XSHUT_ESTOMAGO = A10;
const uint8_t XSHUT_OTROS[] = {A8, A9};
const uint8_t DIR_ESTOMAGO = 0x32;
// 20 ms por medición: lo más rápido que permite el sensor
const uint32_t PRESUPUESTO_US = 20000;
// Lecturas para aprender la base al armar (~200 ms)
const uint8_t MUESTRAS_BASE = 10;
// Cuánto tiene que cambiar la lectura para decir "entró una canica". Girando
// sin canicas el ruido llegó a ~7 mm; una canica cambia 15 mm o más.
const uint16_t UMBRAL_MM = 10;
// Lecturas seguidas por encima del umbral para confirmar (descarta picos)
const uint8_t LECTURAS_CONFIRMAR = 2;
// Lecturas fuera de rango / sin eco
const uint16_t SIN_LECTURA = 8000;

enum Detector : uint8_t { DESARMADO, ARMADO };

Adafruit_PWMServoDriver pca;
VL53L0X estomago;

bool sensorOk = false;
bool pickerGirando = false;
Detector detector = DESARMADO;
uint16_t baseMm = 0;
uint8_t seguidas = 0;
uint16_t capturas = 0;
uint32_t tArmado = 0;
bool verbose = false;

// ---------------- Picker ----------------

void configurarPca() {
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
}

void mandarPulsos(uint8_t escala) {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    int16_t vel = (int32_t)VEL_US[k] * escala / PASOS_ARRANQUE;
    pca.writeMicroseconds(CANAL[k], NEUTRO[k] - SENTIDO[k] * vel);
  }
}

void girarPicker() {
  for (uint8_t paso = 1; paso <= PASOS_ARRANQUE; paso++) {
    mandarPulsos(paso);
    delay(MS_POR_PASO);
  }
  pickerGirando = true;
  Serial.println(F("Picker girando"));
}

void frenarPicker() {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    pca.setPWM(CANAL[k], 0, 0);
  }
  pickerGirando = false;
  Serial.println(F("Picker frenado"));
}

// Si el PCA se reinició por una caída de tensión arranca dormido: reconfigurar
void revisarPca() {
  Wire.beginTransmission(DIR_PCA);
  Wire.write(PCA_MODE1);
  if (Wire.endTransmission() != 0) {
    return;
  }
  Wire.requestFrom(DIR_PCA, (uint8_t)1);
  if (!Wire.available() || !(Wire.read() & PCA_SLEEP)) {
    return;
  }
  configurarPca();
  if (pickerGirando) {
    mandarPulsos(PASOS_ARRANQUE);
  }
  Serial.println(F("El PCA9685 se había reiniciado: reconfigurado"));
}

// ---------------- Sensor ----------------

void escanearI2c() {
  Serial.print(F("I2C:"));
  for (uint8_t dir = 1; dir < 127; dir++) {
    Wire.beginTransmission(dir);
    if (Wire.endTransmission() == 0) {
      Serial.print(F(" 0x"));
      Serial.print(dir, HEX);
    }
  }
  Serial.println();
}

bool iniciarEstomago() {
  // Apaga los tres láseres: todos arrancan en 0x29 y se pisarían
  for (uint8_t pin : XSHUT_OTROS) {
    pinMode(pin, OUTPUT);
    digitalWrite(pin, LOW);
  }
  pinMode(XSHUT_ESTOMAGO, OUTPUT);
  digitalWrite(XSHUT_ESTOMAGO, LOW);
  delay(10);
  // Lo suelta: el pull-up del módulo lo enciende (no se le mandan 5 V)
  pinMode(XSHUT_ESTOMAGO, INPUT);
  delay(50);

  estomago.setTimeout(100);
  if (!estomago.init()) {
    return false;
  }
  estomago.setAddress(DIR_ESTOMAGO);
  estomago.setMeasurementTimingBudget(PRESUPUESTO_US);
  estomago.startContinuous();
  return true;
}

// 0 = sin lectura válida
uint16_t leerEstomago() {
  uint16_t mm = estomago.readRangeContinuousMillimeters();
  if (estomago.timeoutOccurred() || mm >= SIN_LECTURA) {
    return 0;
  }
  return mm;
}

void armar() {
  if (!sensorOk) {
    Serial.println(F("No se puede armar: el sensor del estómago no responde"));
    return;
  }
  uint32_t suma = 0;
  uint8_t n = 0;
  for (uint8_t i = 0; i < MUESTRAS_BASE; i++) {
    uint16_t mm = leerEstomago();
    if (mm > 0) {
      suma += mm;
      n++;
    }
  }
  if (n < MUESTRAS_BASE / 2) {
    detector = DESARMADO;
    Serial.println(F("No se pudo armar: casi sin lecturas válidas, revisá el sensor"));
    return;
  }
  baseMm = suma / n;
  seguidas = 0;
  tArmado = millis();
  detector = ARMADO;
  Serial.print(F("Armado, base "));
  Serial.print(baseMm);
  Serial.println(F(" mm: esperando una canica..."));
}

void desarmar() {
  detector = DESARMADO;
  Serial.println(F("Desarmado"));
}

void procesarEstomago(uint16_t mm) {
  if (detector != ARMADO) {
    return;
  }
  // Sin lectura = algo tapó o desvió el haz: cuenta como cambio
  uint16_t delta = mm == 0 ? UMBRAL_MM : (mm > baseMm ? mm - baseMm : baseMm - mm);
  if (delta < UMBRAL_MM) {
    seguidas = 0;
    return;
  }
  if (++seguidas < LECTURAS_CONFIRMAR) {
    return;
  }
  capturas++;
  detector = DESARMADO;
  Serial.print(F("CAPTURA "));
  Serial.print(capturas);
  Serial.print(F(": "));
  Serial.print(mm);
  Serial.print(F(" mm (base "));
  Serial.print(baseMm);
  Serial.print(F("), a los "));
  Serial.print(millis() - tArmado);
  Serial.println(F(" ms de armar. Desarmado: mandá 'a' para la próxima"));
}

// ---------------- Principal ----------------

void setup() {
  Serial.begin(9600);
  Wire.begin();
  Serial.println(F("Picker + sensor del estómago (captura armada)"));

  sensorOk = iniciarEstomago();
  if (sensorOk) {
    Serial.println(F("VL53L0X del estómago OK en 0x32"));
  } else {
    Serial.println(F("No responde el VL53L0X del estómago: revisá SDA/SCL, XSHUT (A10) y 5V"));
  }
  escanearI2c();

  configurarPca();
  frenarPicker();
  Serial.println(F("Comandos: g girar, p frenar, a armar, d desarmar, v lecturas"));
}

void loop() {
  static uint32_t tChequeoPca = 0;

  if (sensorOk) {
    uint16_t mm = leerEstomago();
    procesarEstomago(mm);
    if (verbose) {
      Serial.print(F("mm "));
      Serial.println(mm);
    }
  }

  uint32_t ahora = millis();
  if (ahora - tChequeoPca >= MS_CHEQUEO_PCA) {
    tChequeoPca = ahora;
    revisarPca();
  }

  if (Serial.available()) {
    switch (Serial.read()) {
      case 'g': girarPicker(); break;
      case 'p': frenarPicker(); break;
      case 'a': armar(); break;
      case 'd': desarmar(); break;
      case 'v': verbose = !verbose; break;
    }
  }
}
