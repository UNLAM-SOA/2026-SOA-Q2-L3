package com.unlam.soa.grupo3.ropaseca.ia;

final class ConfiguracionMqtt {
    static final String BROKER = "broker.emqx.io";
    static final int PUERTO = 1883;
    static final String URI = "tcp://" + BROKER + ":" + PUERTO;
    static final String PREFIJO = "unlam/soa/grupo3/lavadero-ia/";
    static final String ESTADO = PREFIJO + "estado";
    static final String SENSOR = PREFIJO + "sensor";
    static final String COMANDO = PREFIJO + "comando";
    static final long VIGENCIA_MS = 15000;
    static final long CONFIRMACION_MS = 10000;
    static final long MAXIMO_COLA_MS = 10000;
    static final long REINTENTO_MS = 5000;
    static final int CONEXION_TIMEOUT_S = 5;
    static final int KEEPALIVE_S = 5;
    static final long OPERACION_TIMEOUT_MS = 3000;

    private ConfiguracionMqtt() { }
}
