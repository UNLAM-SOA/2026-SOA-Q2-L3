package com.unlam.soa.grupo3.ropaseca.ia;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

// Doble TCP mínimo de MQTT 3.1.1/QoS 0. Nunca accede al broker público ni al ESP32.
final class BrokerLocal implements AutoCloseable {
    final BlockingQueue<byte[]> comandos = new LinkedBlockingQueue<>();
    final BlockingQueue<byte[]> conexiones = new LinkedBlockingQueue<>();
    final BlockingQueue<byte[]> suscripciones = new LinkedBlockingQueue<>();
    private final ServerSocket servidor = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    private final Thread hilo = new Thread(this::atender, "broker-test");
    private volatile Socket socket;
    private volatile DataOutputStream salida;
    private volatile boolean cerrado;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final List<Thread> clientes = new CopyOnWriteArrayList<>();
    private final CountDownLatch primerConnack;
    private final AtomicInteger intentos = new AtomicInteger();
    final AtomicInteger maxAbiertas = new AtomicInteger();

    BrokerLocal() throws IOException { this(new CountDownLatch(0)); }

    BrokerLocal(CountDownLatch primerConnack) throws IOException {
        this.primerConnack = primerConnack;
        hilo.start();
    }

    String uri() { return "tcp://127.0.0.1:" + servidor.getLocalPort(); }

    private void atender() {
        while (!cerrado) {
            try {
                Socket cliente = servidor.accept();
                sockets.add(cliente);
                Thread lector = new Thread(() -> atender(cliente), "cliente-broker-test");
                clientes.add(lector);
                lector.start();
            } catch (IOException e) {
                if (!cerrado) throw new AssertionError(e);
            }
        }
    }

    private void atender(Socket cliente) {
        try (Socket conexion = cliente) {
            socket = cliente;
            DataOutputStream respuesta = new DataOutputStream(conexion.getOutputStream());
            salida = respuesta;
            DataInputStream entrada = new DataInputStream(conexion.getInputStream());
            while (!cerrado) {
                int cabecera = entrada.readUnsignedByte();
                int longitud = 0;
                int multiplicador = 1;
                int b;
                do {
                    b = entrada.readUnsignedByte();
                    longitud += (b & 127) * multiplicador;
                    multiplicador *= 128;
                    if (multiplicador > 128 * 128 * 128 * 128L || longitud > 1024) throw new IOException("Paquete excesivo");
                } while ((b & 128) != 0);
                byte[] datos = new byte[longitud];
                entrada.readFully(datos);
                switch (cabecera >> 4) {
                    case 1:
                        int intento = intentos.incrementAndGet();
                        maxAbiertas.accumulateAndGet(sockets.size(), Math::max);
                        conexiones.add(datos);
                        if (intento == 1 && !primerConnack.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("CONNACK no liberado por la prueba");
                        }
                        paquete(respuesta, 0x20, new byte[]{0, 0});
                        break;
                    case 8:
                        suscripciones.add(datos);
                        paquete(respuesta, 0x90, new byte[]{datos[0], datos[1], 0, 0});
                        break;
                    case 3:
                        ByteArrayOutputStream recibido = new ByteArrayOutputStream();
                        recibido.write(cabecera);
                        recibido.write(datos);
                        comandos.add(recibido.toByteArray());
                        break;
                    case 12: paquete(respuesta, 0xD0, new byte[0]); break;
                    case 14: conexion.close(); break;
                    default: throw new IOException("Paquete inesperado");
                }
            }
        } catch (IOException e) {
            // Cerrar la conexión es una entrada deliberada de estas pruebas.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sockets.remove(cliente);
        }
    }

    private synchronized void paquete(DataOutputStream destino, int cabecera, byte[] datos) throws IOException {
        destino.writeByte(cabecera);
        int restante = datos.length;
        do {
            int b = restante % 128;
            restante /= 128;
            destino.writeByte(b | (restante > 0 ? 128 : 0));
        } while (restante > 0);
        destino.write(datos);
        destino.flush();
    }

    void publicar(String topic, String texto, boolean retenido) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream datos = new DataOutputStream(bytes);
        datos.writeUTF(topic);
        datos.write(texto.getBytes(StandardCharsets.US_ASCII));
        paquete(salida, retenido ? 0x31 : 0x30, bytes.toByteArray());
    }

    byte[] esperar(BlockingQueue<byte[]> cola) throws InterruptedException {
        byte[] datos = cola.poll(10, TimeUnit.SECONDS);
        if (datos == null) throw new AssertionError("No llegó el paquete MQTT esperado");
        return datos;
    }

    void cortar() throws IOException { socket.close(); }

    @Override public void close() throws IOException, InterruptedException {
        cerrado = true;
        primerConnack.countDown();
        servidor.close();
        for (Socket cliente : sockets) cliente.close();
        hilo.join(2000);
        for (Thread lector : clientes) lector.join(2000);
    }
}
