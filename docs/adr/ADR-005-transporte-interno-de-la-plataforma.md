# ADR-005 — Transporte interno de la plataforma: REST sobre HTTPS, y cuándo gRPC

- **Estado:** Aceptado
- **Fecha:** 2026-10-21
- **Change:** `add-work-order-service`
- **Ámbito:** toda la plataforma
- **Enmienda:** `ADR-003` (que reservaba gRPC para la reserva de stock de la saga)
- **Copia canónica:** `erp-work-order-service/docs/adr/ADR-005-transporte-interno-de-la-plataforma.md` (este repositorio; los demás guardan un espejo)

## Contexto

El catálogo de servicios (§2 de `doc-microservices.md`) describe cada uno con
su forma de comunicación, y tres de ellos aparecen como *«REST + gRPC»* o
*«gRPC + eventos»*. La §4 reserva la comunicación síncrona interna para gRPC,
con `deadline ≤ 500 ms`, y nombra dos pares: `fleet→hub` y
`work-order→inventory`.

**Ese contrato gRPC no existe.** Ningún `pom.xml` de los seis servicios
construidos declara `grpc` ni `protobuf`; el paquete de contratos compartidos
(`erp-contracts`) no está creado; y el gateway, que es el único que enruta
peticiones hacia dentro de la plataforma, es **REST→REST**. El propio informe
de estado lo recogió como el hallazgo 6.32: *«el contrato "gRPC interno"
documentado no existe»*, y su acción 33 pedía resolverlo en un ADR antes de
construir el servicio de fleet.

La deriva no es de un servicio: **fleet** e **inventory** se construyeron
en REST, con la documentación diciendo gRPC. El de inventory se construyó
justo ahora, con su reserva de stock expuesta por REST, idempotente y con la
garantía de concurrencia probada.

## Decisión

**1. El transporte interno de la plataforma es REST sobre HTTPS**, con las
mismas garantías que el diseño exigía para gRPC: plazo máximo, reintento con
backoff, interruptor de circuito y clave de idempotencia obligatoria en las
operaciones que la admiten.

**2. La matriz de comunicación de §4 es normativa.** Cada interacción de la
plataforma tiene un transporte asignado, y un servicio nuevo no elige: sigue la
matriz.

**3. gRPC no se descarta: se condiciona.** Se adopta para un camino
concreto cuando se dé **una** de estas tres condiciones:

- **a) Un segundo lenguaje en el camino de una llamada síncrona.** El beneficio
  de gRPC no es el rendimiento: es la **disciplina de contrato** —`.proto`
  versionado, generación en varios lenguajes, contrato que se compila en lugar
  de documentarse—. Ese beneficio solo aparece cuando hay un segundo lenguaje
  que compilar el mismo contrato.
- **b) Un volumen o un streaming que REST no cubra.** Carga binaria de
  tamaño apreciable, o un flujo continuo con contrapresión.
- **c) Un paquete de contratos compartido** (`erp-contracts`) con versionado,
  generación en CI y reglas de propiedad. Es la condición previa de la (a) y
  de la (b): sin él no hay dónde vivir un `.proto`.

**4. La telemetría del IoT no es un caso de gRPC.** El camino es
`dispositivo → MQTT/HTTPS → ingestion → Kafka → processor`, y Kafka está ahí
por una razón concreta: **buffer, contrapresión y grupos de consumidores**,
que es lo que exige el ASR QA-1 ante una ráfaga de miles de vehículos.
Sustituir el bus por un RPC sería perder el buffer a cambio de una latencia
que nadie necesita. Si el IoT necesita compartir contratos binarios, el lugar
natural son los **esquemas de Kafka** (Avro o Protobuf con registro de
esquemas), que es un problema distinto y con una herramienta distinta.

## Motivo

**Sobre el protocolo de las llamadas síncronas internas.** El argumento a
favor de gRPC —tipos fuertes, menos ceremonia de serialización, deadline
nativo— es real, pero su rendimiento no se aprecia donde se aplicaría: una
reserva de repuestos es un comando de un cuerpo pequeño, con una clave de
idempotencia, y su respuesta es un código y un identificador. El deadline es un
timeout del cliente en ambos casos; el reintento con backoff y el interruptor
de circuito son de la biblioteca en ambos casos.

**Y lo que de verdad garantiza la saga no está en el transporte.** Que dos
reservas simultáneas no sobre-reserven lo garantiza el **reclamo de la clave
en la base de datos** y el **bloqueo de los lotes** (`FOR UPDATE`): es una
garantía del modelo de concurrencia del inventario, no del protocolo. Cambiar
a gRPC no la refuerza: obliga a **re-verificar por un segundo camino** la
garantía más delicada de la plataforma, sobre un servicio ya cerrado y con su
suite de concurrencia en verde.

**Sobre el coste, que es organizativo antes que técnico.** Adoptar gRPC
parcialmente es lo caro: obliga a un toolchain nuevo (generación de código,
un paquete de contratos que hay que inventar y con reglas de versionado, CI
para la generación) y **deja la plataforma heterogénea**: a partir de ese
momento, cada llamada nueva plantea la pregunta «¿gRPC o REST?» y cada
servicio responde distinto. El propio catálogo exige un ADR antes de añadir
lenguajes o motores nuevos; el mismo criterio se aplica a los transportes.

**Sobre la decisión de la saga de stock.** La documentación dice «choreography
con outbox» y, a la vez, «reserva de stock vía gRPC `ReserveStock`»: una
**orquestación** es lo contrario de una **coreografía**. La contradicción
tiene una pista que la resuelve: la API que el inventario construyó
—`ReserveStock`, `CommitStock` y `ReleaseStock`, idempotentes y con su
compensación— es de **orquestación**, y la orden de trabajo es quien la
orquesta. La etiqueta que hay que corregir es «choreography»; el flujo, no.

**Sobre el hallazgo 6.32.** El hallazgo era real: la documentación describía
un contrato que no existía. Se **cierra** con este ADR: el contrato interno
es REST, la documentación se corrige, y la decisión queda escrita con su
condición de revisión.

## Alternativas descartadas

- **gRPC para todas las llamadas síncronas internas ahora.** Se descarta por
  el coste sin beneficio: el toolchain completo, la plataforma heterogénea y
  la re-verificación de las garantías de concurrencia por un segundo
  transporte, a cambio de tipos fuertes en un mundo de un solo lenguaje.
- **gRPC solo en la saga de stock**, como reservaba el ADR-003. Se descarta
  por la misma razón, y además porque obligaría a añadir un servidor gRPC al
  inventario, que hoy expone una única superficie REST ya verificada.
- **Mantener el doc diciendo gRPC y dejar que cada servicio decida.** Se
  descarta porque convierte una decisión de plataforma en una costumbre: es
  exactamente como se produce una plataforma sin reglas.
- **gRPC también para la telemetría del IoT.** Se descarta porque el bus da
  el buffer y la contrapresión que el ASR QA-1 exige, y porque un flujo de
  eventos no es una llamada con respuesta.

## Consecuencias

**Positivas.**

- La plataforma tiene **una sola regla de transporte**, con una matriz
  normativa: un servicio nuevo no decide.
- Las **garantías de la saga** (plazo, reintento, interruptor, idempotencia)
  se aplican igual, y el inventario conserva una única superficie pública.
- El **gateway sigue viendo todo el tráfico** de la plataforma: con REST, un
  salto interno es observable en su traza y en sus registros.
- La documentación deja de describir una arquitectura que no existe.

**Negativas.**

- Se **descarta** la herencia de tipos fuertes en las llamadas
  internas. Con OpenAPI, bean validation y contratos versionados, la
  cobertura es suficiente; y cuando el contrato tenga que compilarse en dos
  lenguajes, esta decisión se revisa con el ADR que lo diga.
- El **IoT no aporta el caso de uso** de gRPC: su camino es MQTT y Kafka, y
  sus contratos, si los necesita, serán esquemas de Kafka.

**Neutrales.**

- El **rendimiento no se ve afectado**: no hay ninguna llamada interna de
  volumen apreciable.
- La **revisión** de esta decisión se dispara con la primera de las tres
  condiciones, no con una fecha.

## Alcance de la decisión posterior

- **La condición (a) llegará con el IoT**, pero por el camino de Kafka, no
  por el de las llamadas síncronas: el ingestion (Go) no llama a los servicios
  Java, y el processor (Python) tampoco. Si algún día un servicio en otro
  lenguaje necesita llamar de forma síncrona a un servicio Java, esa es la
  llamada que se revisa.
- **La condición (c) es la previa**: `erp-contracts` no se crea «porque
  toca». Se crea el primer día que un contrato tenga que compilarse en dos
  lenguajes, con su versionado y su generación en CI.
- **El gateway sigue siendo REST→REST.** No se le añade un proxy gRPC: no
  lo necesita ningún camino, y su valor está en ser el único borde.
