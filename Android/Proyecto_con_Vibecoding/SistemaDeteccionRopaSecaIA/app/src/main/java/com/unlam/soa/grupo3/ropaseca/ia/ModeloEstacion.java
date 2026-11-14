package com.unlam.soa.grupo3.ropaseca.ia;

import java.nio.charset.StandardCharsets;

// Solo se modifica en el hilo principal; los tiempos recibidos son monotónicos.
final class ModeloEstacion {
    enum Estado { DESCONOCIDO, ESPERA, MONITOREANDO_SECADO, ROPA_SECA, LLUVIA }
    enum Comando { INICIAR, FINALIZAR }
    enum Resultado { NINGUNO, PENDIENTE, CONFIRMADO, INICIO_NO_CONFIRMADO, FIN_NO_CONFIRMADO }

    boolean broker;
    Estado estado = Estado.DESCONOCIDO;
    Integer humedad;
    long ultimoEstado = -1;
    long ultimoSensor = -1;
    long revisionAviso;
    Resultado resultado = Resultado.NINGUNO;
    Comando pendiente;
    long inicioComando;
    long numeroComando;
    boolean publicado;
    private boolean estadoDeSesion;

    void conectar() {
        broker = true;
        estadoDeSesion = false;
        estado = Estado.DESCONOCIDO;
        ultimoEstado = -1;
        borrarHumedad();
    }

    void desconectar() {
        broker = false;
        estadoDeSesion = false;
        noConfirmado();
    }

    boolean estacionVigente(long ahora) {
        return broker && estadoDeSesion && ultimoEstado >= 0
                && ahora - ultimoEstado < ConfiguracionMqtt.VIGENCIA_MS;
    }

    boolean cicloActivo() {
        return estado != Estado.DESCONOCIDO && estado != Estado.ESPERA;
    }

    boolean lecturaVigente(long ahora) {
        return estacionVigente(ahora) && cicloActivo() && humedad != null
                && ahora - ultimoSensor < ConfiguracionMqtt.VIGENCIA_MS;
    }

    boolean controlHabilitado(long ahora) {
        return estacionVigente(ahora) && pendiente == null;
    }

    void verificarPlazos(long ahora) {
        if (!estacionVigente(ahora)) estadoDeSesion = false;
        if (pendiente != null && (!estacionVigente(ahora)
                || ahora - inicioComando >= (publicado ? ConfiguracionMqtt.CONFIRMACION_MS
                : ConfiguracionMqtt.MAXIMO_COLA_MS))) noConfirmado();
    }

    boolean recibir(String topic, byte[] bytes, boolean retenido, long ahora, long publicacionRecibida) {
        verificarPlazos(ahora);
        if (!broker || retenido || bytes == null || bytes.length == 0 || bytes.length > 32) return false;
        String texto = new String(bytes, StandardCharsets.US_ASCII);
        if (ConfiguracionMqtt.ESTADO.equals(topic)) {
            Estado nuevo;
            try {
                nuevo = Estado.valueOf(texto);
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (nuevo == Estado.DESCONOCIDO) return false;
            boolean anteriorVigente = estacionVigente(ahora);
            if (!anteriorVigente || nuevo == Estado.ESPERA || estado == Estado.ESPERA) borrarHumedad();
            if (anteriorVigente && nuevo != estado
                    && (nuevo == Estado.LLUVIA || nuevo == Estado.ROPA_SECA)) revisionAviso++;
            estado = nuevo;
            ultimoEstado = ahora;
            estadoDeSesion = true;
            if (pendiente != null && publicado && publicacionRecibida == numeroComando
                    && ((pendiente == Comando.INICIAR && cicloActivo())
                    || (pendiente == Comando.FINALIZAR && nuevo == Estado.ESPERA))) {
                pendiente = null;
                resultado = Resultado.CONFIRMADO;
            }
            return true;
        }
        if (ConfiguracionMqtt.SENSOR.equals(topic) && estacionVigente(ahora) && cicloActivo()) {
            if (bytes.length > 3) return false;
            int valor = 0;
            for (byte b : bytes) {
                if (b < '0' || b > '9') return false;
                valor = valor * 10 + b - '0';
            }
            if (valor > 100) return false;
            humedad = valor;
            ultimoSensor = ahora;
            return true;
        }
        return false;
    }

    long solicitar(Comando comando, long ahora) {
        verificarPlazos(ahora);
        if (!controlHabilitado(ahora) || comando == null
                || (comando == Comando.INICIAR) != (estado == Estado.ESPERA)) return -1;
        pendiente = comando;
        publicado = false;
        inicioComando = ahora;
        resultado = Resultado.PENDIENTE;
        return ++numeroComando;
    }

    void publicado(long numero, long ahora) {
        verificarPlazos(ahora);
        if (pendiente == null || numero != numeroComando || publicado) return;
        publicado = true;
        inicioComando = ahora;
    }

    void falloEnvio(long numero) {
        if (numero == numeroComando) noConfirmado();
    }

    private void noConfirmado() {
        if (pendiente == null) return;
        resultado = pendiente == Comando.FINALIZAR ? Resultado.FIN_NO_CONFIRMADO : Resultado.INICIO_NO_CONFIRMADO;
        pendiente = null;
    }

    private void borrarHumedad() {
        humedad = null;
        ultimoSensor = -1;
    }

    static int progreso(int humedad) {
        if (humedad < 0 || humedad > 100) throw new IllegalArgumentException("Humedad fuera de rango");
        return Math.min(100, (100 - humedad) * 100 / 80);
    }
}
