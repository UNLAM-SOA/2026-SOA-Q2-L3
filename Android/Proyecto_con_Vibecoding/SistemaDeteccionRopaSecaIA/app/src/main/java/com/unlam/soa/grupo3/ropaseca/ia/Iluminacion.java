package com.unlam.soa.grupo3.ropaseca.ia;

final class Iluminacion {
    static final float LIMITE_POCA = 100f;
    static final float LIMITE_BUENA = 500f;
    static final float LIMITE_EXCELENTE = 10000f;

    enum Nivel { SIN_DATOS, MUY_POCA, POCA, BUENA, EXCELENTE }

    private Iluminacion() { }

    static Nivel clasificar(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0) return Nivel.SIN_DATOS;
        if (lux < LIMITE_POCA) return Nivel.MUY_POCA;
        if (lux < LIMITE_BUENA) return Nivel.POCA;
        if (lux < LIMITE_EXCELENTE) return Nivel.BUENA;
        return Nivel.EXCELENTE;
    }
}
