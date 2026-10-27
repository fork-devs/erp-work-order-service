# Spec Delta

## Purpose

Permite que un taller gestione el ciclo de vida de una **orden de trabajo** —recepción, diagnóstico, cotización, ejecución, control de calidad y entrega— con una máquina de estados explícita, un correlativo por taller, la **reserva, el consumo y la liberación** de los repuestos que la orden necesita frente al inventario, la verificación del cliente y del vehículo contra fleet, y la publicación de los eventos de la orden por bandeja de salida, de forma que el stock del inventario nunca se sobre-reserva, la orden nunca avanza sobre una reserva que no existe y ningún taller ve las órdenes de otro.

## ADDED Requirements

### Requirement: La orden se abre con un número reservado en exclusiva por taller

El sistema SHALL asignar a cada orden un número **correlativo por taller** y SHALL **reservarlo en el momento de abrir la orden**. Dos órdenes del mismo taller abiertas a la vez no pueden obtener el mismo número, y el número de una orden cancelada no se reutiliza. El sistema MUST NOT calcular el número al cerrar la orden.

#### Scenario: Dos órdenes del mismo taller abiertas a la vez

- **WHEN** el ADMIN_TALLER del taller A abre dos órdenes sin cerrar la primera
- **THEN** las dos órdenes tienen números DISTINTOS

#### Scenario: El número de una orden cancelada no se reutiliza

- **WHEN** el ADMIN_TALLER del taller A cancela una orden y abre otra
- **THEN** la orden nueva tiene un número posterior al de la cancelada

#### Scenario: Dos talleres distintos tienen el mismo número

- **WHEN** el ADMIN_TALLER del taller A y el del taller B abren una orden cada uno
- **THEN** ambas órdenes tienen el mismo número, porque el correlativo es **por taller**

### Requirement: La orden se abre sobre un cliente y un vehículo que fleet conoce

El sistema SHALL verificar, al abrir la orden, que el cliente y el vehículo del payload existen en fleet y que el vehículo **pertenece a ese cliente**, con las mismas garantías de llamada interna (plazo, reintento e interruptor de circuito). El sistema MUST NOT abrir la orden cuando fleet no verifica la referencia: la orden no existe y el fallo no se reporta como conflicto de datos del taller.

#### Scenario: El vehículo existe y pertenece al cliente

- **WHEN** el ADMIN_TALLER del taller A abre una orden sobre un vehículo que existe en el taller A y cuyo cliente registrado es el del payload
- **THEN** la orden se abre con el identificador del vehículo registrado tal cual, sin claves foráneas cruzadas

#### Scenario: El vehículo no pertenece al cliente declarado

- **WHEN** el ADMIN_TALLER del taller A abre una orden cuyo `vehicle_id` existe en fleet pero su cliente registrado no es el `customer_id` del payload
- **THEN** el sistema rechaza la apertura con su código de error estable y la orden no se crea

#### Scenario: Fleet no responde

- **WHEN** fleet no responde dentro del plazo y el reintento también falla
- **THEN** el sistema abandona la llamada, la orden no se crea y el fallo se responde con un error de disponibilidad con su código, no como un error del cliente ni del taller

### Requirement: La orden declara la sucursal donde se proveen los repuestos

El sistema SHALL registrar en la orden la **sucursal del taller** contra la que se reservan los repuestos, y SHALL reservar y consumir en el inventario contra esa misma sucursal toda la vida de la orden. El sistema MUST NOT abrir la orden sin sucursal declarada.

#### Scenario: La cotización reserva contra la sucursal de la orden

- **WHEN** el ADMIN_TALLER del taller A cotiza una orden abierta sobre la sucursal S1
- **THEN** la llamada de reserva al inventario lleva la sucursal S1

#### Scenario: La orden sin sucursal se rechaza

- **WHEN** una apertura de orden llega sin el identificador de la sucursal
- **THEN** el sistema rechaza la petición con su código de error estable y la orden no se crea

### Requirement: La máquina de estados rechaza las transiciones no permitidas

El sistema SHALL permitir únicamente las transiciones declaradas entre los nueve estados del ciclo, y SHALL rechazar de forma explícita cualquier transición que no esté permitida, sin modificar el estado de la orden. El sistema MUST NOT permitir que una orden vuelva a un estado anterior sin una transición declarada, y MUST NOT permitir que una orden entregada, rechazada o cancelada vuelva a un estado activo.

#### Scenario: Se salta un estado

- **WHEN** el ADMIN_TALLER del taller A intenta pasar una orden de `RECEPCIONADA` a `COTIZADA` sin pasar por `EN_DIAGNOSTICO`
- **THEN** el sistema rechaza la transición y la orden sigue en `RECEPCIONADA`

#### Scenario: Se reabre una orden entregada

- **WHEN** el ADMIN_TALLER del taller A intenta pasar una orden `ENTREGADA` a `EN_EJECUCION`
- **THEN** el sistema rechaza la transición y la orden sigue `ENTREGADA`

#### Scenario: La transición permitida se aplica

- **WHEN** el ADMIN_TALLER del taller A pasa una orden de `RECEPCIONADA` a `EN_DIAGNOSTICO`
- **THEN** el sistema aplica la transición y la orden queda en `EN_DIAGNOSTICO`

### Requirement: Cada cambio de estado queda registrado con su autor

El sistema SHALL registrar en la historia de la orden cada transición, con el estado de origen, el de destino, quién la hizo y cuándo. La historia SHALL ser **inmutable**: un cambio de estado no reescribe los anteriores, y un cambio de estado sin historia es un cambio de estado que no ocurrió.

#### Scenario: Se registra la transición

- **WHEN** el ADMIN_TALLER del taller A mueve una orden de `RECEPCIONADA` a `EN_DIAGNOSTICO`
- **THEN** la historia de la orden registra el estado de origen, el de destino, el autor y la fecha

#### Scenario: La historia acumula

- **WHEN** una orden recorre tres estados
- **THEN** su historia tiene tres entradas, una por transición

### Requirement: La cotización reserva los repuestos y la orden no avanza si la reserva falla

El sistema SHALL reservar en el inventario los repuestos de la orden al **cotizar**, con una **clave de idempotencia obligatoria** derivada de la orden, y SHALL llevar la orden a `COTIZADA` **solo** si la reserva se ha realizado. Si la reserva falla, el sistema MUST NOT dejar la orden en `COTIZADA`, MUST NOT registrar una reserva que no existe y MUST NOT propagar el fallo como un error del cliente: la orden se queda en el estado anterior.

#### Scenario: La reserva se realiza y la orden cotiza

- **WHEN** el ADMIN_TALLER del taller A cotiza una orden con dos repuestos y el inventario tiene stock
- **THEN** la orden queda en `COTIZADA` y el inventario tiene reservados los dos repuestos

#### Scenario: El inventario no tiene stock

- **WHEN** el ADMIN_TALLER del taller A cotiza una orden y el inventario no tiene stock suficiente
- **THEN** la orden **no** queda en `COTIZADA`, y el inventario no registra ninguna reserva

#### Scenario: La misma cotización se repite

- **WHEN** el ADMIN_TALLER del taller A repite la misma cotización con la misma clave
- **THEN** el inventario registra **una sola** reserva y la orden no duplica repuestos

### Requirement: La aprobación consume los repuestos reservados

El sistema SHALL **consumir** en el inventario los repuestos reservados cuando la cotización se **aprueba**, con una clave de idempotencia obligatoria, y SHALL llevar la orden a `EN_EJECUCION` solo si el consumo se ha realizado. El sistema MUST NOT intentar deshacer un consumo: el inventario no lo permite, y por eso la orden solo puede liberar mientras la reserva siga viva.

#### Scenario: El cliente aprueba

- **WHEN** el ADMIN_TALLER del taller A aprueba la cotización de una orden en `COTIZADA`
- **THEN** la orden queda en `EN_EJECUCION` y el inventario consume los repuestos reservados

#### Scenario: La aprobación se repite

- **WHEN** la aprobación se repite con la misma clave de idempotencia
- **THEN** el inventario consume **una sola** vez

### Requirement: El rechazo o la cancelación liberan los repuestos reservados

El sistema SHALL **liberar** en el inventario los repuestos reservados cuando el cliente **no aprueba** la cotización o cuando la orden se **cancela** con la reserva viva, con una clave de idempotencia obligatoria. La liberación SHALL devolver el stock disponible y SHALL ser idempotente. El sistema MUST NOT liberar lo que ya se consumió, porque el inventario no permite deshacer un consumo.

#### Scenario: El cliente no aprueba

- **WHEN** el ADMIN_TALLER del taller A rechaza la cotización de una orden en `COTIZADA`
- **THEN** la orden queda `RECHAZADA` y el inventario libera los repuestos reservados

#### Scenario: La cancelación con reserva viva libera

- **WHEN** el ADMIN_TALLER del taller A cancela una orden en `COTIZADA`
- **THEN** la orden queda `CANCELADA` y el inventario libera los repuestos reservados

#### Scenario: La liberación se repite

- **WHEN** la liberación se repite con la misma clave
- **THEN** el stock disponible no aumenta dos veces

### Requirement: La saga con el inventario no se queda bloqueada ni propaga el fallo

El sistema SHALL llamar al inventario con un **plazo máximo de 500 ms**, con **un reintento con backoff** y con **interruptor de circuito**, y MUST NOT esperar indefinidamente a una respuesta. El sistema MUST NOT avanzar la orden cuando la llamada no ha podido completarse, y MUST NOT reintentar a ciegas una operación cuyo resultado se desconoce.

#### Scenario: El inventario no responde

- **WHEN** el inventario no responde dentro del plazo y el reintento también falla
- **THEN** el sistema abandona la llamada, la orden no avanza de estado y el fallo queda registrado con su error

#### Scenario: La llamada se corta y el resultado se desconoce

- **WHEN** la llamada al inventario se corta y no se sabe si la reserva se hizo
- **THEN** el sistema NO reintenta con una clave distinta, sino con la **misma** clave, para no duplicar la reserva

### Requirement: Los repuestos, el mecánico y la sucursal de la orden son referencias lógicas

El sistema SHALL registrar los repuestos de la orden, el mecánico que la atiende y la sucursal como **identificadores lógicos**, sin claves foráneas cruzadas: cada servicio es dueño de su esquema, y el sistema MUST NOT exigir la existencia del repuesto o del mecánico en su propia base de datos.

#### Scenario: Se registra un repuesto que el inventario no conoce

- **WHEN** el ADMIN_TALLER del taller A añade a la orden un repuesto con un identificador que el inventario no conoce
- **THEN** el sistema lo registra igualmente en la orden, porque la referencia es lógica; la garantía de existencia la da la reserva, no el modelo de la orden

#### Scenario: El mecánico de otra área

- **WHEN** la orden se registra con un mecánico cuyo identificador no corresponde a un usuario del taller
- **THEN** el sistema lo registra como referencia lógica y no valida su existencia en la base del servicio

### Requirement: Toda orden está acotada al taller del principal

El sistema SHALL derivar el taller de toda operación de órdenes **exclusivamente** del token verificado, y MUST NOT aceptar el taller del cuerpo de la petición ni de una cabecera como autoridad. El sistema MUST NOT devolver órdenes de otro taller, y MUST NOT permitir que una escritura de un taller modifique órdenes de otro.

#### Scenario: Lectura de una orden de otro taller

- **WHEN** el ADMIN_TALLER del taller A consulta una orden del taller B
- **THEN** el sistema no le devuelve la orden ni ningún dato de ella, y registra el intento de acceso cruzado

#### Scenario: Escritura con el taller en el cuerpo

- **WHEN** el ADMIN_TALLER del taller A envía una operación incluyendo en el cuerpo el identificador del taller B
- **THEN** el sistema lo ignora y opera sobre el taller A

### Requirement: La API exige un token verificado y declara el rol de cada endpoint

El sistema SHALL rechazar con `401` toda petición de órdenes que no traiga un token de acceso verificado, y SHALL declarar en cada endpoint el rol que exige, respondiendo `403` cuando el principal no lo tiene. La creación, el movimiento y las operaciones de la saga SHALL exigir un rol de taller con capacidad de escritura, y la consulta SHALL admitir también el rol de solo lectura.

#### Scenario: Petición sin token

- **WHEN** una petición a la API de órdenes llega sin token de acceso
- **THEN** el sistema responde `401 Unauthorized` y no crea ni modifica ninguna orden

#### Scenario: Rol sin permiso de escritura

- **WHEN** el MECANICO del taller A intenta aprobar la cotización de una orden en `COTIZADA`
- **THEN** el sistema responde `403 Forbidden` y la orden no cambia de estado

### Requirement: Los eventos de la orden se publican por bandeja de salida

El sistema SHALL persistir el evento de la orden **junto al cambio de estado** en la misma transacción local, y un relay lo publicará después, con reintentos y cola de muertos. El sistema SHALL emitir un evento de orden completada al entregar el vehículo y uno de orden cancelada al cancelarla, y el evento SHALL llevar el identificador del taller y el de la orden.

#### Scenario: El evento viaja con el cambio de estado

- **WHEN** una orden se entrega
- **THEN** el evento de orden completada queda persistido en la misma transacción, y se publica después, no durante

#### Scenario: El relay publica lo pendiente

- **WHEN** hay un evento pendiente de publicación
- **THEN** el relay lo publica y lo marca como publicado, y un fallo no impide que se publiquen los siguientes

### Requirement: Los errores llevan un contrato estable

El sistema SHALL responder con `ProblemDetail` (RFC 9457) y SHALL incluir un código de error estable por familia, legible por máquina. El error SHALL incluir el identificador del taller, y MUST NOT incluir secretos ni las credenciales del cliente. El sistema SHALL registrar la excepción capturada, con su traza, en lugar de imprimirla por la salida estándar.

#### Scenario: Transición no permitida

- **WHEN** el ADMIN_TALLER del taller A intenta una transición que no está permitida
- **THEN** el sistema responde con un error que incluye el código estable de la familia y el identificador del taller

#### Scenario: Error interno

- **WHEN** una operación falla con una excepción no prevista
- **THEN** el sistema registra la excepción con su traza y responde con un error genérico que no expone la clase de la excepción ni su mensaje
