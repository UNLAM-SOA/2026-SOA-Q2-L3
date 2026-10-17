package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MqttManagerTest {
    private void main(Runnable tarea) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(tarea);
    }

    private void esperar(MqttManager mqtt, Predicate<ModeloEstacion> condicion) throws InterruptedException {
        CountDownLatch listo = new CountDownLatch(1);
        Runnable listener = () -> { if (condicion.test(mqtt.modelo)) listo.countDown(); };
        main(() -> mqtt.agregar(listener));
        try { assertTrue("No se cumplió la condición en main", listo.await(12, TimeUnit.SECONDS)); }
        finally { main(() -> mqtt.quitar(listener)); }
    }

    @Test public void transporteRealLocalComandosConfirmacionCorteYReconexion() throws Exception {
        try (BrokerLocal broker = new BrokerLocal()) {
            MqttManager mqtt = new MqttManager(broker.uri());
            try {
                main(() -> { mqtt.iniciar(); mqtt.iniciar(); });
                byte[] connect = broker.esperar(broker.conexiones);
                DataInputStream protocolo = new DataInputStream(new ByteArrayInputStream(connect));
                assertEquals("MQTT", protocolo.readUTF());
                assertEquals(4, protocolo.readUnsignedByte());
                assertEquals(2, protocolo.readUnsignedByte()); // sesión limpia, sin credenciales/will
                protocolo.readUnsignedShort();
                String clientId = protocolo.readUTF();
                assertTrue(clientId.startsWith("android-ia-"));
                byte[] sub = broker.esperar(broker.suscripciones);
                DataInputStream suscripcion = new DataInputStream(new ByteArrayInputStream(sub));
                suscripcion.readUnsignedShort();
                assertEquals(ConfiguracionMqtt.ESTADO, suscripcion.readUTF());
                assertEquals(0, suscripcion.readUnsignedByte());
                assertEquals(ConfiguracionMqtt.SENSOR, suscripcion.readUTF());
                assertEquals(0, suscripcion.readUnsignedByte());
                esperar(mqtt, m -> m.broker && m.estado == ModeloEstacion.Estado.DESCONOCIDO);
                assertNull("Iniciar dos veces no duplica conexiones", broker.conexiones.poll(200, TimeUnit.MILLISECONDS));
                broker.publicar(ConfiguracionMqtt.ESTADO, "ESPERA", false);
                esperar(mqtt, m -> m.controlHabilitado(SystemClock.elapsedRealtime()));
                main(() -> {
                    mqtt.enviar(ModeloEstacion.Comando.INICIAR);
                    mqtt.enviar(ModeloEstacion.Comando.INICIAR);
                });
                byte[] comando = broker.esperar(broker.comandos);
                DataInputStream publicacion = new DataInputStream(new ByteArrayInputStream(comando));
                assertEquals(0x30, publicacion.readUnsignedByte()); // QoS 0, sin retain
                assertEquals(ConfiguracionMqtt.COMANDO, publicacion.readUTF());
                byte[] payload = new byte[publicacion.available()];
                publicacion.readFully(payload);
                assertEquals("INICIAR", new String(payload, StandardCharsets.US_ASCII));
                esperar(mqtt, m -> m.publicado);
                broker.publicar(ConfiguracionMqtt.ESTADO, "LLUVIA", false);
                esperar(mqtt, m -> m.resultado == ModeloEstacion.Resultado.CONFIRMADO);
                broker.publicar(ConfiguracionMqtt.SENSOR, "60", false);
                esperar(mqtt, m -> Integer.valueOf(60).equals(m.humedad));
                main(() -> mqtt.enviar(ModeloEstacion.Comando.FINALIZAR));
                assertTrue(new String(broker.esperar(broker.comandos), StandardCharsets.US_ASCII).endsWith("FINALIZAR"));
                broker.cortar();
                esperar(mqtt, m -> !m.broker && m.resultado == ModeloEstacion.Resultado.FIN_NO_CONFIRMADO);
                broker.esperar(broker.conexiones);
                broker.esperar(broker.suscripciones);
                esperar(mqtt, m -> m.broker && m.estado == ModeloEstacion.Estado.DESCONOCIDO && m.humedad == null);
                assertNull("No se reproduce FINALIZAR al reconectar", broker.comandos.poll(300, TimeUnit.MILLISECONDS));
                broker.publicar(ConfiguracionMqtt.ESTADO, "ESPERA", true);
                broker.publicar(ConfiguracionMqtt.ESTADO, "OTRO", false);
                broker.publicar(ConfiguracionMqtt.SENSOR, "50", false);
                // Un estado válido posterior verifica que todos los mensajes anteriores ya se procesaron.
                broker.publicar(ConfiguracionMqtt.ESTADO, "ESPERA", false);
                esperar(mqtt, m -> m.estado == ModeloEstacion.Estado.ESPERA);
                main(() -> {
                    assertNull(mqtt.modelo.humedad);
                    mqtt.enviar(ModeloEstacion.Comando.INICIAR);
                });
                broker.esperar(broker.comandos);
                // Sin Activities: el reloj compartido debe resolver el plazo por sí mismo.
                esperar(mqtt, m -> m.resultado == ModeloEstacion.Resultado.INICIO_NO_CONFIRMADO);
                main(() -> {
                    assertNull(mqtt.modelo.pendiente);
                    mqtt.detener();
                    assertFalse(mqtt.modelo.broker);
                    assertNull(mqtt.modelo.pendiente);
                });
            } finally { main(mqtt::detener); }
        }
    }
}
