package com.unlam.soa.grupo3.ropaseca.ia;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.unlam.soa.grupo3.ropaseca.ia.Iluminacion.Nivel.*;

public class IluminacionTest {
    @Test
    public void clasificaLecturasRepresentativas() {
        assertEquals(MUY_POCA, Iluminacion.clasificar(0f));
        assertEquals(MUY_POCA, Iluminacion.clasificar(50f));
        assertEquals(POCA, Iluminacion.clasificar(250f));
        assertEquals(BUENA, Iluminacion.clasificar(2000f));
        assertEquals(EXCELENTE, Iluminacion.clasificar(Float.MAX_VALUE));
    }

    @Test
    public void respetaLimitesYFlotantesAdyacentes() {
        assertEquals(MUY_POCA, Iluminacion.clasificar(Math.nextDown(100f)));
        assertEquals(POCA, Iluminacion.clasificar(100f));
        assertEquals(POCA, Iluminacion.clasificar(Math.nextUp(100f)));
        assertEquals(POCA, Iluminacion.clasificar(Math.nextDown(500f)));
        assertEquals(BUENA, Iluminacion.clasificar(500f));
        assertEquals(BUENA, Iluminacion.clasificar(Math.nextUp(500f)));
        assertEquals(BUENA, Iluminacion.clasificar(Math.nextDown(10000f)));
        assertEquals(EXCELENTE, Iluminacion.clasificar(10000f));
        assertEquals(EXCELENTE, Iluminacion.clasificar(Math.nextUp(10000f)));
    }

    @Test
    public void rechazaLecturasInvalidas() {
        assertEquals(SIN_DATOS, Iluminacion.clasificar(-1f));
        assertEquals(SIN_DATOS, Iluminacion.clasificar(Float.NaN));
        assertEquals(SIN_DATOS, Iluminacion.clasificar(Float.POSITIVE_INFINITY));
        assertEquals(SIN_DATOS, Iluminacion.clasificar(Float.NEGATIVE_INFINITY));
    }
}
