/*
  Prueba del motor del picker con el L298N (canal A) en el Arduino Mega

  Conexiones:
    L298N ENA (sin jumper) -> D45 (PWM, Timer5: no choca con el shield L293D)
    L298N IN1              -> D22
    L298N IN2              -> D23
    L298N OUT1 / OUT2      -> motor del picker
    L298N +12V / GND       -> batería; GND unido al GND del Mega
    Jumper 5V-EN del L298N puesto

  Al arrancar el motor queda frenado. Por el monitor serie (9600, sin ajuste
  de línea o con cualquiera, los saltos de línea se ignoran):
    'a' -> gira hacia adelante (recolectar) a la velocidad actual
    'r' -> gira al revés (destrabar) a la velocidad actual
    'p' -> frena
    '+' -> sube la velocidad un paso
    '-' -> baja la velocidad un paso
    '1'..'9' -> velocidad del 10% al 90%, '0' -> 100%
    'm' -> rampa: sube de 0 a 100% y baja, adelante y después al revés
  Si un sentido sale al revés de lo esperado, invertir los cables del motor
  en OUT1 / OUT2.
*/

const uint8_t PICKER_ENA = 45;
const uint8_t PICKER_IN1 = 22;
const uint8_t PICKER_IN2 = 23;

const uint8_t PASO_VEL = 25;
// Por debajo de esto un motor TT con el L298N suele zumbar sin girar
const uint8_t VEL_MINIMA_TIPICA = 80;

uint8_t velocidad = 150;
int8_t sentido = 0;  // 1 = adelante, -1 = al revés, 0 = frenado

void picker(int16_t vel) {
  vel = constrain(vel, -255, 255);
  digitalWrite(PICKER_IN1, vel > 0);
  digitalWrite(PICKER_IN2, vel < 0);
  analogWrite(PICKER_ENA, abs(vel));
}

void aplicar() {
  picker(sentido * (int16_t)velocidad);
  Serial.print(sentido > 0 ? F("Adelante") : sentido < 0 ? F("Al revés") : F("Frenado"));
  Serial.print(F(" | velocidad "));
  Serial.print(velocidad);
  Serial.print(F("/255 ("));
  Serial.print(velocidad * 100 / 255);
  Serial.println(F("%)"));
  if (sentido != 0 && velocidad < VEL_MINIMA_TIPICA) {
    Serial.println(F("  Ojo: velocidad baja, puede que el motor no arranque"));
  }
}

void rampa(int8_t s) {
  Serial.println(s > 0 ? F("Rampa adelante") : F("Rampa al revés"));
  for (int16_t v = 0; v <= 255; v += 5) {
    picker(s * v);
    delay(40);
  }
  delay(500);
  for (int16_t v = 255; v >= 0; v -= 5) {
    picker(s * v);
    delay(40);
  }
}

void setup() {
  pinMode(PICKER_ENA, OUTPUT);
  pinMode(PICKER_IN1, OUTPUT);
  pinMode(PICKER_IN2, OUTPUT);
  picker(0);
  Serial.begin(9600);
  Serial.println(F("Prueba del picker (L298N canal A)"));
  Serial.println(F("Comandos: a adelante, r al revés, p frenar, +/- velocidad, 1-9/0 velocidad en %, m rampa"));
  aplicar();
}

void loop() {
  if (!Serial.available()) {
    return;
  }
  char c = Serial.read();
  switch (c) {
    case 'a': sentido = 1; aplicar(); break;
    case 'r': sentido = -1; aplicar(); break;
    case 'p': sentido = 0; aplicar(); break;
    case '+': velocidad = min(255, velocidad + PASO_VEL); aplicar(); break;
    case '-': velocidad = max(0, velocidad - PASO_VEL); aplicar(); break;
    case 'm':
      rampa(1);
      delay(500);
      rampa(-1);
      sentido = 0;
      aplicar();
      break;
    default:
      if (c >= '0' && c <= '9') {
        velocidad = (c == '0') ? 255 : (c - '0') * 255 / 10;
        aplicar();
      }
      break;
  }
}
