package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.Bundle;
import android.os.SystemClock;

public class ConexionActivity extends EstacionActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conexion);
        Pantalla.ajustarBarras(this);
        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());
        texto(R.id.tvBrokerConfig, ConfiguracionMqtt.BROKER + ":" + ConfiguracionMqtt.PUERTO);
        texto(R.id.tvTopicsConfig, ConfiguracionMqtt.COMANDO + "\n"
                + ConfiguracionMqtt.ESTADO + "\n" + ConfiguracionMqtt.SENSOR);
    }

    @Override protected void actualizar() {
        super.actualizar();
        ModeloEstacion m = mqtt().modelo;
        long ahora = SystemClock.elapsedRealtime();
        texto(R.id.tvBroker, getString(m.broker ? R.string.broker_conectado : R.string.broker_desconectado));
        texto(R.id.tvEstacion, getString(m.estacionVigente(ahora)
                ? R.string.estacion_comunicando : R.string.estacion_sin_comunicacion));
        texto(R.id.tvUltimoEstado, getString(R.string.edad_estado, antiguedad(m.ultimoEstado, ahora)));
    }
}
