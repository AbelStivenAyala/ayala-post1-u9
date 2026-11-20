package com.clinica.recordatorios.channel;

import com.clinica.recordatorios.domain.CanalNotificacion;

/** Factory Method: decide en tiempo de ejecucion que Strategy entregar. */
public interface ReminderChannelFactory {
    ReminderChannel obtenerCanal(CanalNotificacion tipo);
}
