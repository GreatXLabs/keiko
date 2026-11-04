/*
  Prueba de los 4 motores de tracción con el shield L293D en el Arduino Mega

  Conexiones (bornes del shield):
    M1 -> delantero izquierdo     M3 -> delantero derecho
    M2 -> trasero izquierdo       M4 -> trasero derecho
    Bornera EXT_PWR -> batería (jumper PWR según cómo se alimente el Mega)

  Al arrancar todo queda frenado. Conviene probar con el auto levantado
  (ruedas en el aire). Por el monitor serie (9600):
    '1'..'4' -> gira ese motor solo hacia adelante 2 s y frena
    'i'      -> recorre los 4 motores de a uno, en orden, 2 s cada uno
    'a'      -> los 4 hacia adelante 2 s
    'r'      -> los 4 hacia atrás 2 s
    'z'      -> gira a la izquierda en el lugar 2 s
    'd'      -> gira a la derecha en el lugar 2 s
    'p'      -> frena todo
    '+' / '-' -> sube o baja la velocidad
  Si un motor gira al revés con '1'..'4', cambiar su valor en INVERTIDO
  (o invertir sus dos cables en el borne del shield).
*/

#include <AFMotor.h>

const uint8_t NUM_MOTORES = 4;
const char *NOMBRE_MOTOR[NUM_MOTORES] = {
  "M1 delantero izquierdo", "M2 trasero izquierdo",
  "M3 delantero derecho", "M4 trasero derecho"
};
// true = flanco izquierdo, false = flanco derecho
const bool ES_IZQUIERDO[NUM_MOTORES] = {true, true, false, false};
// true = el motor está cableado al revés (FORWARD lo hace ir para atrás)
const bool INVERTIDO[NUM_MOTORES] = {true, true, true, true};

const uint16_t TIEMPO_PRUEBA_MS = 2000;
const uint8_t PASO_VEL = 25;

AF_DCMotor m1(1), m2(2), m3(3), m4(4);
AF_DCMotor *motor[NUM_MOTORES] = {&m1, &m2, &m3, &m4};

uint8_t velocidad = 180;

// sentido: FORWARD, BACKWARD o RELEASE, ya corregido según INVERTIDO
void correr(uint8_t k, uint8_t sentido) {
  if (INVERTIDO[k] && sentido != RELEASE) {
    sentido = (sentido == FORWARD) ? BACKWARD : FORWARD;
  }
  motor[k]->run(sentido);
}

void frenarTodo() {
  for (uint8_t k = 0; k < NUM_MOTORES; k++) {
    motor[k]->run(RELEASE);
  }
  Serial.println(F("Frenado"));
}

void probarUno(uint8_t k) {
  Serial.print(F("Adelante: "));
  Serial.println(NOMBRE_MOTOR[k]);
  correr(k, FORWARD);
  delay(TIEMPO_PRUEBA_MS);
  motor[k]->run(RELEASE);
}

// izq / der: FORWARD o BACKWARD para cada flanco
void moverFlancos(uint8_t izq, uint8_t der, const __FlashStringHelper *texto) {
  Serial.println(texto);
  for (uint8_t k = 0; k < NUM_MOTORES; k++) {
    correr(k, ES_IZQUIERDO[k] ? izq : der);
  }
  delay(TIEMPO_PRUEBA_MS);
  frenarTodo();
}

void fijarVelocidad(int16_t v) {
  velocidad = constrain(v, 0, 255);
  for (uint8_t k = 0; k < NUM_MOTORES; k++) {
    motor[k]->setSpeed(velocidad);
  }
  Serial.print(F("Velocidad "));
  Serial.print(velocidad);
  Serial.println(F("/255"));
}

void setup() {
  Serial.begin(9600);
  Serial.println(F("Prueba de tracción (shield L293D, M1-M4)"));
  Serial.println(F("Comandos: 1-4 motor solo, i los 4 en orden, a adelante, r atrás, z/d girar, p frenar, +/- velocidad"));
  fijarVelocidad(velocidad);
  frenarTodo();
}

void loop() {
  if (!Serial.available()) {
    return;
  }
  char c = Serial.read();
  switch (c) {
    case '1': case '2': case '3': case '4':
      probarUno(c - '1');
      Serial.println(F("Frenado"));
      break;
    case 'i':
      for (uint8_t k = 0; k < NUM_MOTORES; k++) {
        probarUno(k);
        delay(500);
      }
      Serial.println(F("Fin del recorrido"));
      break;
    case 'a': moverFlancos(FORWARD, FORWARD, F("Los 4 hacia adelante")); break;
    case 'r': moverFlancos(BACKWARD, BACKWARD, F("Los 4 hacia atrás")); break;
    case 'z': moverFlancos(BACKWARD, FORWARD, F("Girando a la izquierda")); break;
    case 'd': moverFlancos(FORWARD, BACKWARD, F("Girando a la derecha")); break;
    case 'p': frenarTodo(); break;
    case '+': fijarVelocidad(velocidad + PASO_VEL); break;
    case '-': fijarVelocidad(velocidad - PASO_VEL); break;
  }
}
