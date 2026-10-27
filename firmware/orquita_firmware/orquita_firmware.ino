/**
 * 🐋 FIRMWARE CLIENTE OFICIAL: LA ORQUITA (KEIKO)
 * Plataforma: Arduino UNO R3 (ATmega328P) + Kit Rover 4WD Todomicro (Chasis acrílico 2 pisos)
 * Asignaturas: Programación Sobre Redes & Desarrollo de Sistemas - Ciclo 2026
 * 
 * Funcionalidades y Especificaciones de Ingeniería:
 * 1. Muestreo periódico y validación de frontera de transductores HC-SR04 (RF-01).
 *    Filtro de mediana de ventana corta (3 muestras) y suavizado exponencial rápido (EMA alpha=0.65)
 *    para eliminar ruido acústico, reverberación y diafonía (crosstalk).
 * 2. Soporte para topología canónica física de 4 sensores (doble plano L/R) y modo extendido de 6 sensores.
 * 3. Detección por hardware de captura física en cavidad bucal (Pin D2, activo en LOW con debounce).
 * 4. Enlace inalámbrico Bluetooth RFCOMM/SPP (Módulo HC-05 en Serial D0/D1 a 38400 baudios).
 * 5. Control de tracción diferencial 4WD Skid-Steer (Driver L298N en D3, D4, D5, D6, D7, D9)
 *    con compensación de zona muerta (deadband) para permitir giros suaves por arco sin calado motor.
 * 6. FAIL-SAFE OBLIGATORIO (< 500 ms): Watchdog estricto de seguridad.
 *    Si pasan > 500 ms sin recibir orden o heartbeat del Servidor Cerebro,
 *    se cortan inmediatamente las señales PWM y se frenan los 4 motores en reposo seguro.
 * 7. Sincronización de Máquina de Estados Finita (FSM):
 *    Recepción de COMMAND (vL, vR, estado), STATE_CHANGE (ESCAPE), ACK de captura y HEARTBEAT.
 * 8. Señalización óptica de estado en tiempo real mediante LED integrado (Pin D13):
 *    - Apagado: Fail-Safe activo / pérdida de enlace.
 *    - Parpadeo lento (1 Hz): Modo BÚSQUEDA activo.
 *    - Parpadeo rápido (4 Hz): Mutación conductual a Modo ESCAPE verificada.
 *    - Encendido fijo: Misión completada (FINALIZACIÓN).
 */

// =================================================================
// 0. CONFIGURACIÓN DEL SISTEMA Y TOPOLOGÍA SENSORIAL
// =================================================================
// Seleccionar cantidad de canales ultrasónicos HC-SR04 a muestrear y transmitir:
//   4 = Configuración física estándar (2 bajos L/R + 2 altos L/R en extremos)
//   6 = Configuración extendida trisector (2 bajos L/R + 2 altos L/R + 2 centrales)
#define SENSOR_COUNT 4

// Baudrate del módulo Bluetooth HC-05 (38400 por defecto en modo SPP / 9600 común)
#define BT_BAUD_RATE 38400

// =================================================================
// 1. ASIGNACIÓN DE PINES (PINOUT FÍSICO ARDUINO UNO R3)
// =================================================================

// Driver L298N - Flanco Izquierdo
const int PIN_ENA = 3;  // PWM Velocidad Flanco Izquierdo (Timer 2)
const int PIN_IN1 = 4;  // Sentido Izquierda A
const int PIN_IN2 = 5;  // Sentido Izquierda B

// Driver L298N - Flanco Derecho
const int PIN_IN3 = 6;  // Sentido Derecha A
const int PIN_IN4 = 7;  // Sentido Derecha B
const int PIN_ENB = 9;  // PWM Velocidad Flanco Derecho (Timer 1)

// Sensor Bucal de Captura (CNY70 con comparador o Microswitch fin de carrera)
const int PIN_CAPTURE = 2; // Activo en LOW (Pull-up interno)

// Sensores HC-SR04 Plano Inferior (Detección de presas biológicas a Z = 2.5 cm)
const int PIN_TRIG_LOW_L = 8;
const int PIN_ECHO_LOW_L = 10;
const int PIN_TRIG_LOW_R = 11;
const int PIN_ECHO_LOW_R = 12;

// Sensores HC-SR04 Plano Superior (Detección de muros y obstáculos a Z = 9.5 cm)
const int PIN_TRIG_HIGH_L = A0; // GPIO analógico usado como digital
const int PIN_ECHO_HIGH_L = A1;
const int PIN_TRIG_HIGH_R = A2;
const int PIN_ECHO_HIGH_R = A3;

#if SENSOR_COUNT == 6
// Sensores HC-SR04 Centrales (Configuración opcional Trisector)
const int PIN_TRIG_LOW_C  = A4;
const int PIN_ECHO_LOW_C  = A5;
const int PIN_TRIG_HIGH_C = A4; // Puede compartir disparo con LOW_C
const int PIN_ECHO_HIGH_C = 13; // O pin digital auxiliar
#endif

// LED Indicador de Estado Integrado
const int PIN_LED_STATUS = 13;

// =================================================================
// 2. CONSTANTES DE TIEMPO, RANGOS Y FAIL-SAFE
// =================================================================
const unsigned long FAILSAFE_TIMEOUT_MS = 500;        // 500 ms exactos por reglamento
const unsigned long SENSOR_SAMPLE_INTERVAL_MS = 40;   // Intervalo de muestreo y telemetría (40 ms = 25 Hz)
const unsigned long CAPTURE_DEBOUNCE_MS = 1200;       // 1.2 s de debounce para contacto bucal
const unsigned long US_TIMEOUT_MICROS = 14500;         // Timeout pulseIn (~250 cm máx, evita bloqueos largos)
const unsigned long INTER_PING_DELAY_MICROS = 1200;    // Pausa anti-diafonía (crosstalk acústico) entre sensores

const float MIN_RANGO_VALIDO_CM = 2.0;                // Cota física inferior HC-SR04
const float MAX_RANGO_VALIDO_CM = 250.0;              // Cota superior de alcance efectivo en la pista
const float MAX_VEL_COMANDO = 8.0;                    // Velocidad angular máxima enviada por el Servidor (rad/s)
const int PWM_MIN_DEADBAND = 55;                      // PWM mínimo para vencer inercia/fricción de motores TT 1:48

// =================================================================
// 3. ESTRUCTURA Y FILTRO DE FRONTERA (SensorBorderFilter)
// =================================================================
/**
 * Filtro de frontera en tiempo real:
 * - Filtro de mediana de ventana móvil (3 muestras) para rechazar picos espurios y rebotes.
 * - Suavizado exponencial (EMA alpha=0.65) para atenuar ruido blanco de medición.
 */
struct SensorFilter {
  float window[3];
  byte head;
  bool initialized;
  float ema;

  void init() {
    initialized = false;
    head = 0;
    ema = MAX_RANGO_VALIDO_CM;
    window[0] = MAX_RANGO_VALIDO_CM;
    window[1] = MAX_RANGO_VALIDO_CM;
    window[2] = MAX_RANGO_VALIDO_CM;
  }

  float update(float raw) {
    // Validación de frontera física [2.0, 250.0] cm
    if (raw < MIN_RANGO_VALIDO_CM) raw = MIN_RANGO_VALIDO_CM;
    if (raw > MAX_RANGO_VALIDO_CM) raw = MAX_RANGO_VALIDO_CM;

    if (!initialized) {
      window[0] = raw;
      window[1] = raw;
      window[2] = raw;
      ema = raw;
      initialized = true;
      return raw;
    }

    window[head] = raw;
    head = (head + 1) % 3;

    // Mediana rápida de 3 valores
    float a = window[0];
    float b = window[1];
    float c = window[2];
    float median = (a > b) ? ((b > c) ? b : ((a > c) ? c : a))
                           : ((a > c) ? a : ((b > c) ? c : b));

    // Suavizado EMA con alpha = 0.65
    ema = 0.65f * median + 0.35f * ema;
    return ema;
  }
};

// Instancias de filtro para cada sensor
SensorFilter filterLowLeft;
SensorFilter filterLowRight;
SensorFilter filterHighLeft;
SensorFilter filterHighRight;

#if SENSOR_COUNT == 6
SensorFilter filterLowCenter;
SensorFilter filterHighCenter;
#endif

// =================================================================
// 4. VARIABLES DE ESTADO Y COMUNICACIÓN
// =================================================================
unsigned long lastCommandMillis = 0;
unsigned long lastSampleMillis = 0;
unsigned long lastCaptureDebounce = 0;
bool failSafeActive = true;
String currentEstado = "BUSQUEDA";
int capturasConfirmadas = 0;
String rxBuffer = "";

// Prototipos de funciones auxiliares
float readUltrasonic(int trigPin, int echoPin);
int computePWM(float velocity);
void setMotors(float vLeft, float vRight);
void stopMotors();
void processIncomingCommand(String cmd);
void sampleAndTransmitSensors(unsigned long currentMillis);
void updateStatusLED(unsigned long currentMillis);

// =================================================================
// 5. CONFIGURACIÓN INICIAL (SETUP)
// =================================================================
void setup() {
  // Comunicación Bluetooth HC-05 (SPP Serie)
  Serial.begin(BT_BAUD_RATE);

  // Inicializar filtros de validación de frontera
  filterLowLeft.init();
  filterLowRight.init();
  filterHighLeft.init();
  filterHighRight.init();
#if SENSOR_COUNT == 6
  filterLowCenter.init();
  filterHighCenter.init();
#endif

  // Pines de tracción motriz L298N
  pinMode(PIN_ENA, OUTPUT);
  pinMode(PIN_IN1, OUTPUT);
  pinMode(PIN_IN2, OUTPUT);
  pinMode(PIN_IN3, OUTPUT);
  pinMode(PIN_IN4, OUTPUT);
  pinMode(PIN_ENB, OUTPUT);

  // Sensor bucal de captura física
  pinMode(PIN_CAPTURE, INPUT_PULLUP);

  // Pines de sensores ultrasónicos
  pinMode(PIN_TRIG_LOW_L, OUTPUT);
  pinMode(PIN_ECHO_LOW_L, INPUT);
  pinMode(PIN_TRIG_LOW_R, OUTPUT);
  pinMode(PIN_ECHO_LOW_R, INPUT);

  pinMode(PIN_TRIG_HIGH_L, OUTPUT);
  pinMode(PIN_ECHO_HIGH_L, INPUT);
  pinMode(PIN_TRIG_HIGH_R, OUTPUT);
  pinMode(PIN_ECHO_HIGH_R, INPUT);

#if SENSOR_COUNT == 6
  pinMode(PIN_TRIG_LOW_C, OUTPUT);
  pinMode(PIN_ECHO_LOW_C, INPUT);
  pinMode(PIN_TRIG_HIGH_C, OUTPUT);
  pinMode(PIN_ECHO_HIGH_C, INPUT);
#endif

  // LED de estado
  pinMode(PIN_LED_STATUS, OUTPUT);
  digitalWrite(PIN_LED_STATUS, LOW);

  // Estado seguro inicial: motores completamente detenidos
  stopMotors();
  lastCommandMillis = millis();
}

// =================================================================
// 6. BUCLE PRINCIPAL CONCURRENTE (LOOP)
// =================================================================
void loop() {
  unsigned long currentMillis = millis();

  // -------------------------------------------------------------
  // A. RECEPCIÓN DE MENSAJES DESDE EL SERVIDOR (DELIMITADOS POR \n)
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
  // B. WATCHDOG FAIL-SAFE (< 500 ms) - REQUISITO REGLAMENTARIO
  // -------------------------------------------------------------
  if ((currentMillis - lastCommandMillis) > FAILSAFE_TIMEOUT_MS) {
    if (!failSafeActive) {
      failSafeActive = true;
      stopMotors();
      Serial.println(F("ERROR:FAILSAFE_TIMEOUT"));
    }
  }

  // -------------------------------------------------------------
  // C. DETECCIÓN DE CAPTURA FÍSICA EN CAVIDAD BUCAL
  // -------------------------------------------------------------
  if (digitalRead(PIN_CAPTURE) == LOW) { // Contacto verificado
    if (currentMillis - lastCaptureDebounce > CAPTURE_DEBOUNCE_MS) {
      lastCaptureDebounce = currentMillis;
      Serial.println(F("EVENT:CAPTURA"));
    }
  }

  // -------------------------------------------------------------
  // D. MUESTREO SENSORIAL, FILTRADO Y TRANSMISIÓN DE TELEMETRÍA
  // -------------------------------------------------------------
  if (currentMillis - lastSampleMillis >= SENSOR_SAMPLE_INTERVAL_MS) {
    lastSampleMillis = currentMillis;
    sampleAndTransmitSensors(currentMillis);
  }

  // -------------------------------------------------------------
  // E. SEÑALIZACIÓN ÓPTICA DEL ESTADO DEL SISTEMA (LED PIN 13)
  // -------------------------------------------------------------
  updateStatusLED(currentMillis);
}

// =================================================================
// 7. IMPLEMENTACIÓN DE FUNCIONES AUXILIARES
// =================================================================

/**
 * Muestrea los sensores con pausas anti-crosstalk, filtra ruido
 * y emite la trama de telemetría delimitada hacia el Servidor.
 */
void sampleAndTransmitSensors(unsigned long currentMillis) {
  // Muestreo con separación temporal anti-crosstalk acústico
  float raw_ll = readUltrasonic(PIN_TRIG_LOW_L, PIN_ECHO_LOW_L);
  delayMicroseconds(INTER_PING_DELAY_MICROS);
  float raw_lr = readUltrasonic(PIN_TRIG_LOW_R, PIN_ECHO_LOW_R);
  delayMicroseconds(INTER_PING_DELAY_MICROS);
  float raw_hl = readUltrasonic(PIN_TRIG_HIGH_L, PIN_ECHO_HIGH_L);
  delayMicroseconds(INTER_PING_DELAY_MICROS);
  float raw_hr = readUltrasonic(PIN_TRIG_HIGH_R, PIN_ECHO_HIGH_R);

  // Aplicar validación de frontera y filtro acústico
  float d_ll = filterLowLeft.update(raw_ll);
  float d_lr = filterLowRight.update(raw_lr);
  float d_hl = filterHighLeft.update(raw_hl);
  float d_hr = filterHighRight.update(raw_hr);

#if SENSOR_COUNT == 6
  delayMicroseconds(INTER_PING_DELAY_MICROS);
  float raw_lc = readUltrasonic(PIN_TRIG_LOW_C, PIN_ECHO_LOW_C);
  delayMicroseconds(INTER_PING_DELAY_MICROS);
  float raw_hc = readUltrasonic(PIN_TRIG_HIGH_C, PIN_ECHO_HIGH_C);

  float d_lc = filterLowCenter.update(raw_lc);
  float d_hc = filterHighCenter.update(raw_hc);

  // Formato: TELEMETRY:<ll>,<lc>,<lr>,<hl>,<hc>,<hr>,<timestamp>\n
  Serial.print(F("TELEMETRY:"));
  Serial.print(d_ll, 1);
  Serial.print(F(","));
  Serial.print(d_lc, 1);
  Serial.print(F(","));
  Serial.print(d_lr, 1);
  Serial.print(F(","));
  Serial.print(d_hl, 1);
  Serial.print(F(","));
  Serial.print(d_hc, 1);
  Serial.print(F(","));
  Serial.print(d_hr, 1);
  Serial.print(F(","));
  Serial.println(currentMillis);
#else
  // Formato: TELEMETRY:<ll>,<lr>,<hl>,<hr>,<timestamp>\n
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
#endif
}

/**
 * Lee la distancia en centímetros de un sensor HC-SR04 con timeout.
 * Retorna MAX_RANGO_VALIDO_CM si no hay eco reflejado.
 */
float readUltrasonic(int trigPin, int echoPin) {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  unsigned long duration = pulseIn(echoPin, HIGH, US_TIMEOUT_MICROS);
  if (duration == 0) {
    return MAX_RANGO_VALIDO_CM; // Sin eco / espacio despejado
  }
  float dist = (duration * 0.0343f) / 2.0f;
  if (dist < MIN_RANGO_VALIDO_CM) return MIN_RANGO_VALIDO_CM;
  if (dist > MAX_RANGO_VALIDO_CM) return MAX_RANGO_VALIDO_CM;
  return dist;
}

/**
 * Interpreta tramas recibidas desde el Servidor Central (RFCOMM/SPP).
 * Soporta COMMAND, STATE_CHANGE, ACK y HEARTBEAT.
 */
void processIncomingCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  if (cmd.startsWith("COMMAND:")) {
    // COMMAND:<v_left>,<v_right>,<estado>
    int firstComma = cmd.indexOf(',', 8);
    if (firstComma > 8) {
      int secondComma = cmd.indexOf(',', firstComma + 1);
      String sVl = cmd.substring(8, firstComma);
      String sVr = (secondComma > 0) ? cmd.substring(firstComma + 1, secondComma) : cmd.substring(firstComma + 1);

      if (secondComma > 0) {
        currentEstado = cmd.substring(secondComma + 1);
        currentEstado.trim();
      }

      float vLeft = sVl.toFloat();
      float vRight = sVr.toFloat();

      setMotors(vLeft, vRight);
      lastCommandMillis = millis();
      failSafeActive = false;
    }
  } else if (cmd.startsWith("STATE_CHANGE:")) {
    // Notificación formal de mutación conductual emitida por el Servidor (ej. "ESCAPE")
    currentEstado = cmd.substring(13);
    currentEstado.trim();
    lastCommandMillis = millis();
    failSafeActive = false;
  } else if (cmd.startsWith("ACK:")) {
    // Confirmación de ingesta persistida en estomago.txt (ej. "ACK:CAPTURA_1")
    lastCommandMillis = millis();
    failSafeActive = false;
    if (cmd.startsWith("ACK:CAPTURA_")) {
      capturasConfirmadas = cmd.substring(12).toInt();
    }
  } else if (cmd.equals("HEARTBEAT")) {
    // Latido de enlace para reset de watchdog liveness
    lastCommandMillis = millis();
    failSafeActive = false;
  }
}

/**
 * Mapea velocidad angular [-8.0 .. 8.0] a PWM [0 .. 255]
 * con compensación de zona muerta (deadband) para motores DC TT 1:48.
 */
int computePWM(float velocity) {
  float vAbs = abs(velocity);
  if (vAbs < 0.05f) {
    return 0;
  }
  // Mapeo no lineal con base de fricción estática
  int pwm = PWM_MIN_DEADBAND + (int)((vAbs / MAX_VEL_COMANDO) * (255 - PWM_MIN_DEADBAND));
  return constrain(pwm, 0, 255);
}

/**
 * Controla los 4 motores DC en configuración Skid-Steer.
 */
void setMotors(float vLeft, float vRight) {
  // Flanco Izquierdo
  int pwmLeft = computePWM(vLeft);
  if (vLeft > 0.05f) {
    digitalWrite(PIN_IN1, HIGH);
    digitalWrite(PIN_IN2, LOW);
  } else if (vLeft < -0.05f) {
    digitalWrite(PIN_IN1, LOW);
    digitalWrite(PIN_IN2, HIGH);
  } else {
    digitalWrite(PIN_IN1, LOW);
    digitalWrite(PIN_IN2, LOW);
    pwmLeft = 0;
  }
  analogWrite(PIN_ENA, pwmLeft);

  // Flanco Derecho
  int pwmRight = computePWM(vRight);
  if (vRight > 0.05f) {
    digitalWrite(PIN_IN3, HIGH);
    digitalWrite(PIN_IN4, LOW);
  } else if (vRight < -0.05f) {
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
 * Frena inmediatamente los 4 motores en reposo seguro.
 */
void stopMotors() {
  analogWrite(PIN_ENA, 0);
  analogWrite(PIN_ENB, 0);
  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, LOW);
}

/**
 * Actualiza la señalización del LED de estado según la FSM del robot.
 */
void updateStatusLED(unsigned long currentMillis) {
  if (failSafeActive) {
    digitalWrite(PIN_LED_STATUS, LOW); // Apagado en Fail-Safe / desconexión
  } else if (currentEstado.equals("ESCAPE")) {
    // Parpadeo rápido (4 Hz = periodo 250 ms) en modo escape
    bool ledOn = (currentMillis % 250) < 125;
    digitalWrite(PIN_LED_STATUS, ledOn ? HIGH : LOW);
  } else if (currentEstado.equals("FINALIZACION")) {
    digitalWrite(PIN_LED_STATUS, HIGH); // Encendido continuo al completar la misión
  } else {
    // Parpadeo suave (1 Hz = periodo 1000 ms) en búsqueda normal
    bool ledOn = (currentMillis % 1000) < 500;
    digitalWrite(PIN_LED_STATUS, ledOn ? HIGH : LOW);
  }
}
