/*
  Configuración del HC-05 por comandos AT, desde el Mega.

  Conexiones (las mismas de prueba-hc05):
    HC-05 VCC -> 5V, GND -> GND, TXD -> D19 (RX1), RXD -> D18 (TX1)

  Para entrar en modo AT:
    1. Desenchufar el VCC del HC-05.
    2. Mantener apretado el botoncito del módulo y volver a enchufar el VCC.
    3. Soltarlo cuando el LED parpadee LENTO (una vez cada ~2 s).
  En modo AT el HC-05 habla siempre a 38400 baudios.

  Uso: monitor serie a 115200 (cualquier fin de línea). Lo que se escribe se
  manda al HC-05 terminado en \r\n, y su respuesta aparece en el monitor.
  Mientras no conteste, el sketch le manda "AT" cada 2 s y avisa.

  Comandos útiles:
    AT                  -> OK (está en modo AT)
    AT+VERSION?         -> versión del firmware
    AT+NAME?            -> nombre actual
    AT+NAME=ORQUITA     -> cambia el nombre
    AT+UART?            -> velocidad del modo datos (ej. 9600,0,0)
    AT+UART=9600,0,0    -> velocidad del modo datos a 9600
    AT+PSWD=nuevoPIN    -> cambia el PIN (algunas versiones piden comillas:
                           AT+PSWD="nuevoPIN")
    AT+ORGL             -> vuelve todo a fábrica
    AT+RESET            -> sale del modo AT y reinicia en modo datos
*/

const unsigned long USB_BAUDIOS = 115200;
const unsigned long AT_BAUDIOS = 38400;
const unsigned long PERIODO_SONDEO_MS = 2000;

bool enModoAt = false;
unsigned long ultimoSondeo = 0;
char respuesta[64];
uint8_t largoRespuesta = 0;

void setup() {
  Serial.begin(USB_BAUDIOS);
  Serial1.begin(AT_BAUDIOS);
  Serial.println(F("Configuración HC-05 por AT. Esperando que el módulo esté en modo AT..."));
}

void loop() {
  // USB -> HC-05, normalizando el fin de línea a \r\n
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') {
      continue;
    }
    if (c == '\n') {
      Serial1.print(F("\r\n"));
    } else {
      Serial1.write(c);
    }
  }

  // HC-05 -> USB, y detecta el primer OK para saber que está en modo AT
  while (Serial1.available()) {
    char c = Serial1.read();
    Serial.write(c);
    if (!enModoAt) {
      if (c == '\n' || largoRespuesta >= sizeof(respuesta) - 1) {
        respuesta[largoRespuesta] = '\0';
        if (strstr(respuesta, "OK") != NULL) {
          enModoAt = true;
          Serial.println(F("\n>> El HC-05 está en modo AT. Ya podés escribir comandos."));
        }
        largoRespuesta = 0;
      } else if (c != '\r') {
        respuesta[largoRespuesta++] = c;
      }
    }
  }

  if (!enModoAt && millis() - ultimoSondeo >= PERIODO_SONDEO_MS) {
    ultimoSondeo = millis();
    Serial.println(F("(sin respuesta a AT: ¿está el LED parpadeando lento?)"));
    Serial1.print(F("AT\r\n"));
  }
}
