package com.unlam.soa.grupo3.ropaseca.ia;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

final class Pantalla {
    private Pantalla() { }

    static void ajustarBarras(AppCompatActivity activity) {
        EdgeToEdge.enable(activity);
        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(R.id.main), (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(activity.findViewById(R.id.main));
    }
}
