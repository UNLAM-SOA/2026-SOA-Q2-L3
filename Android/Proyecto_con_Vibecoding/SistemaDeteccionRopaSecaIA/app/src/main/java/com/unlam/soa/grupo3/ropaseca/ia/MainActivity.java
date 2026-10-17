package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.Bundle;
import android.content.Intent;

public class MainActivity extends EstacionActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Pantalla.ajustarBarras(this);
        findViewById(R.id.btnMonitoreo).setOnClickListener(v ->
                startActivity(new Intent(this, MonitoreoActivity.class)));
        findViewById(R.id.btnCondiciones).setOnClickListener(v ->
                startActivity(new Intent(this, CondicionesActivity.class)));
        findViewById(R.id.btnConexion).setOnClickListener(v ->
                startActivity(new Intent(this, ConexionActivity.class)));
    }
}
