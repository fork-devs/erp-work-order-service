# Design

## Context

Estado actual, verificado sobre el repositorio reciente (`erp-work-order-service`, commit inicial `d546daf` en `main`, con `develop` y `feature/add-work-order-service` apiladas sobre él:

- **El esqueleto es arrancable pero divergente del criterio de la plataforma**: el paquete se generó como `com.erp.workorderservice` (los otros Java usan `com.erp.inventory`, `com.erp.fleet`), la configuración vive en un **`application.properties`** sin valor útil (`spring.application.name`), el test es un `contextLoads` vacío, y el `pom.xml` no declara actuator, Flyway, JaCoCo, validación ni Resilience4j. El `springdoc-openapi-starter-webmvc-ui` **3.1.0** sobre Boot 4.1.1 es la versión compatible (el 2.6.0 de otros servicios era el hallazgo 6.13).
- **El ADR-005 ya está escrito y committeado** (`docs/adr/ADR-005-transporte-interno-de-la-plataforma.md`, copia canónica del repositorio; espejo e índice ya en la raíz). El informe de estado ya lo reflejó (6.32 cerrado). No hay que escribirlo ni replicarlo: solo quedar prohibidas las copias divergentes durante la implementación.
- **Tabla de rutas y edges del gateway ya preparados**: `/api/v1/work-orders` → `:8088` con plazo de 500 ms; el gateway endurecido (`harden-gateway-edge`) ya trae hilos virtuales, circuito por destino y métricas por ruta (6.56–6.58 cerrados).
- **La saga del inventario ya está completa y verificada con su suite de concurrencia**: las tres operaciones expones por REST con `workOrderId` e `idempotencyKey` en el cuerpo — `POST /api/v1/inventory/stock/reserve`, `/commit`, `/release` — y roles de escritura `SUPERADMIN`/`ADMIN_TALLER`/`ASESOR`; el reclamo de la clave se hace en la base de datos y el bloqueo es `FOR UPDATE` por lote (ADR-004; el informe lo documenta así). La reserva **exige la sucursal del repuesto** (`branchId` es obligatorio en la entrada de reserva).
- **Fleet expone la consulta que la vinculación necesita**: `GET /api/v1/fleet/vehicles/{id}` (roles de lectura que incluyen `ADMIN_TALLER`/`ASESOR`), con el cliente del vehículo en la respuesta.
- **El informe de estado ya lista el patrón 7** («Saga orquetada — ⚪ — requiere `work-order` + `inventory` + `billing`») y la fila 6 de §2 como pendientes. También cita que la cadena de los servicios pendientes empieza aquí (`erp-work-order-service` → billing → iot → …), de modo que este change desbloquea los cuatro que siguen.

**Restricciones del entorno**: no hay Docker, de modo que los tests de integración corren contra **PostgreSQL embebido real** (así está construido el inventario en el mismo entorno). **Kafka todavía no existe**: el transporte de eventos sigue siendo el `EventPublisherPort` con registro del evento (el `KafkaTemplate` llega con el change que introduzca el bus), igual que en tenant, hub e inventario.

**Restricciones del idioma y el escenario de la plataforma** (contexto del proyecto): el tenant se deriva exclusivamente del token verificado; RLS con `FORCE ROW LEVEL SECURITY` y rol de ejecución que no es obtendedor; el registro de eventos va por **bandeja de salida** con reintentos y cola de muertos; sin separación de secretos en código versionado. El contexto de las rutas y roles ya fijado por la plataforma se conserva en este servicio.

## Goals / Non-Goals

**Goals:**

- Que el servicio sea **alcanzable, seguro y aislado** desde el primer día: token verificado, roles por endpoint, taller del token, RLS forzada y contexto de taller dentro de la transacción — heredando el patrón ya cerrado de `fix-tenant-isolation` en los seis servicios Java en pie.
- Que la **máquina de estados exista** como regla de dominio explícita: las transiciones declaradas, el rechazo explícito de la no permitida y la historia inmutable con su autor.
- Que la **saga esté basada en compensaciones completas**: una sola línea de ejecución orquestada por la orden (reservar → confirmar / liberar), con plazos, reintento, circuito y claves de idempotencia deterministas derivadas del id de la orden.
- Que la **numeración por taller** se gestione con la reserva antes de usar, criterio ADR-002, aplicado a las órdenes de trabajo (el correlativo **se reserva**, no se calcula al cerrar).
- Que los **eventos de la orden viajen en la misma transacción** del cambio de estado y se publiquen después con reintentos y cola de muertos.
- Que la **doc y el servicio no diverjan**: DBML §4 actualizado con la numeración reservada y el estado de la bandeja; ADR-005 verificado en el índice y en su espejo.

**Non-Goals:**

- **No** se implementa `erp-billing-service` ni ningún consumidor posterior de `WorkOrderCompleted`: este change emite el evento y lo deja en la bandeja, con el consumidor a cara del change del servicio que lo necesite.
- **No** se conecta a Kafka: sigue el transporte del `EventPublisherPort`.
- **No** se rehacen ni se re-proponen `add-fleet-service` ni `add-inventory-service` ni ningún contracto existente (ni el claim, ni el prefijo, ni el endpoint).
- **No** se migra el gateway: la ruta, el plazo y el circuito ya existen; este change no los toca.
- **No** se escribe el ADR-005: ya existe en el repositorio y es la copia canónica.

## Decisions

### D1. El paquete base es `com.erp.workorder`, no `com.erp.workorderservice`

**Decisión.** El esqueleto se renombra de `com.erp.workorderservice` a `com.erp.workorder`, alineado con `com.erp.inventory`, `com.erp.fleet` y los demás servicios Java de la plataforma.

**Alternativa descartada.** Mantener el nombre generado. La razón: el nombre del paquete es el identificador estable del servicio en la plataforma; `com.erp.workorderservice` queda incoherente con los demás (`com.erp.inventory` no es `com.erp.inventoryservice`) y complica los artefactos que generan nombres a partir de la convención (matriz de roles, doc-microservices, архитектura tests). Corregirlo con el servicio vacío es gratis; corregirlo después, caro.

**Consecuencia.** El `ErpWorkOrderServiceApplication` se mueve de paquete en el mismo commit del esqueleto corregido, y el test de arquitectura que exige la estructura de cuatro capas se escribe contra `com.erp.workorder`.

### D2. La máquina de estados vive en el dominio, y las transiciones son declaradas

**Decisión.** `WorkOrderStatus` es un enum de dominio sin anotaciones, con la tabla de **transiciones declaradas** como parte del propio tipo (`canTransitionTo`):

| Origen | Permite |
|:---|:---|
| `RECEPCIONADA` | `EN_DIAGNOSTICO`, `CANCELADA` |
| `EN_DIAGNOSTICO` | `COTIZADA`, `EN_EJECUCION`, `CANCELADA` |
| `COTIZADA` | `EN_EJECUCION`, `RECHAZADA`, `CANCELADA` |
| `EN_EJECUCION` | `EN_CONTROL_CALIDAD` |
| `EN_CONTROL_CALIDAD` | `LISTA`, `EN_EJECUCION` |
| `LISTA` | `ENTREGADA` |
| `ENTREGADA`, `RECHAZADA`, `CANCELADA` | — (terminales) |

La apreciación `EN_DIAGNOSTICO → EN_EJECUCION` existe para el trabajo **sin repuestos** (la orden avanza si no necesita cotizar nada); `EN_CONTROL_CALIDAD → EN_EJECUCION` existe para el hallazgo en control. Cualquier otra transición se rechaza de forma explícita con su error de dominio, sin generar una fila nueva en la historia.

**Alternativa descartada.** (a) **Tener solo el enum sin transiciones** y decidir el orden en los controladores. La razón: la regla sería eléma de dominio pero estaría escrita fuera del dominio; cada controlador decidiría por su cuenta y los tests de la máquina no serían posibles tipos fuertes. Es exactamente el defecto que el estado del inventario evitó al tener sus types con validación. (b) **A función de control en la base de datos** (CHECK/OBJECTS). La razón: la regla es de **proceso** (quién puede hacer cada cosa, y que el historial no registra una transición rechazada), no de dato estático; la base de datos no puede rechazar un COMMIT llamado desde un caso de uso con la prueba en la mano. (c) **Un motor de máquina de estados genérico** (Spring State Machine o similar). La razón: nueve estados y seis transiciones no necesitan un framework; la tabla declarada en un tipo de dominio es legible, sin dependencia nueva y sin pruebas de adorno.

**Consecuencia.** La tabla es de dominio, se puede testear práctica-puramente sin Spring, y las especificaciones de la spec delta («Se salta un estado», «Se reabre una orden entregada») son tests de dominio directas.

### D3. Las acciones de la saga se acoplan a las transiciones, no a los estados

**Decisión.** Las tres operaciones de la saga son **una parte del caso de uso de la transición** que les corresponde: `EN_DIAGNOSTICO → COTIZADA` reserva los repuestos; `COTIZADA → EN_EJECUCION` consume los reservados; `COTIZADA → RECHAZADA` y `COTIZADA → CANCELADA` liberan. La llamada al inventario ocurre **dentro de la misma transacción de la orden** (con el plazo del cliente); si falla, la transacción se revierte y el estado no cambia.

**Alternativa descartada.** (a) **Separar las llamadas de la saga de las transiciones**: primero cambiar el estado y luego, en un paso posterior, reservar/consumir. La razón: entre el avance del estado y la reserva, la orden puede quedar con repuestos en `COTIZADA` pero **sin reserva de stock**: el estado ya se avanzó, por lo que la cotización se «greita» aun sin disponibilidad, la saga entra en una óposición incompleta, y la recuperación a mano de dos modelos ya diverga. (b) **Que las transiciones se declaren y la saga la orquesta un componente aparte que escanea el estado nuevo**. La razón: es una segunda fuente de verdad (un orquetador extra fuera del caso de uso) y una carrera si dos transiciones siguen concurren.

**Consecuencia.** Cada transición de negocio es **un único `UnitOfWork`**: estado nuevo, fila de historia, escritura de la reserva (y el evento si corresponde) salen juntos — o no salen. Igual que la garantía de la idempotencia del inventario, la de esta saga es **atómica de observación**.

### D4. El correlativo por taller se **reserva** en el momento de abrir la orden, con el criterio del ADR-002

**Decision.** El correlativo de la orden se asigna al abrirla, en la misma transacción: se incrementa un **contador por taller** (`work_order_sequences` con una fila por `tenant_id`, bloqueada con `SELECT ... FOR UPDATE`), generando el número correlativo (`WO-000001`) y la orden se inserta con él. La unicidad `(tenant_id, number)` es la **defensa en profundidad**; el número de una orden cancelada **no** se reutiliza (el contador nunca retrocede).

**Alternativa descartada.** (a) **`COUNT(*)+1` sobre las filas de la orden al cerrar/desplegar la orden**. La razón: es exactamente el defecto que 6.43 cerró en el hub («sin numeración reservada»): dos órdenes abiertas a la vez reciben el mismo número, y si una se cancela el hueco se reutiliza, rompiendo el correlativo continuo. (b) **Un generador global de números** (secuencia genérica de `number`). La razón: el número es **por taller**; una secuencia global de PostgreSQL intercalaría filas de otros talleres y no reinicia por taller. (c) **Un UUID o un identificador aleatorio como número correlativo**. La razón: el número de la orden es el dato **humano** del taller (se comunican por él); un UUID no es correlativo.

**Consecuencia.** La numeración requiere una fila por taller en una tabla nueva (`work_order_sequences`); la refresca el servicio al abrir la orden si no existe (`ON CONFLICT DO NOTHING` para un taller sin órdenes anteriores). El `docs/doc-database-diagrams.md` §4 se actualiza con la tabla y la unicidad.

### D5. Las llamadas a los servicios se hacen con el token del principal, no con credenciales de servicio

**Decisión.** El cliente del inventario y el de fleet **reenvían el token verificado del principal** que está en la petición (el `ADMIN_TALLER`/`ASESOR` que coticé y apruebo). El taller del inventario y el de fleet se derivan de ese token con sus filtros ya existentes (sin pantalla adicional entre servicios); el trabajo de roles (los rollouts exactos que la saga exige) queda declarado también en los endpoints de la orden.

**Alternativa descartada.** (a) **Credenciales de servicio** (client credentials) para llamar al inventario. La razón: los roles del inventario en la saga (`SUPERADMIN`, `ADMIN_TALLER`, `ASESOR`) están declarados y verificados; un rol de máquina nuevo en el auth sería una pransa posible de permisos que en este cambio **no se exige** y agregaría un subject nuevo a la plataforma sin usarlo. (b) **Sin token** (llamada confiado en la red interna). La razón: el inventario validaría contra su JWT net — devuelve `401` a la llamada; y confiar en la red es el defecto P0 5.1/P0 5.2 que ya se cerró en todos los servicios.

**Consecuencia.** Si un `MECANICO` ficha la orden y intenta cotizar, el endpoint de la orden responde `403` antes de salir (rol del endpoint de la orden), y el propio inventario también lo rechazaría (再三 barrera). La propagación muestra una traza coherente: el mismo principal atraviesa todo el camino.

### D6. El cliente del inventario se envuelve con plazo, reintento y circuito igual que en el borde

**Decisión.** `infrastructure/client/InventoryClient` (y `FleetClient`) usa el **RestClient de Spring Boot 4.1.1** con: **timeout de 500 ms** en conexión y lectura (el plazo del doc §4), **un reintento con backoff** en errores transitorios, y **interruptor de circuito Resilience4j** (con las mismas reglas que el gateway endurecido en 6.57: circuito abierto → respuesta rápida con su código, no una espera del plazo). El reintento se hace **con la misma clave de idempotencia** derivada de la orden y del repuesto (`wo:{orderId}:reserve:{sparePartId}` — la unicidad del reclamo del inventario es `(tenant_id, idempotency_key)`, de modo que la clave distingue también el repuesto reservado), de modo que un resultado desconocido no duplica la reserva.

**Alternativa descartada.** (a) **Cloud OpenFeign o WebClient reactivo**. La razón: OpenFeign aporta una capa envolvente adicional sin las reglas del doc y permanece periférico a la plataforma; WebClient introduciría un modelo reactivo paralelo a un servicio MVC sin beneficio. (b) **gRPC para esta llamada**, como documentaba el ADR-003: descartado por el **ADR-005** con sus tres condiciones no satisfechas. (c) **No usar circuit breaker en el servicio** (delegar la resiliencia del circuito al gateway). La razón: el llamado del gateway solo cubre el borde; la llamada `work-order → inventory` está **dentro** de la plataforma, sin circuito del lado del wrapper del gateway — un inventario caído ahí afecta a la una llamada misma y no al path del cliente.

**Consecuencia.** Se añaden a `pom.xml` `io.github.resilience4j:resilience4j-spring-boot4` (compatible con Boot 4.1.1, como demostrado en el gateway endurecido, y sólo porque este servicio es el único de la plataforma que llama a otro).

### D7. La apertura de la orden verifica el vehículo contra fleet, y no confía en el payload

**Decisión.** Al abrir la orden, un `GET /api/v1/fleet/vehicles/{vehicle_id}` (con el token del principal) verifica que el vehículo existe en el taller del token y que su **cliente registrado** es el `customer_id` del payload; cualquiera de las dos condiciones falla, la apertura rechaza con su código de error (–la orden no se crea). Un fleet caído responde con un error de disponibilidad y la orden no se abre (fail-closed).

**Alternativa descartada.** (b) **Cachear la relación vehículo-cliente**. La razón: la plataforma ya procura la deduplicación en el hub para otro fin, y el cliente no está vinculado ahí; un cache local antes de una consulta enteramente a fleet introduce una segunda fuente de verdad con un sincronización adicional que este caso no exige.

**Consecuencia.** La ruta `work-order→fleet` de la matriz §4 queda implementada. La verificación es **de conexión síncrona** (una consulta a otro servicio) que se ejecuta en la misma MVC request de la apertura: el plazo se subsume al timeout de la llamada (no dentro del plazo del cliente del gateway).

### D8. La orden lleva la **sucursal** del repuesto, y esa columna es NOT NULL

**Decisión.** `work_orders` recibe una columna `branch_id UUID NOT NULL` como **referencia lógica** a la sucursal del taller contra la que se reservan los repuestos. La reserva, la confirmación y la liberación se hacen contra esa sucursal; una apertura sin `branch_id` rechaza con su código. La sucursal **no se valida su existencia en la base propia** del servicio (es lógica); su existencia se demostra con la propia llamada de reserva, que falla si no hay stock.

**Alternativa descartada.** (a) **No tener sucursal en la orden** y pedirla al flujo la cotización. La razón: el contrato de reserva del inventario requiere `branchId` — sin filas no hay reserva —, y el orden no deja sin sucursal exactamente al escenario de la espejo — a los repuestos del vehículo — que la spec exige al abrir la orden. (b) **Reservar sin sucursal** (contrato del inventario permite sin sucursal). La razón: el contrato fija y la espejo exige el dato; cambiarlo quebraría la API.

**Consecuencia.** El DBML §4 se actualiza con `work_orders.branch_id`.

### D9. El esqueleto se corrige (paquete, YAML y RLS), pero no se rehace

**Decisión.** El change **reescribe** el esqueleto que está mal (`com.erp.workorderservice` → `com.erp.workorder`, `application.properties` eliminado; única fuente de configuración es el YML — 7.24, las 76 hallazgos de la plataforma), y **construye sobre lo ya resuelto en los otros servicios**: RLS con `ENABLE` **y** `FORCE` sobre un rol no propietario, `SET LOCAL` dentro de la transacción (order de advices declarada), contexto de taller que **solo se restablece desde el propio principal verificado** (`TenantContextScope`, `TenantContextHolder`) y un **fail-closed** igual a los seis Java en pie; demarcación con el puerto `UnitOfWork` en la capa de aplicación, sin `@Transactional` en los adapters.

**Alternativa descartada.** (a) **Dejar el `application.properties`** y añadir un YAML al lado. La理由 (7.24): el preámbulo de Spring Boot carga *Properties* antes que YAML y el último contribuidor gana; el primero que escriba una clave en `.properties` **reemplaza el YAML silently** y aún puede ser el origen del hallazgo. (b) **Un nuevo estilo del Esqueleto para el work-order** (lo que integra con la planificación del gateway, que ya retrajo tal trabajo a un change). La razón: el esqueleto es nuevo, no arrastrado, y se corrige a la primera; el cambio se concentra en lo que falta.

**Consecuencia.** El `ddl-auto: validate` y `baseline-on-migrate: false`, y las pruebas se hachan contra **PostgreSQL embebido con las migraciones aplicadas** (regla del contexto no cumplida en el esqueleto; hallazgo 7.16 cerrado aquí), como el inventario.

### D10. Los eventos de la orden viajan en la bandeja, con la misma forma que el inventario

**Decisión.** `work_order_outbox` replica la forma madura del outbox (`event_id UUID` **único**, `estado`, `attempts`, `next_attempt_at`, `payload`), con la **carga útil con su evento** para deduplicación por consumidor, y el relay retado con reintentos, backoff creciente en `next_attempt_at` y cola de muertos consultable y reactivable (el defecto de trabajo de fila a fila y de cola sin bloqueo ya corregido en el inventario y como hallazgo 6.8). El transporte lo resuelve el `EventPublisherPort` con registro del evento publicado (no hay Kafka en la plataforma todavía).

**Backup — Alternativa descartada.** (a) **El `published boolean` del DBML original**. La razón: la columna no permite ni intentos ni el reintento programado (el hallazgo 7.21 lo citó): un consumidor no puede deduplicar sin `event_id` y el relay no sabe si el reintento n-simo no finaliza sin reintentos. El DBML §4 se actualiza con la forma madura. (b) **Publicar desde el commit del caso de uso**. La razón: el evento se publicaría aunque la transacción de negocio se revierta (o viceversa) — el defecto que `fix-transactional-outbox` cerró.

**Ajuste del grupo 7.** La bandeja es la **cola interna del servicio**: la migración V3 retira el aislamiento por taller de `work_order_outbox` (el mismo criterio de `stock_outbox` y `tenant_outbox`) porque el relay recorre los pendientes de **todos** los talleres y ningún endpoint la expone a los talleres; el aislamiento por taller vive en las tablas que el taller lee (`work_orders`, ítems, historia, numeración), que conservan su RLS forzada.

**Consecuencia.** El payload del outbox (que sea `WorkOrderCompleted` en `LISTA → ENTREGADA` y `WorkOrderCancelled` en la decisión de cancelación declarada) es **una escritura más dentro del `UnitOfWork` de la transición**, junto al estado y la historia.

### D11. La API usa el prefijo del gateway y declara el rol por endpoint, no por prefijo

**Decisión.** Los controladores expone la API bajo `api/v1/work-orders` —la ruta que el gateway ya reenvía—: listado, detalle con ítems y estado, historia, y **transición explícita** (`POST /api/v1/work-orders/{id}/transition` con el estado de destino y las reglas de negocio propias del paso — el diagnóstico, la cotización, la aprobación o el rechazo/la cancelación. Escritura exige `SUPERADMIN`/`ADMIN_TALLER`/`ASESOR`; lectura admite también `MECANICO`.

**Backup — Alternativa descartada.** (a) **Un solo rol en el borde del prefijo** (`/api/v1/work-orders` requiere `ADMIN_TALLER`). La razón: la lectura es necesaria para `MECANICO` y para `ASESOR`, y prohibirlos con un prefijo único los estaca; el rol fino lo aplica el servicio de destino, como en los seis Java. (b) **Transición «automática»** a partir de acciones separadas (cotizar entérate de la transición a `COTIZADA`, aprobar la de `EN_EJECUCION`, …) sin exponer la máquina. La razón: los SEIS estado de `COTIZADA` que el doc/perfil del taller permite son **la misma acción** con distinto contenido de negocio; exponer el punto de transición única con las reglas que le corresponden mantiene la máquina de estados obscura de acuerdo a las transiciones declaradas en la spec.

**Consecuencia.** La **matriz de roles** se genera con el `verificar-matriz-roles.py` de la plataforma (hallazgo 7.5/9.8) y su extensión al nuevo servicio es un task.

### D12. Observabilidad, entrega y doc de la misma entrega

**Decisión.** El mismo paquete que ya existe en los servicios en pie: `application/logging/SecurityEventLogger` con el esquema común (sin paramétros para credenciales); actuator `/health` y `/ready` con indicadores que comprueban **PostgreSQL real** (no un ping abstracto), habilitados hasta con el arranque; `Dockerfile`, sin secretos y `compose.yaml` sin contraseñas iniciales; a los secretos resueltos por variables de entorno y el arranque fallando ruidoso si falta alguna; perfil `prod` explícito (7.3), JaCoCo al **80%** en `domain/` (7.2), suite cross-tenant obligatoria (regla del contexto) y `openspec init` en el repositorio.

**Alternativa descartada.** (a) **Desplegar de la observabilidad** para «un cambio posterior» y simplificar el esqueleto. La razón: es exactamente el hallazgo 7.8 («`/actuator/health` solo valida el propio proceso»), que se cerró en los otros; el servicio nuevo **no hereda deuda de observabilidad**. (b) **No inicializar OpenSpec en el repositorio del servicio**. La razón: los seis services existentes lo tienen y el workflow de la plataforma **lo exige** al archivar el cambio.

**Consecuencia.** El `docs/doc-estado-implementacion.md` actualiza la fila 6 de §2 (⚫→🟢), el patrón 7 de §3 (⚪→🟢) y el checklist de Sprint 4+ en la misma entrega.

## Risks / Trade-offs

- **[La saga sincrona acopla la disponibilidad de la cotización a la del inventario]** → es el precio de la **consistencia ordenada** (el doc lo exige: la orden no avanza sin reserva); el circuito y la cola de muertos de la bandeja de la orden no pueden entrar aquí. La compensación (liberar) es idempotente, así que una reapertura de la saga puede lanzarse sin añadir estado.
- **[El corollario por taller bloqueado con `FOR UPDATE` serializa la apertura de órdenes del mismo taller]** → la contienda es **por taller** (lead en `work_order_sequences`): las aperturas de talleres distintos en un bloqueo distinto. La espera del bloqueo es la del plazo de lamvc request, y un fallo de bloqueo es un error explícito, no un hang silencioso.
- **[La llamada a fleet en la apertura es una segunda llamada síncrona en el camino crítico]** → con el mismo plazo (500 ms) y el mismo circuito; el fail-closed es **por diseño** (sin fleet no hay 我国 orden nueva), y la tasa de fallo del circuito calab fazerla visible por métrica antes que por ticket.
- **[La forma madura del outbox rompe el DBML documentado]** → la actualización del **doc-database-diagrams §4** es un task de esta entrega, igual que el inventario lo hizo con el suyo; el DBML no la usa nadie más que la documentación.
- **[El paquete del esqueleto cambia con el servicio ya en un commit de la rama]** → es un **cambio en la historia de commits** deliberado a antes de la implementación: `com.erp.workorderservice` no tiene funcionionalidad alguna; la renombración es parte del task 1.2 y no influe a los joints de la plataforma.
- **[La suite de PostgreSQL embebido es más lenta que la H2]** → mejor inmutibbean que prueba inválida: el criterio de la plataforma (7.16) exige las mismas migraciones en la suite, y en este entorno es la única forma que garantiza la RLS y la deriva.
- **[El claim roles viene como claim plural `roles` de la plataforma]** → se replica la convención ya cerrada por `fix-identity-lifecycle`: sin claim `roles`, la petición de buena fe es rejectada y ningún rol por defecto se otorga.

## Migration Plan

1. **Rama de trabajo**: `feature/add-work-order-service` apilada sobre `develop`, sin merges hasta el cierre; **ninguna** rama adicional.
2. **Esqueleto corregido** (un commit): paquete `com.erp.workorder`, YAML único, dependencias nuevas (actuator, Flyway, JaCoCo, validación, Resilience4j, springdoc ya está, PostgreSQL embebido en test).
3. **Esquema, un commit por migración**: `V1__work_orders.sql` (las cuatro tablas + `branch_id` + RLS forzada con roles), y `V2__numeracion_por_taller.sql` (contador por taller). Verificaciones contra PostgreSQL real (embebido en suite): la política con `relforcerowsecurity = true`, la unicidad `(tenant_id, number)` y la numeración.
4. **Dominio primero** (máquina de estados, modelo con sus objetos de valor, sin framework), **aplicación después** (casos de uso de las acciones y las transiciones con `UnitOfWork`), **infraestructura al final** (clientes, RLS, bandeja, relay).
5. **Endpoints** (listado, detalle, historia, transición) + matriz de roles y su verificador; userDao de la **específica**: cada tentativa de la spec (número, transición, saga, aislamiento, outbox) es la prueba que la cierra.
6. **Cierre** (docs: DBML §4, estado-implementación fila 6 y patrón 7; `openspec init` en el repositorio).
7. **Rollback**: el servicio no está desplegado con datos reales — revertir es quedarse con el commit anterior de la rama; las migraciones son aditivas y no rompen en caliente; la ruta del gateway (con el 8088 y el plazo de 500 ms) ya existe antes de este change y no se toca.

## Open Questions

- **¿Quién consume `WorkOrderCompleted`?** El cliente natural es `erp-billing-service` (proforma pre llamada a SUNAT para el comprobante por la orden), no construido. La bandeja es el punto de integración, y el change del billing decidirá su consumo (con o sin deduplicación específica); nada en este change depende de la elección.
- **¿Se expone el correlativo como algo más que un string legible?** Se ha mantenido el criterio del ADR-002 (formato humano alrededor del correlativo, con el esquema del taller). La fecha del correlativo y un formato local per custom (y no per-taller una masa es el string) puede decidirse cuando el taller requiera, ya que el campo es un `varchar` y el cambio no afecta la unicidad. Nada de esto cambia la spec.
- **¿La apertura de órdenes limita la cantidad de ítems?** La spec no pide un週 máximo; se ha deja abierto para un escenario de pago a largo plazo (una cotización excleta con cientos de ítems), como dataset futuro. Si el taller lo exige, es una regla de negocio adicional, no un cambio de la máquina de estados ni de la bagde de salida.
