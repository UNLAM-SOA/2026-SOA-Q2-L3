package com.unlam.soa.grupo3.ropaseca.ia;

import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class PantallasTest {
    @Test public void navegaCuatroPantallasYVuelvePorBotonYAtras() {
        try (ActivityScenario<MainActivity> pantalla = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btnMonitoreo)).perform(scrollTo(), click());
            onView(withId(R.id.tvEstado)).check(matches(isDisplayed()));
            onView(withId(R.id.main)).perform(swipeUp());
            onView(withId(R.id.btnVolver)).perform(scrollTo(), click());
            onView(withId(R.id.btnCondiciones)).perform(scrollTo(), click());
            onView(withId(R.id.tvLux)).check(matches(isDisplayed()));
            pressBack();
            onView(withId(R.id.btnConexion)).perform(scrollTo(), click());
            onView(withId(R.id.tvBrokerConfig)).perform(scrollTo()).check(matches(withText(
                    ConfiguracionMqtt.BROKER + ":" + ConfiguracionMqtt.PUERTO)));
            onView(withId(R.id.main)).perform(swipeUp());
            onView(withId(R.id.btnVolver)).perform(scrollTo(), click());
            pantalla.recreate();
            onView(withId(R.id.btnMonitoreo)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void renderizaDatosVigenciaYControlConFixtureSinPublicarComandos() {
        try (ActivityScenario<MonitoreoActivity> pantalla = ActivityScenario.launch(MonitoreoActivity.class)) {
            pantalla.onActivity(activity -> {
                MqttManager mqtt = activity.mqtt();
                mqtt.detener(); // Invalida callbacks de la red real antes del fixture de UI.
                ModeloEstacion m = mqtt.modelo;
                m.conectar();
                activity.actualizar();
                Button control = activity.findViewById(R.id.btnCiclo);
                ProgressBar barra = activity.findViewById(R.id.progresoSecado);
                assertFalse(control.isEnabled());
                assertEquals(View.GONE, barra.getVisibility());
                long ahora = android.os.SystemClock.elapsedRealtime();
                m.recibir(ConfiguracionMqtt.ESTADO, "MONITOREANDO_SECADO".getBytes(StandardCharsets.US_ASCII), false, ahora, -1);
                m.recibir(ConfiguracionMqtt.SENSOR, "60".getBytes(StandardCharsets.US_ASCII), false, ahora, -1);
                activity.actualizar();
                assertTrue(control.isEnabled());
                assertEquals(activity.getString(R.string.finalizar), control.getText().toString());
                assertEquals(View.VISIBLE, barra.getVisibility());
                assertEquals(50, barra.getProgress());
                m.ultimoSensor = ahora - ConfiguracionMqtt.VIGENCIA_MS;
                activity.actualizar();
                assertEquals(View.GONE, barra.getVisibility());
                assertTrue(((TextView) activity.findViewById(R.id.tvHumedad)).getText().toString().contains("desactualizada"));
                m.desconectar();
                activity.actualizar();
                assertFalse(control.isEnabled());
                assertTrue(((TextView) activity.findViewById(R.id.tvEstado)).getText().toString().contains("desactualizado"));
            });
        }
    }
}
