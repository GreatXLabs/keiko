/*
  Detector de canicas con 6 sensores HC-SR04 (matriz 3x2)

  Disposición (vista de frente):

        IZQUIERDA     MEDIO      DERECHA
  ARRIBA   S4           S5          S6
  ABAJO    S1           S2          S3

  Conexiones (TRIG / ECHO), según el montaje físico real:
    S1 abajo-izq  -> 2 / 3
    S2 abajo-med  -> 4 / 5
    S3 abajo-der  -> 6 / 7
    S4 arriba-izq -> 8 / 9
    S5 arriba-med -> 10 / 11
    S6 arriba-der -> 12 / 13

  Montaje (placa de 155 x 100 mm, ver simulacion/):
    - Abajo: centro de los cilindros a 15 mm del piso. Arriba: a 95 mm.
    - Columnas a 5,4 cm entre centros; los laterales girados 30° hacia afuera.

  Lógica: al arrancar se calibra la distancia de reposo de cada sensor (sin
  nada al frente). Hay canica en una columna cuando el sensor de abajo cae
  bastante respecto de su reposo y además mide bastante menos que TODOS los
  de arriba (los conos se pisan: si cualquiera de arriba ve algo a esa
  distancia, es un objeto alto, no una canica). Se informa solo la columna
  con la lectura de abajo más cercana. Enviando 'c' por el monitor serie se
  recalibra.
*/

const uint8_t NUM_SENSORES = 6;
const uint8_t TRIG[NUM_SENSORES] = {2, 4, 6, 8, 10, 12};
const uint8_t ECHO[NUM_SENSORES] = {3, 5, 7, 9, 11, 13};

const char *COLUMNAS[3] = {"IZQUIERDA", "MEDIO", "DERECHA"};

// Caída mínima (cm) respecto del reposo para considerar que hay algo al frente
const float UMBRAL_CM = 5.0;
// Ciclos de medición de calibración; el reposo es la 2ª lectura más corta
const uint8_t CICLOS_CALIBRACION = 11;
// Distancia que se asume cuando no vuelve eco (fuera de rango)
const float DIST_MAX_CM = 400.0;
// Timeout de pulseIn (~25 ms equivale a ~4,3 m)
const unsigned long TIMEOUT_US = 25000UL;
// Pausa entre disparos para que los ecos de un sensor no lleguen a otro
const unsigned int PAUSA_ENTRE_SENSORES_MS = 15;
const unsigned int PAUSA_CICLO_MS = 200;

float medirDistancia(uint8_t i) {
  digitalWrite(TRIG[i], LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG[i], HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG[i], LOW);

  unsigned long duracion = pulseIn(ECHO[i], HIGH, TIMEOUT_US);
  if (duracion == 0) {
    return DIST_MAX_CM;  // sin eco: nada al frente dentro del rango
  }
  return duracion * 0.0343 / 2.0;  // velocidad del sonido ~343 m/s
}

float reposo[NUM_SENSORES];

// Se disparan de a uno para evitar interferencia entre sensores
void medirTodos(float dist[]) {
  for (uint8_t i = 0; i < NUM_SENSORES; i++) {
    dist[i] = medirDistancia(i);
    delay(PAUSA_ENTRE_SENSORES_MS);
  }
}

// Ordena las muestras y devuelve la 2ª más corta: el eco más cercano que el
// sensor ve habitualmente, descartando una sola lectura espuria
float reposoDeMuestras(float valores[], uint8_t n) {
  for (uint8_t i = 1; i < n; i++) {
    float v = valores[i];
    int8_t j = i - 1;
    while (j >= 0 && valores[j] > v) {
      valores[j + 1] = valores[j];
      j--;
    }
    valores[j + 1] = v;
  }
  return valores[1];
}

void calibrar() {
  Serial.println(F("Calibrando: no pongas nada frente a los sensores..."));
  float muestras[NUM_SENSORES][CICLOS_CALIBRACION];
  float dist[NUM_SENSORES];
  for (uint8_t c = 0; c < CICLOS_CALIBRACION; c++) {
    medirTodos(dist);
    for (uint8_t i = 0; i < NUM_SENSORES; i++) {
      muestras[i][c] = dist[i];
    }
  }
  for (uint8_t i = 0; i < NUM_SENSORES; i++) {
    reposo[i] = reposoDeMuestras(muestras[i], CICLOS_CALIBRACION);
    Serial.print(F("  Reposo S"));
    Serial.print(i + 1);
    Serial.print(F(": "));
    Serial.print(reposo[i], 1);
    Serial.println(F(" cm"));
  }
}

void setup() {
  Serial.begin(9600);
  for (uint8_t i = 0; i < NUM_SENSORES; i++) {
    pinMode(TRIG[i], OUTPUT);
    digitalWrite(TRIG[i], LOW);
    pinMode(ECHO[i], INPUT);
  }
  calibrar();
  Serial.println(F("Detector de canicas listo"));
}

void loop() {
  if (Serial.available() && Serial.read() == 'c') {
    calibrar();
  }

  float dist[NUM_SENSORES];
  medirTodos(dist);

  float arribaMin = min(dist[3], min(dist[4], dist[5]));
  int8_t canica = -1;

  Serial.println(F("-------------------------------------------"));
  for (uint8_t col = 0; col < 3; col++) {
    float abajo = dist[col];
    float arriba = dist[col + 3];

    bool bajoAbajo = reposo[col] - abajo >= UMBRAL_CM;
    bool arribaMasLejos = arribaMin - abajo >= UMBRAL_CM;
    if (bajoAbajo && arribaMasLejos && (canica < 0 || abajo < dist[canica])) {
      canica = col;
    }

    Serial.print(COLUMNAS[col]);
    Serial.print(F(" | abajo (S"));
    Serial.print(col + 1);
    Serial.print(F("): "));
    Serial.print(abajo, 1);
    Serial.print(F(" cm | arriba (S"));
    Serial.print(col + 4);
    Serial.print(F("): "));
    Serial.print(arriba, 1);
    Serial.println(F(" cm"));
  }

  if (canica >= 0) {
    Serial.print(F("  -> CANICA DETECTADA en "));
    Serial.print(COLUMNAS[canica]);
    Serial.print(F(" a "));
    Serial.print(dist[canica], 1);
    Serial.println(F(" cm"));
  }

  delay(PAUSA_CICLO_MS);
}
