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

        // Cooldown de 500 ms y heartbeat cada 120 ms para tolerar fsync en disco sin flakiness
        ManejadorCliente sesionSerie = new ManejadorCliente(
                serverRx, serverTx, "Serial:/dev/rfcomm0", () -> {},
                gestorEstomago, clasificador, planificador, 500L, 120L);

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

            // 3. Captura #1 + rebote espurio inmediato (30 ms después < 500 ms cooldown)
            toServer.write("EVENT:CAPTURA_LOBITO_1\n");
            toServer.flush();
            Thread.sleep(30);
            toServer.write("EVENT:CAPTURA_REBOTE_ESPURIO\n");
            toServer.flush();
            Thread.sleep(60);
            assertEquals(1, gestorEstomago.contarCapturasValidas(), "El rebote dentro del cooldown no debe duplicar la captura");

            // 4. Captura #2 tras vencer el cooldown
            Thread.sleep(520);
            toServer.write("EVENT:CAPTURA_LOBITO_2\n");
            toServer.flush();
            Thread.sleep(60);
            assertEquals(2, gestorEstomago.contarCapturasValidas());

            // 5. Captura #3 tras vencer el cooldown -> dispara STATE_CHANGE:ESCAPE
            Thread.sleep(520);
            toServer.write("EVENT:CAPTURA_LOBITO_3\n");
            toServer.flush();

            assertTrue(escapeLatch.await(1, TimeUnit.SECONDS), "El auditor debe activar ESCAPE al contar 3 canicas");
            String stateLine = leerHastaPrefijo(fromServer, "STATE_CHANGE:");
            assertEquals("STATE_CHANGE:ESCAPE", stateLine);
            assertEquals(3, gestorEstomago.contarCapturasValidas());

            // 6. Un 4.º evento después de panza llena no debe exceder las 3 canicas
            Thread.sleep(550);
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
            toServer.write("HEARTBEAT\n");
            toServer.flush();

            String startLine = leerHastaPrefijo(fromServer, "CMD:START");
            assertEquals("CMD:START", startLine);
            assertFalse(logs.stream().anyMatch(m -> m.contains("[PROTOCOLO] Mensaje no reconocido")),
                    "HELLO y HEARTBEAT no deben tratarse como mensajes no reconocidos");
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

    @Test
    public void testMonitoreoPasivoEnReposoYEscalaDeVelocidadEnCorrida() throws Exception {
        PipedOutputStream clientTx = new PipedOutputStream();
        PipedInputStream serverRx = new PipedInputStream(clientTx);
        PipedOutputStream serverTx = new PipedOutputStream();
        PipedInputStream clientRx = new PipedInputStream(serverTx);

        ConfiguracionServidor cfg = ConfiguracionServidor.desdeArgumentos(new String[0]);
        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(cfg.resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);
        EstadoMonitorTui monitor = new EstadoMonitorTui(cfg, clasificador, 0, List.of());

        ManejadorCliente sesion = new ManejadorCliente(
                serverRx, serverTx, "Serie:/dev/rfcomm0@9600", () -> {},
                gestorEstomago, clasificador, planificador, 180L, 5000L, false, monitor, true);

        Thread serverThread = new Thread(sesion, "Test-IdleAndScale");
        serverThread.start();

        BufferedWriter toServer = new BufferedWriter(new OutputStreamWriter(clientTx, StandardCharsets.UTF_8));
        BufferedReader fromServer = new BufferedReader(new InputStreamReader(clientRx, StandardCharsets.UTF_8));

        try {
            // 1. En reposo (!corrida): actualiza TUI y clasifica Red 1/2 pero no avanza FSM ni emite COMMAND
            toServer.write("TELEMETRY:90.0,95.0,85.0,12.0,12.0,0.0,1000\n");
            toServer.flush();

            long deadline = System.currentTimeMillis() + 1000L;
            while (monitor.getUltimaTelemetria() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(15);
            }
            assertNotNull(monitor.getUltimaTelemetria(), "La TUI debe recibir telemetria aun en reposo");
            assertEquals("LOBITO_CENTRO", monitor.getUltimaClaseCanica());
            assertEquals(0, planificador.getContadorCiclos(), "En reposo no debe avanzar el planificador");
            assertEquals(0.0, monitor.getUltimaTelemetria().comando().getVLeft(), 1e-4);

            // 2. Al activar corrida: emite COMMAND escalado a ESCALA_VELOCIDAD_FIJA=0.40 (2.6 -> 1.04)
            monitor.setCorridaActiva(true);
            toServer.write("TELEMETRY:90.0,95.0,85.0,12.0,12.0,0.0,1100\n");
            toServer.flush();

            String cmdLine = leerHastaPrefijo(fromServer, "COMMAND:");
            String[] partes = cmdLine.substring("COMMAND:".length()).split(",");
            assertEquals(1.04, Double.parseDouble(partes[0]), 0.05);
            assertEquals(1.04, Double.parseDouble(partes[1]), 0.05);
            assertEquals(1, planificador.getContadorCiclos());
        } finally {
            sesion.cerrarControlado();
            toServer.close();
            fromServer.close();
            serverThread.join(1000);
        }
    }

    @Test
    public void testDesconexionInesperadaActivaErrorSeguroYReconexionRestaura() throws Exception {
        PipedOutputStream clientTx1 = new PipedOutputStream();
        PipedInputStream serverRx1 = new PipedInputStream(clientTx1);
        PipedOutputStream serverTx1 = new PipedOutputStream();
        PipedInputStream clientRx1 = new PipedInputStream(serverTx1);

        ConfiguracionServidor cfg = ConfiguracionServidor.desdeArgumentos(new String[0]);
        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(cfg.resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);
        EstadoMonitorTui monitor = new EstadoMonitorTui(cfg, clasificador, 0, List.of());
        monitor.setCorridaActiva(true);

        ManejadorCliente sesion1 = new ManejadorCliente(
                serverRx1, serverTx1, "Serie:/dev/rfcomm0@9600", () -> {},
                gestorEstomago, clasificador, planificador, 180L, 5000L, false, monitor, true);
        Thread t1 = new Thread(sesion1, "Test-Drop1");
        t1.start();

        BufferedWriter toServer1 = new BufferedWriter(new OutputStreamWriter(clientTx1, StandardCharsets.UTF_8));
        BufferedReader fromServer1 = new BufferedReader(new InputStreamReader(clientRx1, StandardCharsets.UTF_8));
        toServer1.write("HELLO:" + ConfiguracionServidor.VERSION_SISTEMA + "," + ConfiguracionServidor.RIG_HARDWARE + "\n");
        toServer1.flush();
        long deadline = System.currentTimeMillis() + 1000L;
        while (!monitor.isHandshakeRecibido() && System.currentTimeMillis() < deadline) {
            Thread.sleep(15);
        }
        // Arranca la corrida y luego se corta el enlace inesperadamente -> ERROR_SEGURO transitorio
        monitor.setCorridaActiva(true);
        toServer1.close();
        fromServer1.close();
        t1.join(1000);
        assertEquals(EstadoRobot.ERROR_SEGURO, planificador.getEstado());
        assertEquals(EstadoRobot.ERROR_SEGURO, monitor.getEstadoFsmActual());

        // Al reconectar con HELLO tras haber capturado 3 canicas en disco -> restaura ESCAPE
        gestorEstomago.registrarCaptura("CAP_1");
        gestorEstomago.registrarCaptura("CAP_2");
        gestorEstomago.registrarCaptura("CAP_3");
        PipedOutputStream clientTx2 = new PipedOutputStream();
        PipedInputStream serverRx2 = new PipedInputStream(clientTx2);
        PipedOutputStream serverTx2 = new PipedOutputStream();
        PipedInputStream clientRx2 = new PipedInputStream(serverTx2);
        ManejadorCliente sesion2 = new ManejadorCliente(
                serverRx2, serverTx2, "Serie:/dev/rfcomm0@9600", () -> {},
                gestorEstomago, clasificador, planificador, 180L, 5000L, true, monitor, true);
        Thread t2 = new Thread(sesion2, "Test-Reconnect");
        t2.start();
        try (BufferedWriter toServer2 = new BufferedWriter(new OutputStreamWriter(clientTx2, StandardCharsets.UTF_8));
             BufferedReader fromServer2 = new BufferedReader(new InputStreamReader(clientRx2, StandardCharsets.UTF_8))) {
            toServer2.write("HELLO:" + ConfiguracionServidor.VERSION_SISTEMA + "," + ConfiguracionServidor.RIG_HARDWARE + "\n");
            toServer2.flush();
            String cmdStart = fromServer2.readLine();
            assertEquals("CMD:START", cmdStart);
            assertEquals(EstadoRobot.ESCAPE, planificador.getEstado());
        } finally {
            sesion2.cerrarControlado();
            t2.join(1000);
        }
    }

    @Test
    public void testStartManualSuprimeAutoStartDuplicadoYBackoffFantasma() throws Exception {
        assertEquals(1500L, ReceptorSerie.calcularBackoffFantasmaMs(1));
        assertEquals(3000L, ReceptorSerie.calcularBackoffFantasmaMs(2));
        assertEquals(5000L, ReceptorSerie.calcularBackoffFantasmaMs(3));
        assertEquals(5000L, ReceptorSerie.calcularBackoffFantasmaMs(5));

        PipedOutputStream clientTx = new PipedOutputStream();
        PipedInputStream serverRx = new PipedInputStream(clientTx);
        PipedOutputStream serverTx = new PipedOutputStream();
        PipedInputStream clientRx = new PipedInputStream(serverTx);

        ConfiguracionServidor cfg = ConfiguracionServidor.desdeArgumentos(new String[0]);
        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(cfg.resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);
        ManejadorCliente sesion = new ManejadorCliente(
                serverRx, serverTx, "Test-ManualStart", null,
                gestorEstomago, clasificador, planificador, 180L, 5000L, true, null, false);

        Thread serverThread = new Thread(sesion, "Test-ManualStart");
        serverThread.start();

        BufferedWriter toServer = new BufferedWriter(new OutputStreamWriter(clientTx, StandardCharsets.UTF_8));
        BufferedReader fromServer = new BufferedReader(new InputStreamReader(clientRx, StandardCharsets.UTF_8));

        try {
            sesion.marcarStartManual();
            toServer.write("HELLO:" + ConfiguracionServidor.VERSION_SISTEMA + "," + ConfiguracionServidor.RIG_HARDWARE + "\n");
            toServer.write("TELEMETRY:90.0,95.0,85.0,999.0,999.0,0.0,1200\n");
            toServer.flush();

            String primeraLinea = fromServer.readLine();
            assertNotNull(primeraLinea);
            assertFalse(primeraLinea.startsWith("CMD:START"),
                    "No debe emitir CMD:START automatico si ya hubo start manual");
            assertEquals(2, sesion.getTotalTramasValidas());
        } finally {
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
