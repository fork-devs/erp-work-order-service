# Proposal

## Why

`erp-work-order-service` **no existe**. El repositorio (`erp-work-order-service`) quedó recién inicializado —un commit de andamiaje con su `pom.xml`, un main vacío y ni siquiera un `application.yml`— y sin él el taller **no puede trabajar**: no hay quien reciba el vehículo, lo diagnostique, cotice, reserve los repuestos, ejecute la reparación y la entregue. El reporte de estado lo recoge en la **fila 6 de §2** («`operations-service` (work-order) — ⚫ No existe — la ruta del gateway apunta a `:8088`, puerto reservado») y en el **patrón 7 de §3** («Saga orquestada — ⚪ — requiere `work-order` + `inventory` + `billing`»), dos entradas abiertas de §2 y §3 que solo este change puede cerrar: el inventario ya expone la saga de stock (`ReserveStock`, `CommitStock`, `ReleaseStock`, idempotentes y con la clave reclamada en la base de datos), pero **falta el orquestador** —quién llama, en qué orden y con qué compensación—, y una orden de trabajo sin reserva de repuestos no puede cotizar ni ejecutarse.

La máquina de estados del doc (`doc-database-diagrams.md` §4) define las **nueve columnas** del ciclo (`RECEPCIONADA`, `EN_DIAGNOSTICO`, `COTIZADA`, `EN_EJECUCION`, `EN_CONTROL_CALIDAD`, `LISTA`, `ENTREGADA`, `RECHAZADA`, `CANCELADA`), pero lo que **no** define son las **transiciones** entre esos estados —que es donde vive el valor del taller, y una orden «en la que se salta la cotización» no se puede fabricar— ni el **correlativo por taller** reservado antes de usarse, que tiene el mismo problema de numeración que el ADR-002 resolvió para los comprobantes fiscales (hallazgo 6.43, cerrado en el hub y repetible aquí).

El transporte de la llamada `work-order → inventory` también está en juego: el **hallazgo 6.32** («el contrato gRPC interno documentado no existe») señalaba que la documentación asignaba gRPC a esa llamada (`doc-microservices.md` §3.4 y §4, ADR-003) —un contrato que ningún `pom.xml` declara y que re-verificaría por un segundo camino la garantía más delicada de la plataforma (dos reservas simultáneas no sobre-reservan: lo garantizan el reclamo de la clave en la base de datos y el bloqueo de los lotes del inventario, no el transporte)—. La decisión ya está codificada en el **ADR-005**, queda committeada en el servicio (compromesa en el andamiaje), y la documentación de la plataforma ya la refleja; queda **implementar el lado que faltaba**.

## What Changes

- **El microservicio `erp-work-order-service` en Java Spring Boot** con PostgreSQL propio, en la estructura de cuatro capas del proyecto (`domain/` sin framework, `application/`, `infrastructure/`, `interfaces/`), con la demarcación transaccional en la capa de aplicación a través del puerto `UnitOfWork`, el aislamiento por taller **con RLS forzada** sobre un rol que no es propietario, y el esqueleto del andamio corregido (paquete `com.erp.workorder`, YAML como única fuente de configuración, sin `application.properties`; hallazgo 7.24).

- **La máquina de estados, con sus transiciones** — nueve estados y las transiciones permitidas entre ellos, con la regla de que **una transición no permitida se rechaza de forma explícita** (el estado no cambia, el fallo es del dominio y el error lleva su código) y con la historia de cambios registrada e **inmutable** en `work_order_status_history` (quién cambió qué y cuándo). Ninguna transición que vuelva de `ENTREGADA`, `RECHAZADA` o `CANCELADA` existe: son estados terminales.

- **El correlativo por taller, reservado antes de usarse** — el número de orden se **reserva en el momento de abrir la orden** (el mismo criterio del ADR-002 y del hallazgo 6.43 resuelto en el hub: numeración reservada, no `COUNT`), no se calcula al cerrarla: dos órdenes del mismo taller abiertas a la vez no pueden obtener el mismo número, y el hueco de una orden cancelada no se reutiliza.

- **La saga de stock orquestada por la orden** — la orden **reserva** los repuestos al cotizar (`ReserveStock`), los **consume** cuando el taller confirma que el cliente aprobó (`CommitStock`) y los **libera** si el taller no aprobó o si el taller cancela antes de la ejecución (`ReleaseStock`). La compensación es explícita: cada salida de la saga tiene su operación de vuelta, y el inventario no tiene forma de deshacer un consumo, de modo que la orden no puede deshacer lo que ya consumió. Cerrar el patrón 7 de §3 («Saga orquestada — ⚪»).

- **La vinculación con fleet y con el inventario es de contrato lógico** — `customer_id` y `vehicle_id` apuntan a fleet, `mechanic_id` a auth y `spare_part_id` a inventory, **sin claves foráneas cruzadas**: cada servicio es dueño de su esquema (criterio de «referencia lógica» del capítulo de las referencias cruzadas del capítulo §4). El vehículo del calling se **verifica** contra fleet (ruta `work-order→fleet` de §4), no se confía en el payload.

- **La bandeja de salida de la orden** — `WorkOrderCompleted` y `WorkOrderCancelled` se persisten **junto al cambio de estado**, en la misma transacción (el evento viaja con el estado, cerrado por 7.21/`fix-transactional-outbox`), y los publica un relay con reintentos, espera creciente y cola de muertos consultable. La publicación nunca ocurre dentro de la transacción de negocio.

- **La API bajo `/api/v1/work-orders`** — el prefijo que el gateway reenvía sin quitar, con los roles por endpoint (escritura para `SUPERADMIN`/`ADMIN_TALLER`/`ASESOR`, lectura también para `MECANICO`) y con el taller derivado del token verificado, no de la cabecera ni del cuerpo (hallazgo P0 5.1/5.2, cerrado en los otros servicios pero no en este, que es nuevo).

- **El transporte REST con las garantías que el doc exige** (§4): plazo de **500 ms**, **un reintento con backoff**, **interruptor de circuito** (de la misma forma que el gateway endurecido los cerró como 6.57) y **clave de idempotencia obligatoria** en las tres operaciones de la saga. El **ADR-005** ya está escrito (transporte REST sobre HTTPS, gRPC condicionado a tres condiciones y matriz normativa); **no hay cambio BREAKING**: el ADR enmienda el ADR-003 sin romper ningún contrato existente ni publicado.

- **Observabilidad y entrega** — logging estructurado con el mismo esquema de eventos de seguridad que el resto de la plataforma (hallazgo 7.32), actuator con salud y disponibilidad que **comprueban PostgreSQL de verdad** (hallazgos 6.12 y 7.8, cerrados en auth y tenant), tests contra **PostgreSQL embebido real** con las migraciones Flyway aplicadas (hallazgo 7.16), JaCoCo al **80 %** en `domain/` (hallazgo 7.2), perfil de producción explicito (hallazgo 7.3), `Dockerfile` y `compose.yaml` (hallazgo 7.10), OpenAPI con springdoc 3.x compatible (hallazgo 6.13) y OpenSpec inicializado en el repositorio.

## Capabilities

### New Capabilities

- `work-order`: Ciclo de vida de la **orden de trabajo** de un taller —recepción, diagnóstico, cotización, ejecución, control de calidad y entrega— con la **máquina de estados** de nueve estados y sus transiciones, el **correlativo por taller** reservado antes de usarse, la **saga de repuestos** (reservar, consumir, liberar) frente al inventario, la **vinculación de repuestos** al taller que la atiende a través de la sucursal del inventario, y la publicación de los eventos de la orden por bandeja de salida.

### Modified Capabilities

Ninguna. `openspec/specs/` sigue sin specs principales: este es el octavo change del workspace y todos sus requisitos son nuevos.

## Impact

**Microservicio nuevo `erp-work-order-service`** (Java 21 / Spring Boot 4.1.1, puerto **8088** — que laTabla de rutas del gateway ya reserva: `/api/v1/work-orders` → `:8088` con plazo de 500 ms)

| Contenido | Ficheros | Trazabilidad |
|:---|:---|:---|
| Esquema y aislamiento | `db/migration/V1__work_orders.sql` (las cuatro tablas, RLS forzada, roles), `V2__numeracion_por_taller.sql` (la numeración reservada por taller) | fila 6 de §2; §3.4; §4 de `doc-database-diagrams.md` |
| Dominio | `domain/model/{WorkOrder,WorkOrderItem,WorkOrderStatus}.java`, `domain/port/*` | patrón 7 — Saga orquestada; estructura de cuatro capas |
| Máquina de estados | `domain/model/WorkOrderStatus.java` con las transiciones declaradas, `work_order_status_history` con el autór | §3.4 («estado en PG con máquina de estados»), transiciones nuevas (no estaban definidas) |
| Saga con el inventario | `infrastructure/client/InventoryClient.java`, `application/service/StockReservationService.java` | §3.4 («reserva de stock idempotente»), patrón 7 |
| Bandeja de salida | `domain/event/*`, `infrastructure/messaging/outbox/*` | §3.4 («evento `WorkOrderCompleted` por outbox»), 7.21 |
| API y roles | `interfaces/rest/controller/WorkOrderController.java` | prefijo del gateway; P0 5.1/5.2; hallazgo 7.17 (errores tipados) |
| Pruebas | `src/test/java/com/erp/workorder/**` | 7.2 (JaCoCo 80 % en `domain/`), 7.16 (PostgreSQL real), suite cross-tenant |

**Documentación (actualizada en la misma entrega)**

- `docs/adr/ADR-005-transporte-interno-de-la-plataforma.md` — ya está escrita, primera copia canónica en `erp-work-order-service/docs/adr/` (ya committeada) y espejo e índice en la raíz (ya actualizados): solo se verifica en este change.
- `docs/doc-database-diagrams.md` §4 — las tablas con la numeración por taller reservada y el estado de la bandeja (la misma columna de estado, reintentos y cola de muertos que ya usa el inventario en `stock_outbox`).
- `docs/doc-microservices.md` §3.4 y §4 — ya reflejan la saga orquestada y el transporte REST (cambiadas cuando se escribió en ADR-005): solo se verifica en este change.
- `docs/doc-estado-implementacion.md` — la fila 6 de §2 deja de decir «⚫ No existe», el patrón 7 de §3 pasa de «⚪» a su estado implementado, y el Sprint 4+ recoge el servicio construido.

**Dependencias y contratos de otras partes (sin cambios)**

- `erp-inventory-service` — la saga ya expone sus **tres** operaciones idempotentes con `workOrderId` y `idempotencyKey` (ADR-004): `POST /api/v1/inventory/stock/reserve`, `/commit`, `/release`. No se modifica.
- `erp-api-gateway` — la ruta `/api/v1/work-orders` → `:8088` (con plazo de 500 ms y el circuito endurecido de `harden-gateway-edge`) ya existe: no se modifica.
- `erp-auth-service` — el claim de roles del JWT es un array y es el emisor verificado que este servicio valida: no se modifica.

**Lo que NO cambia:** ningún contrato REST existente, ningún claim JWT, ninguna cabecera, ninguna cabecera nueva en ninguno de los siete servicios ya construidos. No hay cambios **BREAKING**.
