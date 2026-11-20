package com.clinica.recordatorios.channel;

import com.clinica.recordatorios.domain.Cita;

/** Strategy: cada canal de envio es una implementacion intercambiable. */
public interface ReminderChannel {
    void enviar(Cita cita, String mensaje);
}
