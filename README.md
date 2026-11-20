Recordatorios Citas Service


Autor: Abel Stiven Ayala Llanes
Curso: Patrones de Diseño de Software — UFPS

Descripción

Repositorio del post-contenido de la Unidad 9. Un único proyecto Spring Boot que revisa qué citas médicas ocurren en las próximas 24 horas y no han sido notificadas, y envía un recordatorio por el canal preferido del paciente (correo o SMS). El servicio nace diseñado con Strategy (ReminderChannel, Unidad 4) y Factory Method (ReminderChannelFactory, Unidad 2), ambos inyectados por constructor junto con el repositorio y un Clock, aplicando el Principio de Inversión de Dependencias (DIP).

El trabajo tiene dos partes sobre el mismo proyecto:

Parte 1: suite de pruebas unitarias con JUnit 5 y Mockito, decidiendo y justificando qué test double (mock, stub o fake) corresponde a cada colaborador.
Parte 2: refactor de una funcionalidad que viola DIP para habilitarla para pruebas, pruebas de integración con H2 y un pipeline de integración continua con GitHub Actions.
Estructura del proyecto
src/main/java/com/clinica/recordatorios/
├── domain/        Cita, CanalNotificacion
├── repository/    CitaRepository
├── channel/       ReminderChannel (Strategy), EmailReminderChannel, SmsReminderChannel,
│                  ReminderChannelFactory, ReminderChannelFactoryImpl
│   └── gateway/   NotificadorSmsGateway, TwilioNotificadorSmsGateway
├── service/       CitaReminderService, CitaReminderServiceImpl, CitaNoEncontradaException
└── config/        ClockConfig, CanalUrgenteConfig   (composition root)

src/test/java/com/clinica/recordatorios/
├── service/       CitaReminderServiceImplTest          6 pruebas unitarias (JUnit 5 + Mockito)
├── repository/    CitaRepositoryTest                   1 prueba @DataJpaTest con H2
└── CitaReminderServiceIntegrationTest                  1 prueba @SpringBootTest con H2

.github/workflows/ci.yml                                pipeline de GitHub Actions
Cómo ejecutar

Requisitos: JDK 21 y Maven 3.9+.

bash
mvn test       # pruebas unitarias y de persistencia
mvn verify     # ciclo completo, igual que en el pipeline de CI

Reporte de cobertura (opcional, perfil cobertura): mvn verify -Pcobertura genera target/site/jacoco/index.html.

Herramientas utilizadas
Java 21, Spring Boot 3.3.5, Spring Data JPA, H2, JUnit 5, Mockito
Apache Maven, GitHub Actions, Git, GitHub
Parte 1 — Pruebas unitarias con JUnit 5 y Mockito

CitaReminderServiceImplTest aísla CitaReminderServiceImpl de sus colaboradores. Estas pruebas no tocan ninguna base de datos ni envían ningún mensaje.

Decisiones de diseño: Mock, Stub o Fake
Colaborador	Test double	Justificación
CitaRepository	Mock	No basta con controlar lo que devuelve findByFechaHoraCitaBetween...: también hay que verificar que save() se llama exactamente una vez, con la cita ya marcada como enviada, y que no se llama cuando no hay citas pendientes. Cuando una prueba necesita controlar la respuesta y verificar la interacción, es un mock y no un simple stub.
ReminderChannelFactory y ReminderChannel	Mock	El comportamiento bajo prueba es justamente la selección de la estrategia: que el servicio pida el canal EMAIL cuando la cita lo prefiere y que ese canal reciba el mensaje. Eso solo se comprueba verificando interacciones (obtenerCanal, enviar), no con un valor de retorno.
Clock	Fake (Clock.fixed(...))	Clock.fixed es una implementación real y completa de java.time.Clock provista por el JDK: LocalDateTime.now(clock) funciona igual que en producción, solo con el instante congelado. Mockearlo obligaría a stubbear instant() y getZone() por separado y arriesgaría que no se comporte como un reloj real. Con el fake, la ventana de 24 horas es determinista: de 09:00 a 09:00 del día siguiente, en cualquier máquina y a cualquier hora.
Qué patrón facilitó qué prueba

Strategy (ReminderChannel). Sin esta interfaz, la lógica de envío (SMTP o SMS) estaría escrita dentro del servicio, y probar la selección de canal exigiría mockear librerías de bajo nivel (un cliente SMTP, un cliente HTTP) en lugar de una sola interfaz propia con un método enviar(). Con Strategy, un único @Mock ReminderChannel basta para verificar cualquier canal: la prueba enviarRecordatoriosPendientes_citaEnVentana_usaElCanalPreferido verifica enviar(eq(cita), anyString()) sin saber si detrás hay correo o SMS.

Factory Method (ReminderChannelFactory). Sin ella, el servicio tendría un if/else o un switch embebido para decidir el canal, y esa decisión solo sería observable de forma indirecta (revisando qué se imprimió o qué se envió realmente). Con la factory inyectada, la selección se verifica como una interacción explícita (obtenerCanal(EMAIL)), y el caso "sin citas pendientes" se prueba con verifyNoInteractions(reminderChannelFactory).

Inyección del Clock (DIP). Si el servicio llamara a LocalDateTime.now() directamente, la prueba de la ventana de fechas daría un resultado distinto en cada ejecución. Al recibir el reloj por constructor, la prueba enviarRecordatoriosPendientes_ventanaDeterministaSegunClockFijo_consultaLosLimitesExactos puede afirmar los límites exactos capturándolos con un ArgumentCaptor.

Parte 2 — Refactor DIP, integración y CI/CD
Diagnóstico: por qué enviarRecordatorioUrgente no se podía testear

La primera versión del método construía sus dependencias dentro del cuerpo:

java
ReminderChannel canalUrgente = new SmsReminderChannel(new TwilioNotificadorSmsGateway());

Esa línea es la responsable. SmsReminderChannel y TwilioNotificadorSmsGateway se crean con new dentro del propio método, así que no existe ningún punto donde Mockito pueda insertar un test double: una prueba real usaría el gateway real, y sería imposible simular el escenario "el gateway falla y la cita no debe marcarse como enviada".

En cambio, enviarRecordatoriosPendientes() sí era testeable desde la Parte 1, porque recibe la factory y el reloj por constructor y la prueba puede entregarle dobles. Habría sido técnicamente posible interceptar el new con Mockito.mockConstruction(), pero eso es una señal de que el problema es el diseño y no la herramienta: se resuelve con DIP, no con una API de pruebas más avanzada.

El refactor: del new hardcodeado a la inyección por constructor
Antes (versión con problema)	Después (refactor)
new SmsReminderChannel(new TwilioNotificadorSmsGateway()) dentro del método	ReminderChannel canalUrgente recibido por constructor, con @Qualifier("canalUrgente")
Sin punto de sustitución para pruebas	Se sustituye por un mock en el @BeforeEach
El servicio conoce las clases concretas	El servicio solo conoce la abstracción ReminderChannel

El new no desaparece: alguien tiene que construir el objeto concreto. Se movió al único lugar donde es correcto escribirlo, el composition root (config/CanalUrgenteConfig), y el servicio recibe solo la abstracción.

La prueba enviarRecordatorioUrgente_gatewayFalla_noMarcaComoEnviado era imposible de escribir antes del refactor. Ahora demuestra que la testabilidad es consecuencia directa de aplicar DIP: el mock del canal lanza una excepción y se verifica que save() nunca se llama.

Pruebas de integración
CitaRepositoryTest (@DataJpaTest): levanta H2 en memoria sin configuración adicional y comprueba que la consulta derivada devuelve solo la cita pendiente dentro de la ventana, excluyendo la que ya fue enviada y la que está fuera de ventana.
CitaReminderServiceIntegrationTest (@SpringBootTest): levanta el contexto completo y reemplaza solo lo estrictamente necesario: el Clock, para que la ventana sea determinista, y el NotificadorSmsGateway con @MockBean.

Por qué H2 real pero gateway mockeado. H2 es un fake aceptable porque su contrato SQL/JPA es equivalente al de la base real, sin infraestructura adicional. Un proveedor de SMS de terceros no tiene un equivalente gratuito y determinista; reemplazarlo con un mock evita depender de credenciales, de la red y de la disponibilidad del proveedor en cada ejecución del pipeline.

Integración continua

El workflow .github/workflows/ci.yml compila y ejecuta mvn -B verify con JDK 21 en cada push y pull request a main. No incluye ningún servicio de base de datos, y eso es posible precisamente porque las pruebas de integración usan H2 en memoria: probar contra PostgreSQL con Testcontainers habría exigido un runner con Docker disponible y tiempos de arranque mayores.

Notas y limitaciones
Nombre del bean del Clock en la prueba de integración. El @TestConfiguration declara el bean como clockFijo y no como clock, porque ese nombre ya lo usa ClockConfig y Spring Boot no permite sobrescribir definiciones de beans por defecto. Con @Primary, el reloj fijo gana la inyección.
El canal urgente es una segunda instancia de SmsReminderChannel. CanalUrgenteConfig crea una instancia propia, distinta de la del @Component. Es intencional: así el canal urgente puede evolucionar (otro mensaje, otro gateway) sin tocar el canal normal, y el @Qualifier evita ambigüedad.
Sin Lombok. Cita declara explícitamente constructores, getters y setters. Es equivalente a @Data @NoArgsConstructor @AllArgsConstructor y evita depender de la compatibilidad de Lombok con cada versión de JDK.
Configuración de Mockito en JDK recientes. El pom.xml define un argLine con opciones de ByteBuddy y de carga dinámica de agentes. Las pruebas se ejecutaron localmente con un JDK más nuevo que el 21; el pipeline usa JDK 21.
No hay endpoint HTTP ni planificador. El servicio expone sus métodos, pero ningún controller ni @Scheduled los dispara: el laboratorio se centra en probar el diseño, no en el disparo periódico.
Evidencia de pruebas

Las capturas están en la carpeta capturas/.

Evidencia	Captura
mvn test: las 6 pruebas unitarias de CitaReminderServiceImplTest en verde (3 de la Parte 1 y 3 de la Parte 2)	pruebas unitarias
mvn verify: 8 pruebas en verde (unitarias, @DataJpaTest y @SpringBootTest) y BUILD SUCCESS	mvn verify
Pestaña Actions de GitHub con el workflow en Success	github actions
Reporte de cobertura (JaCoCo)	cobertura

Conclusiones

La inyección por constructor fue lo que hizo posible probar este servicio: gracias a Strategy, Factory y la inyección del Clock, cada colaborador se pudo reemplazar por un test double sin tocar el código de producción. Lo que más me enseñó el laboratorio fue ver que la prueba del gateway que falla era imposible de escribir mientras el método usaba new, y que la solución no fue una herramienta más potente sino mover esa construcción al composition root. También aprendí a distinguir cuándo corresponde un mock y cuándo un fake: Clock.fixed era más simple y más fiel que cualquier mock. Combinar H2 real con un gateway mockeado me mostró que cada doble se elige según lo que se puede reproducir de forma determinista, y esa misma decisión permitió que el pipeline de CI no necesitara ningún contenedor de base de datos. Reconsideraría mockear la base de datos si el modelo dependiera de funciones propias de un motor concreto, porque ahí H2 dejaría de ser un fake equivalente.