package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;

public class MonitoreoActivity extends EstacionActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitoreo);
        Pantalla.ajustarBarras(this);
        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());
        findViewById(R.id.btnCiclo).setOnClickListener(v -> mqtt().enviar(
                mqtt().modelo.estado == ModeloEstacion.Estado.ESPERA
                        ? ModeloEstacion.Comando.INICIAR : ModeloEstacion.Comando.FINALIZAR));
    }

    @Override protected void actualizar() {
        super.actualizar();
        ModeloEstacion m = mqtt().modelo;
        long ahora = SystemClock.elapsedRealtime();
        boolean vigente = m.estacionVigente(ahora);
        String estado;
        switch (m.estado) {
            case ESPERA: estado = getString(R.string.estado_espera); break;
            case MONITOREANDO_SECADO: estado = getString(R.string.estado_monitoreo); break;
            case ROPA_SECA: estado = getString(R.string.estado_seca); break;
            case LLUVIA: estado = getString(R.string.estado_lluvia); break;
            default: estado = getString(R.string.sin_datos);
        }
        texto(R.id.tvEstado, m.estado == ModeloEstacion.Estado.DESCONOCIDO
                ? getString(R.string.estado_desconocido)
                : getString(vigente ? R.string.estado_actual : R.string.estado_anterior, estado));
        Button control = findViewById(R.id.btnCiclo);
        control.setEnabled(m.controlHabilitado(ahora));
        texto(R.id.btnCiclo, getString(m.estado == ModeloEstacion.Estado.DESCONOCIDO ? R.string.control_ciclo
                : m.estado == ModeloEstacion.Estado.ESPERA ? R.string.iniciar : R.string.finalizar));
        texto(R.id.tvControl, getString(!m.controlHabilitado(ahora) ? R.string.control_no_disponible
                : m.estado == ModeloEstacion.Estado.ESPERA ? R.string.aviso_espera : R.string.control_activo));
        boolean lecturaVigente = m.lecturaVigente(ahora);
        texto(R.id.tvHumedad, m.humedad == null ? getString(R.string.humedad_sin_datos)
                : getString(lecturaVigente ? R.string.humedad_actual : R.string.humedad_anterior, m.humedad));
        texto(R.id.tvProgreso, lecturaVigente
                ? getString(R.string.progreso_actual, ModeloEstacion.progreso(m.humedad))
                : getString(R.string.progreso_sin_datos));
        ProgressBar barra = findViewById(R.id.progresoSecado);
        barra.setVisibility(lecturaVigente ? View.VISIBLE : View.GONE);
        if (lecturaVigente) barra.setProgress(ModeloEstacion.progreso(m.humedad));
        texto(R.id.tvAntiguedad, getString(R.string.edad_lectura, antiguedad(m.ultimoSensor, ahora)));
        texto(R.id.tvLluvia, getString(!vigente || !m.cicloActivo() ? R.string.lluvia_sin_datos
                : m.estado == ModeloEstacion.Estado.LLUVIA ? R.string.aviso_lluvia : R.string.sin_alerta_lluvia));
        texto(R.id.tvRopaSeca, getString(!vigente || !m.cicloActivo() || m.estado == ModeloEstacion.Estado.LLUVIA
                ? R.string.seca_sin_datos : m.estado == ModeloEstacion.Estado.ROPA_SECA
                ? R.string.aviso_seca : R.string.sin_alerta_seca));
    }
}
