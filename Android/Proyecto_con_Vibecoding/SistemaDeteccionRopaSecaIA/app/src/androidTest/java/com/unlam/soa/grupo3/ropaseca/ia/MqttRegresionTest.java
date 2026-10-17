package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MqttRegresionTest {
    private void main(Runnable tarea) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(tarea);
    }

    private void esperar(MqttManager mqtt, Predicate<ModeloEstacion> condicion) throws InterruptedException {
        CountDownLatch listo = new CountDownLatch(1);
        Runnable listener = () -> { if (condicion.test(mqtt.modelo)) listo.countDown(); };
        main(() -> mqtt.agregar(listener));
        try { assertTrue("No se cumplió la condición", listo.await(12, TimeUnit.SECONDS)); }
        finally { main(() -> mqtt.quitar(listener)); }
    }

    private void envioRetenido(MqttManager mqtt, BrokerLocal broker, ExecutorService worker,
                              ModeloEstacion.Comando comando, String compatible) throws Exception {
        CountDownLatch liberar = new CountDownLatch(1);
        CountDownLatch retenido = new CountDownLatch(1);
        worker.execute(() -> {
            retenido.countDown();
            try { liberar.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(retenido.await(2, TimeUnit.SECONDS));
            main(() -> mqtt.enviar(comando));
            broker.publicar(ConfiguracionMqtt.ESTADO, compatible, false);
            esperar(mqtt, m -> m.estado.name().equals(compatible));
            main(() -> {
                assertEquals(ModeloEstacion.Resultado.PENDIENTE, mqtt.modelo.resultado);
                assertFalse(mqtt.modelo.publicado);
            });
            assertNull(broker.comandos.poll(200, TimeUnit.MILLISECONDS));
        } finally { liberar.countDown(); }
        assertTrue(new String(broker.esperar(broker.comandos), java.nio.charset.StandardCharsets.US_ASCII)
                .endsWith(comando.name()));
        esperar(mqtt, m -> m.publicado);
        broker.publicar(ConfiguracionMqtt.ESTADO, compatible, false);
        esperar(mqtt, m -> m.resultado == ModeloEstacion.Resultado.CONFIRMADO);
        assertNull("Una sola publicación", broker.comandos.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void estadoCompatibleAnteriorAPublicacionNoConfirmaNiCancelaEnvio() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (BrokerLocal broker = new BrokerLocal()) {
            MqttManager mqtt = new MqttManager(broker.uri(), worker);
            try {
                main(mqtt::iniciar);
                esperar(mqtt, m -> m.broker);
                broker.publicar(ConfiguracionMqtt.ESTADO, "ESPERA", false);
                esperar(mqtt, m -> m.controlHabilitado(SystemClock.elapsedRealtime()));
                envioRetenido(mqtt, broker, worker, ModeloEstacion.Comando.INICIAR, "LLUVIA");
                envioRetenido(mqtt, broker, worker, ModeloEstacion.Comando.FINALIZAR, "ESPERA");
            } finally {
                main(mqtt::detener);
            }
        } finally { worker.shutdown(); }
    }

    @Test public void salirYVolverConConnackPendienteNoAbreDosSockets() throws Exception {
        CountDownLatch connack = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (BrokerLocal broker = new BrokerLocal(connack)) {
            MqttManager mqtt = new MqttManager(broker.uri(), worker);
            try {
                main(mqtt::iniciar);
                broker.esperar(broker.conexiones);
                main(() -> { mqtt.detener(); mqtt.iniciar(); });
                // Barrera de main: deja que el nuevo intento se encole antes de liberar el anterior.
                main(() -> { });
                connack.countDown();
                broker.esperar(broker.conexiones);
                esperar(mqtt, m -> m.broker);
                assertEquals("No deben coexistir sockets del mismo clientId", 1, broker.maxAbiertas.get());
            } finally {
                connack.countDown();
                main(mqtt::detener);
            }
        } finally { worker.shutdown(); }
    }
}
