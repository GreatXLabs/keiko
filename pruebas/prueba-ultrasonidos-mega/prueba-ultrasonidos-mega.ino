/*
  Prueba de los 3 ultrasonidos HC-SR04 de arriba en el Arduino Mega

  Conexiones (pines libres del Mega, fuera del shield L293D):
    Izquierda  TRIG/ECHO -> 24 / 25
    Medio      TRIG/ECHO -> 26 / 27
    Derecha    TRIG/ECHO -> 28 / 29
    VCC -> 5V, GND -> GND
  Son los pines propuestos en la bitácora. El firmware de tests-emi tiene
  medio y derecha cruzados (27/26 y 29/28): este sketch dice cuál es cierto.

  Por el monitor serie (115200):
    (nada)  -> lectura cruda de cada sensor cada 100 ms. "---" = sin eco
               (NO significa que esté libre: puede ser cable flojo o cruzado).
    'd'     -> diagnóstico: prueba cada par en las dos orientaciones y dice
               cuál responde. Poné un objeto plano a ~20 cm de los tres.
               (En la orientación equivocada el Mega maneja unos 10 µs el pin
               de salida del módulo: es seguro para una prueba corta.)
    'o'     -> pasa a ordenado: un sensor a la vez, 20 ms entre disparos.
    'j'     -> pasa a seguido: los tres disparan uno atrás del otro, sin pausa
               (como el firmware de tests-emi), para ver si se pisan.
*/

const uint8_t NUM_US = 3;
const uint8_t US_TRIG[NUM_US] = {24, 26, 28};
const uint8_t US_ECHO[NUM_US] = {25, 27, 29};
const char *NOMBRE[NUM_US] = {"izq", "med", "der"};
const unsigned long TIMEOUT_US = 25000UL;  // ~4,3 m
const unsigned int PAUSA_ORDENADO_MS = 20;
const unsigned int PERIODO_MS = 100;

bool ordenado = true;

// 0 = sin eco
unsigned long pulso(uint8_t trig, uint8_t echo) {
  digitalWrite(trig, LOW);
  delayMicroseconds(2);
  digitalWrite(trig, HIGH);
  delayMicroseconds(10);
  digitalWrite(trig, LOW);
  return pulseIn(echo, HIGH, TIMEOUT_US);
}

void diagnosticar() {
  Serial.println(F("Diagnóstico (5 disparos por orientación):"));
  for (uint8_t i = 0; i < NUM_US; i++) {
    uint8_t a = US_TRIG[i], b = US_ECHO[i];
    uint8_t okNormal = 0, okCruzado = 0;
    pinMode(a, OUTPUT); pinMode(b, INPUT);
    for (uint8_t n = 0; n < 5; n++) { okNormal += pulso(a, b) > 0; delay(30); }
    pinMode(b, OUTPUT); pinMode(a, INPUT);
    for (uint8_t n = 0; n < 5; n++) { okCruzado += pulso(b, a) > 0; delay(30); }
    pinMode(a, OUTPUT); digitalWrite(a, LOW); pinMode(b, INPUT);

    Serial.print(NOMBRE[i]);
    Serial.print(F(" (pines "));
    Serial.print(a); Serial.print('/'); Serial.print(b);
    Serial.print(F("): TRIG="));
    Serial.print(a); Serial.print(F(" -> "));
    Serial.print(okNormal);
    Serial.print(F("/5 | TRIG="));
    Serial.print(b); Serial.print(F(" -> "));
    Serial.print(okCruzado);
    Serial.print(F("/5  =>  "));
    if (okNormal >= 3 && okCruzado < 3) Serial.println(F("OK, orientación normal"));
    else if (okCruzado >= 3 && okNormal < 3) Serial.println(F("CRUZADO: intercambiar TRIG y ECHO en el sketch"));
    else if (okNormal < 3 && okCruzado < 3) Serial.println(F("NO RESPONDE: revisar VCC/GND y cables"));
    else Serial.println(F("ambiguo: acercar un objeto plano y repetir"));
  }
}

void setup() {
  Serial.begin(115200);
  for (uint8_t i = 0; i < NUM_US; i++) {
    pinMode(US_TRIG[i], OUTPUT);
    digitalWrite(US_TRIG[i], LOW);
    pinMode(US_ECHO[i], INPUT);
  }
  Serial.println(F("Prueba de ultrasonidos (24/25, 26/27, 28/29). 'd' diagnóstico, 'o' ordenado, 'j' seguido"));
}

void loop() {
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'd') diagnosticar();
    if (c == 'o') { ordenado = true;  Serial.println(F("Modo ordenado (20 ms entre sensores)")); }
    if (c == 'j') { ordenado = false; Serial.println(F("Modo seguido (sin pausa)")); }
  }

  unsigned long t0 = millis();
  for (uint8_t i = 0; i < NUM_US; i++) {
    unsigned long us = pulso(US_TRIG[i], US_ECHO[i]);
    Serial.print(NOMBRE[i]);
    Serial.print(F(": "));
    if (us == 0) Serial.print(F("---   "));
    else { Serial.print(us * 0.0343 / 2.0, 1); Serial.print(F(" cm  ")); }
    if (ordenado) delay(PAUSA_ORDENADO_MS);
  }
  Serial.println();
  while (millis() - t0 < PERIODO_MS) {}
}
