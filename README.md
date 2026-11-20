# Recordatorios Citas Service

![CI](https://github.com/AbelStivenAyala/ayala-post1-u9/actions/workflows/ci.yml/badge.svg)

**Autor:** Abel Stiven Ayala Llanes
**Curso:** Patrones de Diseño de Software — UFPS

## Descripción

Repositorio del post-contenido de la Unidad 9. Un único proyecto Spring Boot que revisa qué citas médicas ocurren en las próximas 24 horas y no han sido notificadas, y envía un recordatorio por el canal preferido del paciente (correo o SMS). El servicio nace diseñado con **Strategy** (`ReminderChannel`, Unidad 4) y **Factory Method** (`ReminderChannelFactory`, Unidad 2), ambos inyectados por constructor junto con el repositorio y un `Clock`, aplicando el Principio de Inversión de Dependencias.

## Estructura del proyecto

```
src/main/java/com/clinica/recordatorios/
├── domain/        Cita, CanalNotificacion
├── repository/    CitaRepository
├── channel/       ReminderChannel (Strategy), Email/Sms, ReminderChannelFactory(Impl)
│   └── gateway/   NotificadorSmsGateway, TwilioNotificadorSmsGateway
├── service/       CitaReminderService(Impl), CitaNoEncontradaException
└── config/        ClockConfig, CanalUrgenteConfig  (composition root)

src/test/java/com/clinica/recordatorios/
├── service/       CitaReminderServiceImplTest             (unitarias: JUnit 5 + Mockito)
├── repository/    CitaRepositoryTest                      (@DataJpaTest con H2)
└── CitaReminderServiceIntegrationTest                     (@SpringBootTest con H2)
```

## Cómo ejecutar

Requisitos: JDK 21 y Maven 3.9+.

```bash
mvn test       # pruebas unitarias y de persistencia
mvn verify     # todo el ciclo, igual que en el pipeline de CI
```

Reporte de cobertura (opcional, perfil `cobertura`): `mvn verify -Pcobertura` genera `target/site/jacoco/index.html`.

## Herramientas utilizadas

- Java 21, Spring Boot 3.3.5, Spring Data JPA, H2, JUnit 5, Mockito
- Apache Maven, GitHub Actions, Git, GitHub

---

# Parte 1 — Pruebas unitarias con JUnit 5 y Mockito

`CitaReminderServiceImplTest` aísla `CitaReminderServiceImpl` de sus tres colaboradores. No toca ninguna base de datos ni envía ningún mensaje.

## Decisiones de diseño — Mock, Stub o Fake

| Colaborador | Test double | Justificación |
|---|---|---|
| `CitaRepository` | **Mock** | No basta con controlar lo que devuelve `findByFechaHoraCitaBetween...`: también hay que **verificar** que `save()` se llama exactamente una vez, con la cita ya marcada como enviada, y que no se llama cuando no hay citas pendientes. Cuando una prueba necesita controlar la respuesta *y* verificar la interacción, es un mock y no un simple stub. |
| `ReminderChannelFactory` y `ReminderChannel` | **Mock** | El comportamiento bajo prueba es justamente la selección de la estrategia: que el servicio pida el canal `EMAIL` cuando la cita lo prefiere y que ese canal reciba el mensaje. Eso solo se comprueba verificando interacciones (`obtenerCanal`, `enviar`), no con un valor de retorno. |
| `Clock` | **Fake** (`Clock.fixed(...)`) | `Clock.fixed` es una implementación real y completa de `java.time.Clock` provista por el JDK: `LocalDateTime.now(clock)` funciona exactamente igual que en producción, solo con el instante congelado. Mockearlo obligaría a stubbear `instant()` y `getZone()` por separado y arriesgaría que no se comporte como un reloj real. Con el fake, la ventana de 24 horas es determinista: `09:00 → 09:00 del día siguiente`, en cualquier máquina y a cualquier hora. |

## Qué patrón facilitó qué prueba

**Strategy (`ReminderChannel`).** Sin esta interfaz, la lógica de envío (SMTP o SMS) estaría escrita dentro del servicio, y probar la selección de canal exigiría mockear librerías de bajo nivel (un cliente SMTP, un cliente HTTP) en lugar de una sola interfaz propia con un método `enviar()`. Con Strategy, un único `@Mock ReminderChannel` basta para verificar cualquier canal: `enviarRecordatoriosPendientes_citaEnVentana_usaElCanalPreferido` verifica `enviar(eq(cita), anyString())` sin saber si detrás hay correo o SMS.

**Factory Method (`ReminderChannelFactory`).** Sin ella, el servicio tendría un `if/else` o un `switch` embebido para decidir el canal, y esa decisión solo sería observable de forma indirecta (revisando qué se imprimió o qué se envió realmente). Con la factory inyectada, la selección se verifica como una interacción explícita (`obtenerCanal(EMAIL)`), y el caso "sin citas pendientes" se prueba con `verifyNoInteractions(reminderChannelFactory)`.

**Inyección del `Clock` (DIP).** Si el servicio llamara a `LocalDateTime.now()` directamente, la prueba de la ventana de fechas daría un resultado distinto en cada ejecución. Al recibir el reloj por constructor, `enviarRecordatoriosPendientes_ventanaDeterministaSegunClockFijo_consultaLosLimitesExactos` puede afirmar los límites exactos con un `ArgumentCaptor`.

---

# Parte 2 — Refactor DIP, integración y CI/CD

## Diagnóstico: por qué `enviarRecordatorioUrgente` no se podía testear

La primera versión del método construía sus dependencias dentro del cuerpo:

```java
ReminderChannel canalUrgente = new SmsReminderChannel(new TwilioNotificadorSmsGateway());
```

Esa línea es la responsable. `SmsReminderChannel` y `TwilioNotificadorSmsGateway` se crean con `new` dentro del propio método, así que no existe ningún punto donde Mockito pueda insertar un test double: la prueba real enviaría un SMS de verdad (o tocaría el gateway real) y sería imposible simular el escenario "el gateway falla y la cita no debe marcarse como enviada". En cambio, `enviarRecordatoriosPendientes()` sí era testeable desde la Parte 1 porque recibe la factory y el reloj por constructor, y por eso la prueba puede entregarle dobles. Habría sido técnicamente posible interceptar ese `new` con `Mockito.mockConstruction()`, pero eso es una señal de que el problema es el diseño y no la herramienta: se resuelve con DIP, no con una API de pruebas más avanzada.

## El refactor: del `new` hardcodeado a la inyección por constructor

| Antes (Paso 7) | Después (Paso 8) |
|---|---|
| `new SmsReminderChannel(new TwilioNotificadorSmsGateway())` dentro del método | `ReminderChannel canalUrgente` recibido por constructor, con `@Qualifier("canalUrgente")` |
| Sin punto de sustitución para pruebas | Se sustituye por un mock en `@BeforeEach` |
| El servicio conoce las clases concretas | El servicio solo conoce la abstracción `ReminderChannel` |

El `new` no desaparece: alguien tiene que construir el objeto concreto. Se movió al único lugar donde es correcto escribirlo, el composition root (`config/CanalUrgenteConfig`), y el servicio recibe solo la abstracción. La prueba `enviarRecordatorioUrgente_gatewayFalla_noMarcaComoEnviado` no podía escribirse antes del refactor; ahora demuestra que la testabilidad es consecuencia directa de aplicar DIP.

## Pruebas de integración

- **`CitaRepositoryTest` (`@DataJpaTest`)**: levanta H2 en memoria sin configuración adicional y comprueba que la consulta derivada devuelve solo la cita pendiente dentro de la ventana, excluyendo la ya enviada y la que está fuera de ventana.
- **`CitaReminderServiceIntegrationTest` (`@SpringBootTest`)**: levanta el contexto completo y reemplaza solo lo estrictamente necesario: el `Clock` (para que la ventana sea determinista) y el `NotificadorSmsGateway` con `@MockBean`.

**Por qué H2 real pero gateway mockeado.** H2 es un fake aceptable porque su contrato SQL/JPA es equivalente al de la base real, sin infraestructura adicional. Un proveedor de SMS de terceros no tiene un equivalente gratuito y determinista; reemplazarlo con un mock evita depender de credenciales, de la red y de la disponibilidad del proveedor en cada ejecución del pipeline.

## Integración continua

El workflow `.github/workflows/ci.yml` compila y ejecuta `mvn -B verify` con JDK 21 en cada push y pull request a `main`. No incluye ningún servicio de base de datos, y eso es posible precisamente porque las pruebas de integración usan H2 en memoria: probar contra PostgreSQL con Testcontainers habría exigido un runner con Docker y tiempos de arranque mayores.

## Notas y limitaciones

- **Nombre del bean del `Clock` en la prueba de integración.** El `@TestConfiguration` declara el bean como `clockFijo` y no como `clock`, porque ese nombre ya lo usa `ClockConfig` y Spring Boot no permite sobrescribir definiciones de beans por defecto. Con `@Primary`, el reloj fijo gana la inyección.
- **El canal urgente es un segundo bean `SmsReminderChannel`.** `CanalUrgenteConfig` crea una instancia propia, distinta de la del `@Component`. Es intencional: así el canal urgente puede evolucionar (otro mensaje, otro gateway) sin tocar el canal normal, y el `@Qualifier` evita ambigüedad.
- **Sin Lombok.** `Cita` declara explícitamente constructores, getters y setters. Funcionalmente es equivalente a `@Data @NoArgsConstructor @AllArgsConstructor` y evita depender de la compatibilidad de Lombok con cada versión de JDK.
- **No hay endpoint HTTP ni planificador.** El servicio expone sus métodos, pero ningún controller ni `@Scheduled` los dispara; el laboratorio se centra en probar el diseño, no en el disparo periódico.

## Evidencia de pruebas

> Las capturas están en la carpeta `docs/`.

| Evidencia | Captura |
|---|---|
| `mvn test`: las 6 pruebas unitarias de `CitaReminderServiceImplTest` en verde (3 de la Parte 1 y 3 de la Parte 2) | ![unitarias](docs/01-mvn-test-unitarias.png) |
| `mvn verify`: unitarias, `@DataJpaTest` y `@SpringBootTest` en verde y `BUILD SUCCESS` | ![verify](docs/02-mvn-verify.png) |
| Pestaña Actions de GitHub con el workflow en Success | ![actions](docs/03-github-actions-success.png) |
| Reporte de cobertura (JaCoCo) | ![cobertura](docs/04-cobertura-jacoco.png) |

## Conclusiones

[Escribe aquí 3-5 oraciones con lo que aprendiste. Ideas: qué prueba habría sido imposible sin Strategy, Factory o la inyección del Clock; por qué el `new` dentro del método era un problema de diseño y no de herramienta; qué cambia al probar contra H2 en vez de la base real; y qué escenario te haría reconsiderar la decisión de mockear el gateway.]
