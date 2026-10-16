package com.orquitas.server;

import com.orquitas.server.persistence.AuditorThread;
import com.orquitas.server.persistence.EstomagoManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de esfuerzo concurrente sobre 'estomago.txt' ("La Linareada").
 * Valida que los bloqueos de lectura/escritura (ReentrantReadWriteLock) y el
 * vaciado físico (sync) prevengan condiciones de carrera entre múltiples hilos
 * escritores y el hilo auditor lector.
 */
public class ConcurrencyEstomagoTest {

    private File tempEstomago;
    private EstomagoManager manager;

    @BeforeEach
    public void setUp() throws IOException {
        tempEstomago = File.createTempFile("estomago_test_", ".txt");
        manager = new EstomagoManager(tempEstomago);
    }

    @AfterEach
    public void tearDown() {
        if (tempEstomago != null && tempEstomago.exists()) {
            tempEstomago.delete();
        }
    }

    @Test
    public void testSequentialCapturesAndPersistence() throws Exception {
        assertEquals(0, manager.countValidCaptures(), "Debe iniciar con 0 capturas");

        int c1 = manager.recordCapture("Lobito #1 en cuadrante alfa");
        assertEquals(1, c1);
        assertEquals(1, manager.countValidCaptures());

        int c2 = manager.recordCapture("Lobito #2 en cavidad bucal");
        assertEquals(2, c2);
        assertEquals(2, manager.countValidCaptures());

        int c3 = manager.recordCapture("Lobito #3 capturado");
        assertEquals(3, c3);
        assertEquals(3, manager.countValidCaptures());

        List<String> records = manager.getAllCaptureRecords();
        assertEquals(3, records.size());
        assertTrue(records.get(0).contains("CAPTURA #1"));
        assertTrue(records.get(1).contains("CAPTURA #2"));
        assertTrue(records.get(2).contains("CAPTURA #3"));
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
        AuditorThread auditor = new AuditorThread(manager, 10, 3, new AuditorThread.AuditorListener() {
            @Override
            public void onPanzaLlena(int totalCaptures, List<String> verifiedRecords) {
                auditorDetectedPanzaLlena.set(true);
            }

            @Override
            public void onCaptureAudited(int currentCaptures) {
                maxAuditedObserved.updateAndGet(curr -> Math.max(curr, currentCaptures));
            }
        });
        auditor.start();

        // Lanzar hilos escritores concurrentes disputando el bloqueo exclusivo
        for (int w = 0; w < numWriters; w++) {
            final int writerId = w;
            writerPool.submit(() -> {
                try {
                    startLatch.await(); // Disparo simultáneo de todos los hilos
                    for (int c = 0; c < capturesPerWriter; c++) {
                        manager.recordCapture("Escritor-" + writerId + " muestra-" + c);
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
        auditor.stopAuditor();

        // Verificación de consistencia en disco físico
        int finalCount = manager.countValidCaptures();
        assertEquals(totalExpected, finalCount, "Todas las capturas concurrentes deben quedar registradas sin pérdida");

        List<String> records = manager.getAllCaptureRecords();
        assertEquals(totalExpected, records.size(), "La cantidad de líneas válidas debe ser exacta");

        assertTrue(auditorDetectedPanzaLlena.get(), "El auditor debió disparar Panza Llena al alcanzar >= 3 capturas");
        assertTrue(maxAuditedObserved.get() >= 3, "El auditor debió observar al menos 3 capturas");
    }
}
