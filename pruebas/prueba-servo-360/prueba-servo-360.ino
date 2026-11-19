/*
  Servos SG90 modificados a 360° (rotación continua) para el picker, en los
  canales 2 y 3 del PCA9685

  Conexiones:
    PCA9685 SDA -> D20, SCL -> D21 (I2C del Mega), VCC -> 5V, GND -> GND
    PCA9685 V+ (bornera) -> 5V para los servos, con GND común
    Servos picker -> canal 2 = izquierdo, canal 3 = derecho
                     (marrón GND, rojo V+, naranja PWM)

  El pulso es velocidad: cerca del neutro frena, más lejos gira más rápido y
  con más fuerza. Al arrancar cada servo recibe su neutro (un servo sin
  modificar recibe 1500 µs = 90°, para centrar el potenciómetro).

  Por el monitor serie (9600). Los comandos actúan sobre los servos elegidos:
    '2' / '3' -> elegir solo el servo del canal 2 o del canal 3
    'b'       -> elegir los dos (al arrancar están los dos)
    'c' -> pulso neutro (quietos)
    '+' / '-' -> sube o baja el pulso 10 µs
    ']' / '[' -> sube o baja el pulso 1 µs (ajuste fino del frenado)
    'n' -> guarda el pulso actual como neutro (con un solo servo elegido)
    'a' / 'r' -> gira a un lado o al otro, a velUs del neutro
    '>' / '<' -> más o menos velocidad para 'a' / 'r' (5 µs)
    's' -> corta la señal (el servo queda suelto, sin fuerza)
*/

#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>

const uint8_t NUM_SERVOS = 2;
const uint8_t CANAL[NUM_SERVOS] = {2, 3};
// Canal 2 calibrado el 2026-11-01: quieto entre ~1465 y ~1485 µs.
// Canal 3 calibrado el 2026-11-02 girando el potenciómetro con 1500 µs
// aplicados hasta que el motor frenó.
const uint16_t NEUTRO_CALIBRADO[NUM_SERVOS] = {1485, 1500};
// 1 o -1: para que con 'a' los dos empujen la canica hacia el mismo lado
// (si están montados enfrentados, uno tiene que girar al revés del otro)
// Verificado el 2026-11-02 con los servos montados: con 'a' los dos
// recolectan (el izquierdo con pulsos bajos, el derecho con pulsos altos).
const int8_t SENTIDO[NUM_SERVOS] = {1, -1};

const uint16_t US_MIN = 500;
const uint16_t US_MAX = 2500;
const uint8_t PASO_GRUESO = 10;
const uint8_t PASO_FINO = 1;
const uint8_t PASO_VEL = 5;
const uint8_t TODOS = NUM_SERVOS;

Adafruit_PWMServoDriver pca;

uint16_t pulso[NUM_SERVOS];
uint16_t neutro[NUM_SERVOS];
uint16_t velUs = 40;  // distancia al neutro para 'a' / 'r'
uint8_t elegido = TODOS;

bool estaElegido(uint8_t k) {
  return elegido == TODOS || elegido == k;
}

void mandar(uint8_t k, int16_t us) {
  pulso[k] = constrain(us, US_MIN, US_MAX);
  pca.writeMicroseconds(CANAL[k], pulso[k]);
  Serial.print(F("Canal "));
  Serial.print(CANAL[k]);
  Serial.print(F(": pulso "));
  Serial.print(pulso[k]);
  Serial.print(F(" us (neutro "));
  Serial.print(neutro[k]);
  Serial.print(F(", "));
  Serial.print((int16_t)pulso[k] - (int16_t)neutro[k]);
  Serial.println(F(")"));
}

// sentido: -1 = 'a', 1 = 'r', 0 = neutro
void girar(int8_t sentido) {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    if (estaElegido(k)) {
      mandar(k, (int16_t)neutro[k] + sentido * SENTIDO[k] * (int16_t)velUs);
    }
  }
}

void moverPulso(int8_t delta) {
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    if (estaElegido(k)) {
      mandar(k, (int16_t)pulso[k] + delta);
    }
  }
}

void elegir(uint8_t k) {
  elegido = k;
  if (k == TODOS) {
    Serial.println(F("Elegidos: los dos"));
  } else {
    Serial.print(F("Elegido: canal "));
    Serial.println(CANAL[k]);
  }
}

void mostrarVelocidad() {
  Serial.print(F("Velocidad: "));
  Serial.print(velUs);
  Serial.println(F(" us del neutro"));
}

void setup() {
  Serial.begin(9600);
  pca.begin();
  pca.setOscillatorFrequency(27000000);
  pca.setPWMFreq(50);
  Serial.println(F("Servos 360 del picker (PCA9685 canales 2 y 3)"));
  Serial.println(F("Comandos: 2/3/b elegir, c neutro, +/- 10us, ]/[ 1us, n guardar neutro, a/r girar, >/< velocidad, s soltar"));
  for (uint8_t k = 0; k < NUM_SERVOS; k++) {
    neutro[k] = NEUTRO_CALIBRADO[k];
    mandar(k, neutro[k]);
  }
}

void loop() {
  if (!Serial.available()) {
    return;
  }
  switch (Serial.read()) {
    case '2': elegir(0); break;
    case '3': elegir(1); break;
    case 'b': elegir(TODOS); break;
    case 'c': girar(0); break;
    case '+': moverPulso(PASO_GRUESO); break;
    case '-': moverPulso(-PASO_GRUESO); break;
    case ']': moverPulso(PASO_FINO); break;
    case '[': moverPulso(-PASO_FINO); break;
    case 'n':
      if (elegido == TODOS) {
        Serial.println(F("Elegí un solo servo (2 o 3) para guardar su neutro"));
      } else {
        neutro[elegido] = pulso[elegido];
        Serial.print(F("Neutro guardado del canal "));
        Serial.print(CANAL[elegido]);
        Serial.print(F(": "));
        Serial.println(neutro[elegido]);
      }
      break;
    case 'a': girar(-1); break;
    case 'r': girar(1); break;
    case '>': velUs = min(500, velUs + PASO_VEL); mostrarVelocidad(); break;
    case '<': velUs = max(PASO_VEL, velUs - PASO_VEL); mostrarVelocidad(); break;
    case 's':
      for (uint8_t k = 0; k < NUM_SERVOS; k++) {
        if (estaElegido(k)) {
          pca.setPWM(CANAL[k], 0, 0);
          Serial.print(F("Canal "));
          Serial.print(CANAL[k]);
          Serial.println(F(": señal cortada (servo suelto)"));
        }
      }
      break;
  }
}
