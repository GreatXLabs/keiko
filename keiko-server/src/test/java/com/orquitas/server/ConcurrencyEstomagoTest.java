package com.orquitas.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de esfuerzo concurrente sobre 'estomago.txt' ("La Linareada").
 * Valida que la seccion critica sincronizada y el vaciado fisico (sync)
 * prevengan condiciones de carrera entre multiples hilos escritores y el hilo auditor lector.
 */
public class ConcurrencyEstomagoTest {

    private File tempEstomago;
    private GestorEstomago manager;

    @BeforeEach
    public void setUp() throws IOException {
        tempEstomago = File.createTempFile("estomago_test_", ".txt");
        manager = new GestorEstomago(tempEstomago);
    }

    @AfterEach
    public void tearDown() {
        if (tempEstomago != null && tempEstomago.exists()) {
            tempEstomago.delete();
        }
    }

    @Test
    public void testSequentialCapturesAndPersistence() throws Exception {
        assertEquals(0, manager.contarCapturasValidas(), "Debe iniciar con 0 capturas");

        int c1 = manager.registrarCaptura("Lobito #1 en cuadrante alfa");
        assertEquals(1, c1);
        assertEquals(1, manager.contarCapturasValidas());

        int c2 = manager.registrarCaptura("Lobito #2 en cavidad bucal");
        assertEquals(2, c2);
        assertEquals(2, manager.contarCapturasValidas());

        int c3 = manager.registrarCaptura("Lobito #3 capturado");
        assertEquals(3, c3);
        assertEquals(3, manager.contarCapturasValidas());

        List<RegistroCaptura> records = manager.obtenerCapturasValidas();
        assertEquals(3, records.size());
        assertEquals(1, records.get(0).getNumeroCaptura());
        assertEquals(2, records.get(1).getNumeroCaptura());
        assertEquals(3, records.get(2).getNumeroCaptura());
    }

    @Test
    public void testConcurrentWritersAndAuditorReader() throws Exception {
        int numWriters = 5;
        int capturesPerWriter = 4;
        int totalExpected = numWriters * capturesPerWriter;

        ExecutorService writerPool = Executors.newFixedThreadPool(numWriters);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numWriters);

        AtomicBoolean auditorDetectedPanzaLlena = new AtomicBoolean(false);
        AtomicInteger maxAuditedObserved = new AtomicInteger(0);

        // Hilo Auditor concurrente
        AuditorEstomago auditor = new AuditorEstomago(manager, 10, 3);
        auditor.agregarOyente(new OyenteAuditor() {
            @Override
            public void onModoEscapeActivado(List<RegistroCaptura> verifiedRecords) {
                auditorDetectedPanzaLlena.set(true);
            }

            @Override
            public void onCapturaAuditada(int currentCaptures) {
                maxAuditedObserved.updateAndGet(curr -> Math.max(curr, currentCaptures));
            }
        });
        auditor.iniciar();

        // Lanzar hilos escritores concurrentes disputando el bloqueo exclusivo
        for (int w = 0; w < numWriters; w++) {
            final int writerId = w;
            writerPool.submit(() -> {
                try {
                    startLatch.await(); // Disparo simultaneo de todos los hilos
                    for (int c = 0; c < capturesPerWriter; c++) {
                        manager.registrarCaptura("Escritor-" + writerId + " muestra-" + c);
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    fail("Fallo en hilo escritor: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completedInTime = doneLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completedInTime, "Todos los escritores concurrentes debieron terminar a tiempo");

        writerPool.shutdown();
        Thread.sleep(100); // Dar un ciclo al auditor
        auditor.detener();

        // Verificacion de consistencia en disco fisico
        int finalCount = manager.contarCapturasValidas();
        assertEquals(totalExpected, finalCount, "Todas las capturas concurrentes deben quedar registradas sin perdida");

        List<RegistroCaptura> records = manager.obtenerCapturasValidas();
        assertEquals(totalExpected, records.size(), "La cantidad de lineas validas debe ser exacta");

        assertTrue(auditorDetectedPanzaLlena.get(), "El auditor debio disparar Panza Llena al alcanzar >= 3 capturas");
        assertTrue(maxAuditedObserved.get() >= 3, "El auditor debio observar al menos 3 capturas");
    }
}
