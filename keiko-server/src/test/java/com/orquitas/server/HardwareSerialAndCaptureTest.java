package com.orquitas.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica el flujo de hardware físico sobre streams serie/Bluetooth (sin Socket TCP):
 * - Recepción de TELEMETRY 3US+2Laser y emisión de COMMAND
 * - Conteo de canicas desde EVENT:CAPTURA del láser del estómago con cooldown anti-rebote y tope en 3
 * - Emisión periódica de HEARTBEAT (< 500 ms) para mantener vivo el watchdog del Arduino
 * - Parseo de argumentos CLI --serial y --baud
 */
public class HardwareSerialAndCaptureTest {

    private File tempEstomago;
    private GestorEstomago gestorEstomago;

    @BeforeEach
    public void setUp() throws IOException {
        tempEstomago = File.createTempFile("estomago_hw_serial_test_", ".txt");
        if (tempEstomago.exists()) tempEstomago.delete();
        gestorEstomago = new GestorEstomago(tempEstomago);
    }

    @AfterEach
    public void tearDown() {
        if (tempEstomago != null && tempEstomago.exists()) {
            tempEstomago.delete();
        }
    }

    @Test
    public void testStreamSessionCountsMarblesWithDebounceAndTriggersEscape() throws Exception {
        PipedOutputStream arduinoTx = new PipedOutputStream();
        PipedInputStream serverRx = new PipedInputStream(arduinoTx);
        PipedOutputStream serverTx = new PipedOutputStream();
        PipedInputStream arduinoRx = new PipedInputStream(serverTx);

        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(new ConfiguracionServidor().resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);

        // Cooldown de 180 ms y heartbeat cada 120 ms para test rápido y determinista
        ManejadorCliente sesionSerie = new ManejadorCliente(
                serverRx, serverTx, "Serial:/dev/rfcomm0", () -> {},
                gestorEstomago, clasificador, planificador, 180L, 120L);

        CountDownLatch escapeLatch = new CountDownLatch(1);
        AuditorEstomago auditor = new AuditorEstomago(gestorEstomago, 25, 3);
        auditor.agregarOyente(new OyenteAuditor() {
            @Override
            public void onCapturaAuditada(int total) {}

            @Override
            public void onModoEscapeActivado(List<RegistroCaptura> capturas) {
                planificador.activarModoEscape();
                sesionSerie.enviarMensaje(MensajeProtocolo.crearModoEscape());
                escapeLatch.countDown();
            }
        });

        Thread serverThread = new Thread(sesionSerie, "Test-SerialSession");
        serverThread.start();
        auditor.iniciar();

        BufferedWriter toServer = new BufferedWriter(new OutputStreamWriter(arduinoTx, StandardCharsets.UTF_8));
        BufferedReader fromServer = new BufferedReader(new InputStreamReader(arduinoRx, StandardCharsets.UTF_8));

        try {
            // 1. Verificar HEARTBEAT automático del servidor cuando la línea está en silencio
            String firstLine = fromServer.readLine();
            assertEquals("HEARTBEAT", firstLine, "El servidor debe enviar HEARTBEAT periódico para sostener el fail-safe");

            // 2. Enviar telemetría con canica al centro -> debe responder COMMAND con APROXIMACION
            toServer.write("TELEMETRY:90.0,95.0,85.0,12.0,12.0,0.0,1000\n");
            toServer.flush();

            String cmdLine = leerHastaPrefijo(fromServer, "COMMAND:");
            assertTrue(cmdLine.contains("APROXIMACION"), "Debe responder COMMAND en APROXIMACION, recibió: " + cmdLine);

            // 3. Captura #1 + rebote espurio inmediato (30 ms después < 180 ms cooldown)
            toServer.write("EVENT:CAPTURA_LOBITO_1\n");
            toServer.flush();
            Thread.sleep(30);
            toServer.write("EVENT:CAPTURA_REBOTE_ESPURIO\n");
            toServer.flush();
            Thread.sleep(60);
            assertEquals(1, gestorEstomago.contarCapturasValidas(), "El rebote dentro del cooldown no debe duplicar la captura");

            // 4. Captura #2 tras vencer el cooldown
            Thread.sleep(160);
            toServer.write("EVENT:CAPTURA_LOBITO_2\n");
            toServer.flush();
            Thread.sleep(60);
            assertEquals(2, gestorEstomago.contarCapturasValidas());

            // 5. Captura #3 tras vencer el cooldown -> dispara STATE_CHANGE:ESCAPE
            Thread.sleep(160);
            toServer.write("EVENT:CAPTURA_LOBITO_3\n");
            toServer.flush();

            assertTrue(escapeLatch.await(1, TimeUnit.SECONDS), "El auditor debe activar ESCAPE al contar 3 canicas");
            String stateLine = leerHastaPrefijo(fromServer, "STATE_CHANGE:");
            assertEquals("STATE_CHANGE:ESCAPE", stateLine);
            assertEquals(3, gestorEstomago.contarCapturasValidas());

            // 6. Un 4.º evento después de panza llena no debe exceder las 3 canicas
            Thread.sleep(200);
            toServer.write("EVENT:CAPTURA_EXTRA\n");
            toServer.flush();
            Thread.sleep(60);
            assertEquals(3, gestorEstomago.contarCapturasValidas(), "En ESCAPE no debe registrar una 4ta captura");
        } finally {
            auditor.detener();
            sesionSerie.cerrar();
            toServer.close();
            fromServer.close();
            serverThread.join(1000);
        }
    }

    @Test
    public void testCliSerialConfigurationParsing() {
        ConfiguracionServidor cfg = ConfiguracionServidor.desdeArgumentos(
                new String[]{"--serial", "/dev/rfcomm0", "--baud", "9600"});
        assertEquals("/dev/rfcomm0", cfg.getPuertoSerie());
        assertEquals(9600, cfg.getBaudiosSerie());

        ConfiguracionServidor defaultCfg = ConfiguracionServidor.desdeArgumentos(new String[]{});
        assertNotNull(defaultCfg.getPuertoSerie());
        assertFalse(defaultCfg.getPuertoSerie().isBlank());
    }

    @Test
    public void testStreamSessionHandlesHelloHandshakeCleanly() throws Exception {
        PipedOutputStream clientTx = new PipedOutputStream();
        PipedInputStream serverRx = new PipedInputStream(clientTx);
        PipedOutputStream serverTx = new PipedOutputStream();
        PipedInputStream clientRx = new PipedInputStream(serverTx);

        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(new ConfiguracionServidor().resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);

        ManejadorCliente sesion = new ManejadorCliente(
                serverRx, serverTx, "Test-Hello", () -> {},
                gestorEstomago, clasificador, planificador, 180L, 5000L);

        java.util.List<String> logs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.logging.Logger handlerLogger = java.util.logging.Logger.getLogger(ManejadorCliente.class.getName());
        java.util.logging.Handler captureHandler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        handlerLogger.addHandler(captureHandler);

        Thread serverThread = new Thread(sesion, "Test-HelloSession");
        serverThread.start();

        BufferedWriter toServer = new BufferedWriter(new OutputStreamWriter(clientTx, StandardCharsets.UTF_8));
        BufferedReader fromServer = new BufferedReader(new InputStreamReader(clientRx, StandardCharsets.UTF_8));

        try {
            toServer.write("HELLO:" + ConfiguracionServidor.VERSION_SISTEMA + "," + ConfiguracionServidor.RIG_HARDWARE + "\n");
            toServer.write("HELLO:1.0.0,other-rig\n");
            toServer.write("TELEMETRY:90.0,95.0,85.0,12.0,12.0,0.0,1000\n");
            toServer.flush();

            String cmdLine = leerHastaPrefijo(fromServer, "COMMAND:");
            assertTrue(cmdLine.startsWith("COMMAND:"), "Debe procesar TELEMETRY tras HELLO");
            assertTrue(sesion.isConectado(), "La sesion debe seguir conectada tras HELLO");
            assertTrue(logs.stream().anyMatch(m -> m.contains("[HANDSHAKE OK]")), "Debe registrar HANDSHAKE OK");
            assertTrue(logs.stream().anyMatch(m -> m.contains("[VERSION MISMATCH]")), "Debe advertir VERSION MISMATCH");
            assertFalse(logs.stream().anyMatch(m -> m.contains("[PROTOCOLO] Mensaje no reconocido")),
                    "HELLO no debe tratarse como mensaje no reconocido");
        } finally {
            handlerLogger.removeHandler(captureHandler);
            sesion.cerrar();
            toServer.close();
            fromServer.close();
            serverThread.join(1000);
        }
    }

    @Test
    public void testStreamSessionIgnoresDbgAndSendsCmdStartWhenAutoStart() throws Exception {
        PipedOutputStream clientTx = new PipedOutputStream();
        PipedInputStream serverRx = new PipedInputStream(clientTx);
        PipedOutputStream serverTx = new PipedOutputStream();
        PipedInputStream clientRx = new PipedInputStream(serverTx);

        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(new ConfiguracionServidor().resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);

        ManejadorCliente sesion = new ManejadorCliente(
                serverRx, serverTx, "Test-Mega-AutoStart", () -> {},
                gestorEstomago, clasificador, planificador, 180L, 5000L, true);

        java.util.List<String> logs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.logging.Logger handlerLogger = java.util.logging.Logger.getLogger(ManejadorCliente.class.getName());
        java.util.logging.Handler captureHandler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        handlerLogger.addHandler(captureHandler);

        Thread serverThread = new Thread(sesion, "Test-MegaAutoStart");
        serverThread.start();

        BufferedWriter toServer = new BufferedWriter(new OutputStreamWriter(clientTx, StandardCharsets.UTF_8));
        BufferedReader fromServer = new BufferedReader(new InputStreamReader(clientRx, StandardCharsets.UTF_8));

        try {
            toServer.write("HELLO:" + ConfiguracionServidor.VERSION_SISTEMA + "," + ConfiguracionServidor.RIG_HARDWARE + "\n");
            toServer.write("DBG:estomago=42 base=42 det=2 cambios=0\n");
            toServer.flush();

            String startLine = leerHastaPrefijo(fromServer, "CMD:START");
            assertEquals("CMD:START", startLine);
            assertFalse(logs.stream().anyMatch(m -> m.contains("[PROTOCOLO] Mensaje no reconocido")),
                    "DBG: no debe tratarse como mensaje no reconocido");
        } finally {
            handlerLogger.removeHandler(captureHandler);
            sesion.cerrar();
            toServer.close();
            fromServer.close();
            serverThread.join(1000);
        }
    }

    @Test
    public void testStreamSessionIgnoresDeprecatedSimulationEvents() throws Exception {
        PipedOutputStream clientTx = new PipedOutputStream();
        PipedInputStream serverRx = new PipedInputStream(clientTx);
        PipedOutputStream serverTx = new PipedOutputStream();
        PipedInputStream clientRx = new PipedInputStream(serverTx);

        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(new ConfiguracionServidor().resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);

        ManejadorCliente sesion = new ManejadorCliente(
                serverRx, serverTx, "Test-SimEvents", () -> {},
                gestorEstomago, clasificador, planificador, 180L, 5000L);

        java.util.List<String> logs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.logging.Logger handlerLogger = java.util.logging.Logger.getLogger(ManejadorCliente.class.getName());
        java.util.logging.Handler captureHandler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        handlerLogger.addHandler(captureHandler);

        Thread serverThread = new Thread(sesion, "Test-DeprecatedSimEvents");
        serverThread.start();

        BufferedWriter toServer = new BufferedWriter(new OutputStreamWriter(clientTx, StandardCharsets.UTF_8));
        BufferedReader fromServer = new BufferedReader(new InputStreamReader(clientRx, StandardCharsets.UTF_8));

        try {
            toServer.write("TELEMETRY:90.0,95.0,85.0,150.0,150.0,0.0,1000\n");
            toServer.flush();
            String firstCmd = leerHastaPrefijo(fromServer, "COMMAND:");
            assertTrue(firstCmd.startsWith("COMMAND:"));

            toServer.write("EVENT:SALIDA_META:0.00,0.60\n");
            toServer.write("EVENT:COLISION,impacto=1,lado=front_left\n");
            toServer.write("TELEMETRY:90.0,95.0,85.0,150.0,150.0,0.0,1001\n");
            toServer.flush();

            String cmdAfterEvents = leerHastaPrefijo(fromServer, "COMMAND:");
            assertTrue(sesion.isConectado(), "La sesion debe seguir conectada");
            assertEquals(EstadoRobot.BUSQUEDA, planificador.getEstado(), "El estado debe permanecer en BUSQUEDA");
            assertFalse(logs.stream().anyMatch(m -> m.contains("[META SALIDA]")),
                    "EVENT:SALIDA_META no debe registrarse ni alterar el planificador");

            String[] campos = cmdAfterEvents.substring("COMMAND:".length()).split(",");
            double vLeft = Double.parseDouble(campos[0]);
            double vRight = Double.parseDouble(campos[1]);
            assertTrue(vLeft > 0.0 && vRight > 0.0,
                    "EVENT:COLISION no debe activar retroceso ni giro evasivo; comando recibido: " + cmdAfterEvents);
        } finally {
            handlerLogger.removeHandler(captureHandler);
            sesion.cerrar();
            toServer.close();
            fromServer.close();
            serverThread.join(1000);
        }
    }

    private static String leerHastaPrefijo(BufferedReader reader, String prefijo) throws IOException {
        for (int i = 0; i < 15; i++) {
            String line = reader.readLine();
            assertNotNull(line, "El stream se cerró antes de recibir " + prefijo);
            if (line.startsWith(prefijo)) {
                return line;
            }
        }
        fail("No se recibió línea con prefijo " + prefijo);
        return null;
    }
}
