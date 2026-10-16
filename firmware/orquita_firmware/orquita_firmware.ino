/**
 * 🐋 FIRMWARE CLIENTE OFICIAL: LA ORQUITA (KEIKO)
 * Plataforma: Arduino UNO R3 (Kit Rover 4WD Todomicro)
 * Asignaturas: Programación Sobre Redes & Desarrollo de Sistemas - Ciclo 2026
 * 
 * Funcionalidades:
 * 1. Muestreo periódico de 4 sensores ultrasónicos HC-SR04 (doble plano).
 * 2. Detección por hardware de captura física en cavidad bucal (Pin D2).
 * 3. Enlace inalámbrico Bluetooth RFCOMM/SPP (Módulo HC-05 en Serial D0/D1).
 * 4. Control de tracción diferencial 4WD (Driver L298N en D3, D4, D5, D6, D7, D9).
 * 5. FAIL-SAFE OBLIGATORIO: Watchdog por software de 500 ms.
 *    Si pasan > 500 ms sin recibir orden o heartbeat del Servidor,
 *    se cortan inmediatamente las señales PWM y se frenan los 4 motores.
 */

// =================================================================
// 1. ASIGNACIÓN DE PINES (PINOUT FÍSICO)
// =================================================================

// Driver L298N - Flanco Izquierdo
const int PIN_ENA = 3;  // PWM Velocidad Izquierda
const int PIN_IN1 = 4;  // Dirección Izquierda A
const int PIN_IN2 = 5;  // Dirección Izquierda B

// Driver L298N - Flanco Derecho
const int PIN_IN3 = 6;  // Dirección Derecha A
const int PIN_IN4 = 7;  // Dirección Derecha B
const int PIN_ENB = 9;  // PWM Velocidad Derecha

// Sensor Bucal de Captura (CNY70 con comparador o Microswitch fin de carrera)
const int PIN_CAPTURE = 2; // Activo en LOW (Pull-up interno)

// Sensores HC-SR04 Plano Inferior (Canicas / Lobitos)
const int PIN_TRIG_LOW_L = 8;
const int PIN_ECHO_LOW_L = 10;
const int PIN_TRIG_LOW_R = 11;
const int PIN_ECHO_LOW_R = 12;

// Sensores HC-SR04 Plano Superior (Muros y Obstáculos)
const int PIN_TRIG_HIGH_L = A0;
const int PIN_ECHO_HIGH_L = A1;
const int PIN_TRIG_HIGH_R = A2;
const int PIN_ECHO_HIGH_R = A3;

// =================================================================
// 2. CONSTANTES DE TIEMPO Y FAIL-SAFE
// =================================================================
const unsigned long FAILSAFE_TIMEOUT_MS = 500;  // 500 ms exactos por reglamento
const unsigned long SENSOR_SAMPLE_INTERVAL_MS = 40; // Muestreo cada 40 ms
const unsigned long US_TIMEOUT_MICROS = 18000;   // Timeout pulseIn (~300 cm máx)

// =================================================================
// 3. VARIABLES DE ESTADO
// =================================================================
unsigned long lastCommandMillis = 0;
unsigned long lastSampleMillis = 0;
bool failSafeActive = true;
String rxBuffer = "";

// Prototipos de funciones
float readUltrasonic(int trigPin, int echoPin);
void setMotors(float vLeft, float vRight);
void stopMotors();
void processIncomingCommand(String cmd);

void setup() {
  // Comunicación Bluetooth HC-05 (baudrate por defecto del módulo)
  Serial.begin(38400);

  // Configuración de pines de tracción motriz
  pinMode(PIN_ENA, OUTPUT);
  pinMode(PIN_IN1, OUTPUT);
  pinMode(PIN_IN2, OUTPUT);
  pinMode(PIN_IN3, OUTPUT);
  pinMode(PIN_IN4, OUTPUT);
  pinMode(PIN_ENB, OUTPUT);

  // Configuración de sensor bucal
  pinMode(PIN_CAPTURE, INPUT_PULLUP);

  // Configuración de sensores ultrasónicos
  pinMode(PIN_TRIG_LOW_L, OUTPUT);
  pinMode(PIN_ECHO_LOW_L, INPUT);
  pinMode(PIN_TRIG_LOW_R, OUTPUT);
  pinMode(PIN_ECHO_LOW_R, INPUT);

  pinMode(PIN_TRIG_HIGH_L, OUTPUT);
  pinMode(PIN_ECHO_HIGH_L, INPUT);
  pinMode(PIN_TRIG_HIGH_R, OUTPUT);
  pinMode(PIN_ECHO_HIGH_R, INPUT);

  // Estado seguro inicial: motores completamente apagados
  stopMotors();
  lastCommandMillis = millis();
}

void loop() {
  unsigned long currentMillis = millis();

  // -------------------------------------------------------------
  // A. RECEPCIÓN DE MENSAJES DESDE EL SERVIDOR CENTRAL (RFCOMM/SPP)
  // -------------------------------------------------------------
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (rxBuffer.length() > 0) {
        processIncomingCommand(rxBuffer);
        rxBuffer = "";
      }
    } else {
      if (rxBuffer.length() < 64) {
        rxBuffer += c;
      }
    }
  }

  // -------------------------------------------------------------
  // B. WATCHDOG FAIL-SAFE (< 500 ms) - SEGURIDAD CRÍTICA
  // -------------------------------------------------------------
  if ((currentMillis - lastCommandMillis) > FAILSAFE_TIMEOUT_MS) {
    if (!failSafeActive) {
      failSafeActive = true;
      stopMotors();
      Serial.println(F("ERROR:FAILSAFE_TRIGGERED"));
    }
  }

  // -------------------------------------------------------------
  // C. DETECCIÓN DE CAPTURA FÍSICA EN CAVIDAD BUCAL
  // -------------------------------------------------------------
  static unsigned long lastCaptureDebounce = 0;
  if (digitalRead(PIN_CAPTURE) == LOW) { // Contacto detectado
    if (currentMillis - lastCaptureDebounce > 1000) { // 1 seg debounce
      lastCaptureDebounce = currentMillis;
      Serial.println(F("EVENT:CAPTURA"));
    }
  }

  // -------------------------------------------------------------
  // D. MUESTREO SENSORIAL Y TRANSMISIÓN DE TELEMETRÍA
  // -------------------------------------------------------------
  if (currentMillis - lastSampleMillis >= SENSOR_SAMPLE_INTERVAL_MS) {
    lastSampleMillis = currentMillis;

    float d_ll = readUltrasonic(PIN_TRIG_LOW_L, PIN_ECHO_LOW_L);
    float d_lr = readUltrasonic(PIN_TRIG_LOW_R, PIN_ECHO_LOW_R);
    float d_hl = readUltrasonic(PIN_TRIG_HIGH_L, PIN_ECHO_HIGH_L);
    float d_hr = readUltrasonic(PIN_TRIG_HIGH_R, PIN_ECHO_HIGH_R);

    // Formato acordado: TELEMETRY:<d_ll>,<d_lr>,<d_hl>,<d_hr>,<timestamp>\n
    Serial.print(F("TELEMETRY:"));
    Serial.print(d_ll, 1);
    Serial.print(F(","));
    Serial.print(d_lr, 1);
    Serial.print(F(","));
    Serial.print(d_hl, 1);
    Serial.print(F(","));
    Serial.print(d_hr, 1);
    Serial.print(F(","));
    Serial.println(currentMillis);
  }
}

/**
 * Lee la distancia en centímetros de un sensor HC-SR04 con timeout.
 */
float readUltrasonic(int trigPin, int echoPin) {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  unsigned long duration = pulseIn(echoPin, HIGH, US_TIMEOUT_MICROS);
  if (duration == 0) {
    return 300.0; // Distancia máxima / sin eco
  }
  float dist = (duration * 0.0343) / 2.0;
  if (dist < 2.0) return 2.0;
  if (dist > 300.0) return 300.0;
  return dist;
}

/**
 * Interpreta una trama recibida desde el Servidor Central.
 */
void processIncomingCommand(String cmd) {
  cmd.trim();
  if (cmd.startsWith("COMMAND:")) {
    // COMMAND:<v_left>,<v_right>,<estado>
    int firstComma = cmd.indexOf(',', 8);
    if (firstComma > 8) {
      int secondComma = cmd.indexOf(',', firstComma + 1);
      String sVl = cmd.substring(8, firstComma);
      String sVr = (secondComma > 0) ? cmd.substring(firstComma + 1, secondComma) : cmd.substring(firstComma + 1);
      
      float vLeft = sVl.toFloat();
      float vRight = sVr.toFloat();

      setMotors(vLeft, vRight);
      lastCommandMillis = millis();
      failSafeActive = false;
    }
  } else if (cmd.equals("HEARTBEAT")) {
    lastCommandMillis = millis();
    failSafeActive = false;
    Serial.println(F("ACK"));
  }
}

/**
 * Controla los 4 motores DC del chasis Skid-Steer.
 * Mapea velocidad angular [-8.0 .. 8.0] a PWM [0 .. 255] y sentidos IN1..IN4.
 */
void setMotors(float vLeft, float vRight) {
  // Flanco Izquierdo
  int pwmLeft = constrain(abs(vLeft) * (255.0 / 8.0), 0, 255);
  if (vLeft > 0.1) {
    digitalWrite(PIN_IN1, HIGH);
    digitalWrite(PIN_IN2, LOW);
  } else if (vLeft < -0.1) {
    digitalWrite(PIN_IN1, LOW);
    digitalWrite(PIN_IN2, HIGH);
  } else {
    digitalWrite(PIN_IN1, LOW);
    digitalWrite(PIN_IN2, LOW);
    pwmLeft = 0;
  }
  analogWrite(PIN_ENA, pwmLeft);

  // Flanco Derecho
  int pwmRight = constrain(abs(vRight) * (255.0 / 8.0), 0, 255);
  if (vRight > 0.1) {
    digitalWrite(PIN_IN3, HIGH);
    digitalWrite(PIN_IN4, LOW);
  } else if (vRight < -0.1) {
    digitalWrite(PIN_IN3, LOW);
    digitalWrite(PIN_IN4, HIGH);
  } else {
    digitalWrite(PIN_IN3, LOW);
    digitalWrite(PIN_IN4, LOW);
    pwmRight = 0;
  }
  analogWrite(PIN_ENB, pwmRight);
}

/**
 * Frena inmediatamente los 4 motores.
 */
void stopMotors() {
  analogWrite(PIN_ENA, 0);
  analogWrite(PIN_ENB, 0);
  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, LOW);
}
