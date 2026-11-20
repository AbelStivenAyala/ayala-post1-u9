package com.clinica.recordatorios.channel;

import com.clinica.recordatorios.domain.Cita;
import org.springframework.stereotype.Component;

@Component
public class EmailReminderChannel implements ReminderChannel {

    @Override
    public void enviar(Cita cita, String mensaje) {
        // En produccion: cliente SMTP real. Aqui se simula con una salida por consola.
        System.out.printf("[EMAIL a %s] %s%n", cita.getPacienteContacto(), mensaje);
    }
}
