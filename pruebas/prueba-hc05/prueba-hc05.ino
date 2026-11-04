/*
  Prueba del HC-05 en el Mega: puente entre el USB (Serial) y el Bluetooth
  (Serial1), y aviso cuando cambia el pin STATE.

  Conexiones:
    HC-05 VCC -> 5V, GND -> GND
    HC-05 TXD -> D19 (RX1)
    HC-05 RXD -> D18 (TX1)
    HC-05 STATE -> D30 (HIGH = hay enlace)

  Uso: abrir el monitor serie a 115200. Lo que se escribe ahí sale por
  Bluetooth y lo que llega por Bluetooth aparece ahí. Si llegan caracteres
  raros, cambiar BT_BAUDIOS (9600 de fábrica; algunos vienen en 38400).
  Además, cada 2 s manda por Bluetooth un "PING n" para ver si llega a la PC.
*/

const unsigned long USB_BAUDIOS = 115200;
const unsigned long BT_BAUDIOS = 9600;
const uint8_t PIN_STATE = 30;
const unsigned long PERIODO_PING_MS = 2000;

bool enlaceAnterior = false;
unsigned long ultimoPing = 0;
unsigned long numPing = 0;

void setup() {
  Serial.begin(USB_BAUDIOS);
  Serial1.begin(BT_BAUDIOS);
  pinMode(PIN_STATE, INPUT);
  enlaceAnterior = digitalRead(PIN_STATE);
  Serial.print(F("Prueba HC-05 lista. Bluetooth a "));
  Serial.print(BT_BAUDIOS);
  Serial.print(F(" baudios. STATE inicial: "));
  Serial.println(enlaceAnterior ? F("CONECTADO") : F("sin enlace"));
}

void loop() {
  while (Serial.available()) {
    Serial1.write(Serial.read());
  }
  while (Serial1.available()) {
    Serial.write(Serial1.read());
  }

  bool enlace = digitalRead(PIN_STATE);
  if (enlace != enlaceAnterior) {
    enlaceAnterior = enlace;
    Serial.println(enlace ? F("\n[STATE] CONECTADO") : F("\n[STATE] enlace perdido"));
  }

  if (millis() - ultimoPing >= PERIODO_PING_MS) {
    ultimoPing = millis();
    Serial1.print(F("PING "));
    Serial1.println(numPing++);
  }
}
