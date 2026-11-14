package com.unlam.soa.grupo3.ropaseca.ia;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.google.android.material.snackbar.Snackbar;

public class RopaSecaApp extends Application implements Application.ActivityLifecycleCallbacks {
    final MqttManager mqtt = new MqttManager();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable detener = () -> mqtt.detener();
    private int pantallasVisibles;
    private Activity actual;
    private long ultimoAviso;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        mqtt.agregar(() -> {
            ModeloEstacion m = mqtt.modelo;
            if (m.revisionAviso == ultimoAviso) return;
            ultimoAviso = m.revisionAviso;
            if (actual != null) Snackbar.make(actual.findViewById(R.id.main),
                    m.estado == ModeloEstacion.Estado.LLUVIA ? R.string.aviso_lluvia : R.string.aviso_seca,
                    Snackbar.LENGTH_LONG).show();
        });
    }

    @Override public void onActivityStarted(Activity activity) {
        main.removeCallbacks(detener);
        if (pantallasVisibles++ == 0) mqtt.iniciar();
    }

    @Override public void onActivityStopped(Activity activity) {
        // La gracia evita cortar la conexión durante navegación o recreación.
        if (--pantallasVisibles == 0) main.postDelayed(detener, 500);
    }

    @Override public void onActivityResumed(Activity activity) { actual = activity; }
    @Override public void onActivityPaused(Activity activity) { if (actual == activity) actual = null; }
    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
