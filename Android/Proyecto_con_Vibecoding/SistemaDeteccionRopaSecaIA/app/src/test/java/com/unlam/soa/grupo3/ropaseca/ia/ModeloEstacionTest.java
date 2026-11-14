package com.unlam.soa.grupo3.ropaseca.ia;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static com.unlam.soa.grupo3.ropaseca.ia.ModeloEstacion.Estado.*;
import static com.unlam.soa.grupo3.ropaseca.ia.ModeloEstacion.Comando.*;
import static com.unlam.soa.grupo3.ropaseca.ia.ModeloEstacion.Resultado.*;

public class ModeloEstacionTest {
    private ModeloEstacion modelo() {
        ModeloEstacion m = new ModeloEstacion();
        m.conectar();
        return m;
    }

    private boolean estado(ModeloEstacion m, String estado, long ahora) {
        return m.recibir(ConfiguracionMqtt.ESTADO, estado.getBytes(StandardCharsets.US_ASCII), false, ahora, m.numeroComando);
    }

    private boolean sensor(ModeloEstacion m, String valor, long ahora) {
        return m.recibir(ConfiguracionMqtt.SENSOR, valor.getBytes(StandardCharsets.US_ASCII), false, ahora, m.numeroComando);
    }

    @Test public void brokerNoPruebaPresenciaYEstadoInicialEsDesconocido() {
        ModeloEstacion m = modelo();
        assertEquals(DESCONOCIDO, m.estado);
        assertFalse(m.controlHabilitado(0));
        assertFalse(sensor(m, "50", 0));
        assertTrue(estado(m, "ESPERA", 0));
        assertTrue(m.controlHabilitado(0));
        assertFalse(m.cicloActivo());
    }

    @Test public void validaTopicBytesLongitudRetencionYEstadosExactos() {
        ModeloEstacion m = modelo();
        for (String invalido : new String[]{"", "DESCONOCIDO", "espera", " ESPERA", "ESPERA\n",
                "ESPERA\0", "OTRO", "MONITOREANDO_SECADO EXTRA"}) {
            assertFalse(invalido, estado(m, invalido, 10));
        }
        assertFalse(m.recibir(ConfiguracionMqtt.SENSOR, "ESPERA".getBytes(StandardCharsets.US_ASCII), false, 10, -1));
        assertFalse(m.recibir(ConfiguracionMqtt.ESTADO + "/", "ESPERA".getBytes(StandardCharsets.US_ASCII), false, 10, -1));
        assertFalse(m.recibir(null, new byte[]{'0'}, false, 10, -1));
        assertFalse(m.recibir(ConfiguracionMqtt.ESTADO, null, false, 10, -1));
        assertFalse(m.recibir(ConfiguracionMqtt.ESTADO, new byte[33], false, 10, -1));
        assertFalse(m.recibir(ConfiguracionMqtt.ESTADO, "ESPERA".getBytes(StandardCharsets.US_ASCII), true, 10, -1));
        assertEquals(-1, m.ultimoEstado);
        assertFalse(m.estacionVigente(10));
    }

    @Test public void humedadSoloDuranteCicloYNoSeRecortaLoInvalido() {
        ModeloEstacion m = modelo();
        estado(m, "ESPERA", 0);
        assertFalse(sensor(m, "50", 0));
        estado(m, "MONITOREANDO_SECADO", 1);
        for (String invalido : new String[]{"", "-1", "+1", "101", "1000", "1.0", "10%", " 1", "1\n", "NaN", "\u00ff"}) {
            assertFalse(invalido, sensor(m, invalido, 2));
        }
        assertTrue(sensor(m, "0", 2));
        assertEquals(Integer.valueOf(0), m.humedad);
        assertTrue(sensor(m, "100", 3));
        assertFalse(sensor(m, "101", 4));
        assertFalse(m.recibir(ConfiguracionMqtt.SENSOR, new byte[]{'5', '0'}, true, 5, -1));
        assertEquals(Integer.valueOf(100), m.humedad);
        assertEquals(3, m.ultimoSensor);
    }

    @Test public void progresoUsaFormulaYTruncadoDelFirmware() {
        assertEquals(0, ModeloEstacion.progreso(100));
        assertEquals(50, ModeloEstacion.progreso(60));
        assertEquals(100, ModeloEstacion.progreso(20));
        assertEquals(100, ModeloEstacion.progreso(0));
        assertEquals(48, ModeloEstacion.progreso(61));
        assertEquals(98, ModeloEstacion.progreso(21));
        assertThrows(IllegalArgumentException.class, () -> ModeloEstacion.progreso(-1));
        assertThrows(IllegalArgumentException.class, () -> ModeloEstacion.progreso(101));
    }

    @Test public void vigenciaTieneLimiteExactoYHeartbeatNoRejuveneceHumedad() {
        ModeloEstacion m = modelo();
        estado(m, "MONITOREANDO_SECADO", 0);
        sensor(m, "60", 0);
        estado(m, "MONITOREANDO_SECADO", 10000);
        assertTrue(m.lecturaVigente(14999));
        assertFalse(m.lecturaVigente(15000));
        assertTrue(m.estacionVigente(24999));
        assertFalse(m.estacionVigente(25000));
        assertEquals(0, m.ultimoSensor);
        assertEquals(10000, m.ultimoEstado);
    }

    @Test public void noHeredaHumedadTrasEsperaNuevoCicloReconectarOVencerEstado() {
        ModeloEstacion m = modelo();
        estado(m, "MONITOREANDO_SECADO", 0);
        sensor(m, "60", 1);
        estado(m, "ROPA_SECA", 2);
        assertEquals(Integer.valueOf(60), m.humedad);
        estado(m, "ESPERA", 3);
        assertNull(m.humedad);
        estado(m, "MONITOREANDO_SECADO", 4);
        assertFalse(m.lecturaVigente(4));
        sensor(m, "50", 5);
        m.desconectar();
        assertFalse(m.lecturaVigente(6));
        m.conectar();
        assertEquals(DESCONOCIDO, m.estado);
        assertNull(m.humedad);
        estado(m, "MONITOREANDO_SECADO", 10);
        sensor(m, "50", 11);
        estado(m, "MONITOREANDO_SECADO", 15010);
        assertNull(m.humedad);
        assertEquals(-1, m.ultimoSensor);
    }

    @Test public void inicioSeConfirmaConCualquierEstadoActivoSinInventarTransicion() {
        for (String activo : new String[]{"MONITOREANDO_SECADO", "LLUVIA", "ROPA_SECA"}) {
            ModeloEstacion m = modelo();
            estado(m, "ESPERA", 0);
            assertTrue(m.solicitar(INICIAR, 1) > 0);
            m.publicado(m.numeroComando, 1);
            assertEquals(ESPERA, m.estado);
            assertEquals(PENDIENTE, m.resultado);
            assertFalse(m.controlHabilitado(2));
            assertEquals(-1, m.solicitar(INICIAR, 2));
            estado(m, "ESPERA", 3);
            assertEquals(PENDIENTE, m.resultado);
            estado(m, activo, 4);
            assertEquals(CONFIRMADO, m.resultado);
            assertNull(m.pendiente);
        }
    }

    @Test public void finalizarSoloSeConfirmaPorEspera() {
        ModeloEstacion m = modelo();
        estado(m, "LLUVIA", 0);
        assertEquals(-1, m.solicitar(INICIAR, 1));
        m.solicitar(FINALIZAR, 1);
        m.publicado(m.numeroComando, 1);
        estado(m, "ROPA_SECA", 2);
        assertEquals(PENDIENTE, m.resultado);
        estado(m, "ESPERA", 3);
        assertEquals(CONFIRMADO, m.resultado);
        assertEquals(-1, m.solicitar(FINALIZAR, 4));
    }

    @Test public void timeoutNoSeReiniciaPorHeartbeatYNoConfirmaFueraDePlazo() {
        ModeloEstacion m = modelo();
        estado(m, "ESPERA", 0);
        m.solicitar(INICIAR, 1);
        m.publicado(m.numeroComando, 1);
        estado(m, "ESPERA", 5000);
        m.verificarPlazos(10000);
        assertEquals(PENDIENTE, m.resultado);
        estado(m, "LLUVIA", 10001);
        assertEquals(INICIO_NO_CONFIRMADO, m.resultado);
        assertEquals(LLUVIA, m.estado);
        assertTrue(m.controlHabilitado(10001));
    }

    @Test public void desconexionOCaducidadResuelvenComandoSinUiPresente() {
        ModeloEstacion m = modelo();
        estado(m, "LLUVIA", 0);
        m.solicitar(FINALIZAR, 1);
        m.desconectar();
        assertEquals(FIN_NO_CONFIRMADO, m.resultado);
        assertNull(m.pendiente);
        assertFalse(m.controlHabilitado(2));
        m.conectar();
        estado(m, "LLUVIA", 100);
        m.solicitar(FINALIZAR, 15099);
        m.verificarPlazos(15100);
        assertEquals(FIN_NO_CONFIRMADO, m.resultado);
    }

    @Test public void falloDeEnvioViejoNoCancelaOtroComandoNiUnaConfirmacion() {
        ModeloEstacion m = modelo();
        estado(m, "ESPERA", 0);
        long primero = m.solicitar(INICIAR, 1);
        m.publicado(primero, 1);
        estado(m, "MONITOREANDO_SECADO", 2);
        m.falloEnvio(primero);
        assertEquals(CONFIRMADO, m.resultado);
        long segundo = m.solicitar(FINALIZAR, 3);
        m.falloEnvio(primero);
        assertEquals(PENDIENTE, m.resultado);
        m.falloEnvio(segundo);
        assertEquals(FIN_NO_CONFIRMADO, m.resultado);
    }

    @Test public void alertasSoloPorCambioSinRepetirHeartbeatNavegacionNiReapertura() {
        ModeloEstacion m = modelo();
        estado(m, "ROPA_SECA", 0);
        assertEquals(0, m.revisionAviso);
        estado(m, "LLUVIA", 1);
        assertEquals(1, m.revisionAviso);
        estado(m, "LLUVIA", 2);
        assertEquals(1, m.revisionAviso);
        estado(m, "ROPA_SECA", 3);
        assertEquals(2, m.revisionAviso);
        m.desconectar();
        m.conectar();
        estado(m, "LLUVIA", 4);
        assertEquals(2, m.revisionAviso);
        estado(m, "ROPA_SECA", 15004);
        assertEquals(2, m.revisionAviso);
    }

    @Test public void estadosAnterioresAPublicarNoConfirmanAunqueCompartanMilisegundo() {
        for (ModeloEstacion.Comando comando : ModeloEstacion.Comando.values()) {
            ModeloEstacion m = modelo();
            String inicial = comando == INICIAR ? "ESPERA" : "LLUVIA";
            String compatible = comando == INICIAR ? "LLUVIA" : "ESPERA";
            estado(m, inicial, 10);
            long numero = m.solicitar(comando, 10);
            estado(m, compatible, 10); // Aún en cola, no hay publicación.
            assertEquals(PENDIENTE, m.resultado);
            assertNotNull(m.pendiente);
            m.publicado(numero, 10);
            m.recibir(ConfiguracionMqtt.ESTADO, compatible.getBytes(StandardCharsets.US_ASCII),
                    false, 10, numero - 1); // Capturado antes del envío, procesado después.
            assertEquals(PENDIENTE, m.resultado);
            estado(m, compatible, 10); // Capturado después del marcador, mismo reloj.
            assertEquals(CONFIRMADO, m.resultado);
        }
    }

    @Test public void plazoDeConfirmacionComienzaAlPublicarYNoSeReinicia() {
        ModeloEstacion m = modelo();
        estado(m, "ESPERA", 0);
        long numero = m.solicitar(INICIAR, 1);
        m.publicado(numero, 5000);
        estado(m, "ESPERA", 10000);
        m.publicado(numero, 14000);
        m.verificarPlazos(14999);
        assertEquals(PENDIENTE, m.resultado);
        m.verificarPlazos(15000);
        assertEquals(INICIO_NO_CONFIRMADO, m.resultado);
    }

    @Test public void colaVencidaNoSeReanimaConUnMarcadorDePublicacionTardio() {
        ModeloEstacion m = modelo();
        estado(m, "ESPERA", 0);
        long numero = m.solicitar(INICIAR, 1);
        m.verificarPlazos(10001);
        m.publicado(numero, 10002);
        estado(m, "LLUVIA", 10003);
        assertEquals(INICIO_NO_CONFIRMADO, m.resultado);
        assertFalse(m.publicado);
    }
}
