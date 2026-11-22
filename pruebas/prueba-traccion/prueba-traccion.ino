/*
  Prueba de los 4 motores de tracción con el shield L293D en el Arduino Mega

  Conexiones (bornes del shield):
    M1 -> delantero izquierdo     M3 -> delantero derecho
    M2 -> trasero izquierdo       M4 -> trasero derecho
    Bornera EXT_PWR -> batería (jumper PWR según cómo se alimente el Mega)

  Al arrancar todo queda frenado. Manejo (monitor serie USB a 9600, o el
  HC-05 por Bluetooth en Serial1 D18/D19 a 9600; mismos comandos):
    W adelante, S atrás, A gira a la izquierda, D gira a la derecha
      (mantener apretada la tecla; al soltar frena a los ~0,45 s)
    espacio o 'p' -> frena todo
    '1'..'4' -> gira ese motor solo hacia adelante 2 s y frena
    '+' / '-' -> sube o baja la velocidad (arranca en 180)
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
  Serial1.begin(9600);  // HC-05: los mismos comandos llegan por Bluetooth
  Serial.println(F("Prueba de tracción (shield L293D, M1-M4)"));
  Serial.println(F("Comandos: WASD mover (mantener), espacio frenar, 1-4 motor solo, +/- velocidad"));
  fijarVelocidad(velocidad);
  frenarTodo();
}

// Manejo con teclado: mientras llegan teclas (repetición del terminal) se mueve;
// si dejan de llegar por HOLD_MS, frena solo.
const uint16_t HOLD_MS = 450;
unsigned long ultimaTecla = 0;
bool enMarcha = false;

void manejar(uint8_t izq, uint8_t der) {
  for (uint8_t k = 0; k < NUM_MOTORES; k++) {
    correr(k, ES_IZQUIERDO[k] ? izq : der);
  }
  ultimaTecla = millis();
  enMarcha = true;
}

void loop() {
  if (enMarcha && millis() - ultimaTecla > HOLD_MS) {
    for (uint8_t k = 0; k < NUM_MOTORES; k++) motor[k]->run(RELEASE);
    enMarcha = false;
  }
  char c;
  if (Serial.available()) c = Serial.read();
  else if (Serial1.available()) c = Serial1.read();
  else return;
  if (c >= 'A' && c <= 'Z') c += 32;
  switch (c) {
    case 'w': manejar(FORWARD, FORWARD); break;
    case 's': manejar(BACKWARD, BACKWARD); break;
    case 'a': manejar(BACKWARD, FORWARD); break;   // gira a la izquierda
    case 'd': manejar(FORWARD, BACKWARD); break;   // gira a la derecha
    case ' ': case 'p': frenarTodo(); enMarcha = false; break;
    case '1': case '2': case '3': case '4':
      probarUno(c - '1');
      break;
    case '+': fijarVelocidad(velocidad + PASO_VEL); break;
    case '-': fijarVelocidad(velocidad - PASO_VEL); break;
  }
}
