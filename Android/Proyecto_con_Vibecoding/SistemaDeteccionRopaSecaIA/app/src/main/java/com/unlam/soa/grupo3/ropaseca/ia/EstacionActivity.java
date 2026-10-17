package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.SystemClock;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

// Las cuatro pantallas comparten estado visible, no crean conexiones propias.
abstract class EstacionActivity extends AppCompatActivity {
    private final Runnable refrescar = this::actualizar;

    final MqttManager mqtt() { return ((RopaSecaApp) getApplication()).mqtt; }

    @Override protected void onStart() {
        super.onStart();
        mqtt().agregar(refrescar);
    }

    @Override protected void onStop() {
        mqtt().quitar(refrescar);
        super.onStop();
    }

    protected void actualizar() {
        ModeloEstacion m = mqtt().modelo;
        long ahora = SystemClock.elapsedRealtime();
        texto(R.id.tvConexionGlobal, getString(!m.broker ? R.string.broker_desconectado
                : m.estacionVigente(ahora) ? R.string.estacion_comunicando : R.string.estacion_sin_comunicacion));
        texto(R.id.tvCondicionGlobal, getString(!m.estacionVigente(ahora) ? R.string.condicion_desconocida
                : m.estado == ModeloEstacion.Estado.LLUVIA ? R.string.aviso_lluvia
                : m.estado == ModeloEstacion.Estado.ROPA_SECA ? R.string.aviso_seca
                : m.estado == ModeloEstacion.Estado.ESPERA ? R.string.aviso_espera : R.string.aviso_monitoreo));
        int resultado;
        switch (m.resultado) {
            case PENDIENTE: resultado = m.publicado ? R.string.comando_pendiente : R.string.comando_preparando; break;
            case CONFIRMADO: resultado = R.string.comando_confirmado; break;
            case INICIO_NO_CONFIRMADO: resultado = R.string.inicio_no_confirmado; break;
            case FIN_NO_CONFIRMADO: resultado = R.string.fin_no_confirmado; break;
            default: resultado = R.string.sin_comandos;
        }
        texto(R.id.tvResultadoComando, getString(resultado));
    }

    final void texto(int id, String texto) {
        TextView vista = findViewById(id);
        if (!texto.contentEquals(vista.getText())) vista.setText(texto);
    }

    final String antiguedad(long instante, long ahora) {
        return instante < 0 ? getString(R.string.sin_datos)
                : getString(R.string.hace_segundos, Math.max(0, ahora - instante) / 1000);
    }
}
