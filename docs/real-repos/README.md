# Faultline on real repos

Phase 7, Part 5 ran Faultline on four open-source Spring Boot and Kafka repos that nobody planted bugs in. Every
confirmed finding below was then checked by reading the code, the evidence and the app's own logs.

- **Confirmed and checked by hand:** 3 bugs, in ewolff, ionutbalosin and KHolodilin.
- **Clean results:** 2, both in BankForge.

The only thing added to each repo is its `faultline/` folder. Each folder here holds that repo's `faultline/` files and
the plans that ran. KHolodilin needed no hints, so it has only its plan. In each plan, `<clone>` stands for wherever you cloned the repo.

| Repo | Commit | Risk tested | Result |
|---|---|---|---|
| [ewolff/microservice-kafka](https://github.com/ewolff/microservice-kafka) | 103de22 | P8: saves, then publishes | **confirmed**: orders saved with no event |
| [ionutbalosin/ecommerce-app](https://github.com/ionutbalosin/ecommerce-app) | 9cc90da | P7: no dead-letter handling | **confirmed**: one bad record stops order creation |
| [KHolodilin/spring-transactional-outbox-kafka](https://github.com/KHolodilin/spring-transactional-outbox-kafka) | 1308ddb | P7: no dead-letter handling | **confirmed**: one bad record stops a partition's notifications |
| [Harry-Zhao-AU/BankForge](https://github.com/Harry-Zhao-AU/BankForge) | 0773838 | P3: double submit; P9: Kafka Connect resends | clean: both held |

A confirmed finding means:
- Its check failed in both runs with the fault.
- It passed in a baseline run without the fault.
- The fault fired as planned in every run.

A finding is not claimed if only Faultline's own fault could cause it, or if the repo says the gap is out of scope.
None of these repos says either about the bugs below.

## ewolff/microservice-kafka: an order is saved, but its event is lost

`OrderService#order` saves the order, then sends `order-created` with `kafkaTemplate.send(...)`:

- There is no outbox and no transaction around the two.
- The result of the send is never checked.
- The send only puts the record in the producer's buffer.

So when the order service dies between the save and the actual delivery to Kafka, the order stays in `dborder`, but
invoicing and shipping never hear of it.

**The run** (runs/2026-11-07T07-28-50Z, seed 571747): 3,000 order forms posted at 100/s. The order service crashed
just before `fireOrderCreatedEvent` on every 100th order, twice per run, and restarted each time.

| Run | Crashes fired | Orders saved with no event on `order` |
|---|---|---|
| run-1 | 2 of 2 | 7 |
| run-2 | 2 of 2 | 4 |
| baseline | none | 0, all 3,000 delivered |

**Checked by hand:**
- More orders were lost than there were crashes. Each crash also lost the sends other requests had just put in the
  buffer.
- Two of the lost orders, `Main St 571747-1108` and `-1109` in run-1, had already been answered with HTTP 200. The
  customer was told the order was placed, and it never reached invoicing or shipping.
- The rest of the lost orders were in flight when the process died. Each was saved, but its client got no answer.
- The README presents the repo as "a sample to show how Kafka can be used". It says nothing about delivery guarantees,
  either way.

**Rerun:** `./faultline doctor <clone> --compose <clone>/docker/docker-compose.yml --keep`, then
`./faultline run docs/real-repos/ewolff_microservice-kafka/p8.plan.yaml`. Doctor needs the jars built first and says
how.

## ionutbalosin/ecommerce-app: one record order-service cannot read stops order creation

order-service reads `ecommerce-orders-topic` with a plain `KafkaAvroDeserializer` as its value deserializer. No module
configures an `ErrorHandlingDeserializer`, an error handler or a dead-letter topic.

A record the deserializer cannot read fails inside the consumer's poll, before the listener is called. Spring Kafka's
default error handler cannot skip such a record. It seeks back to it again and again, and every order behind it on
that partition waits for good.

The app's own log says so: "This error handler cannot process 'SerializationException's directly; please consider
configuring an 'ErrorHandlingDeserializer'".

**The run** (runs/2026-11-01T13-55-19Z, seed 301282):
- 2,000 cart checkouts went through Faultline's relay. The agent renames the topic that shopping-cart-service sends
  to, so the relay sits in between.
- The relay corrupted the 300th message, once per run.

| Run | Orders created | Left on the topic |
|---|---|---|
| run-1 | 299 | 1,701 |
| run-2 | 299 | 1,692 |
| baseline | 1,989, every checkout that was answered | 0 |

no-lost failed for about 1,700 checkouts in each faulted run. The corrupted message itself is excused, since it can no
longer be read: Faultline found its user ID in the Avro bytes.

**Checked by hand:** the deserializer setup and the missing error handling are as described above. The README lists
resiliency as a primary goal of the repo.

**Rerun:** the clone needs `faultline/` from this folder, at commit 9cc90da. Its hints do four things:
- They build seven services.
- They move the cart database off a port Windows reserves.
- They skip a Debezium Connect that is off the journey.
- They pin Traefik to v3.6.1, because v3.0 cannot talk to Docker Engine 29.

The plan runs only the cart, product and order services, so the machine has enough memory.

## KHolodilin/spring-transactional-outbox-kafka: one bad record stops a tenth of the notifications

Under `docker/compose.debezium.yml`, the journey is:
1. order-service-debezium writes the order and an outbox row in one transaction.
2. Debezium carries the row to `orders.events`, which has 10 partitions.
3. notification-stub consumes it with a batch listener.

The stub's own `EventEnvelopeDeserializer` throws `SerializationException` for a record that is not JSON. Its
container factory sets no error handler, and there is no `ErrorHandlingDeserializer`. So the first such record stops
its partition for good.

**The run** (runs/2026-11-07T07-53-36Z, seed 638236):
- 2,000 orders, each sent with its own `Idempotency-Key` header.
- Nothing in the app's code sends to `orders.events`; Debezium does. So the relay could not sit in front of a sender.
  Instead, the stub's listener was moved to `orders.events.faultline` through its own setting `app.kafka.topic`, and
  the relay forwarded `orders.events` to it.
- The relay corrupted the 300th message, once per run.

| Run | Corrupted message | Notifications never recorded |
|---|---|---|
| run-1 | partition 3, offset 33 | 192 |
| run-2 | partition 3, offset 261 | 190 |
| baseline | none | 0, all 2,000 recorded |

**Checked by hand:** run-1's log has the same `SerializationException` at `orders.events.faultline-3` offset 33
15,287 times in 30 seconds, with "This error handler cannot process 'SerializationException's directly". The other
nine partitions kept flowing. The 30 seconds of log came to 715,000 lines.

The repo's implementation plan lists dead-letter handling among its failure scenarios; the stub does not have it.

**Rerun:** `./faultline doctor <clone> --compose <clone>/docker/compose.debezium.yml --keep`, which boots Kafka Connect
and registers its connector, then `./faultline run docs/real-repos/KHolodilin_spring-transactional-outbox-kafka/p7.plan.yaml`.

## Harry-Zhao-AU/BankForge: clean

Two risks were tested on the payment, account, Debezium and ledger journey. Its notification-service is skipped,
because under compose.yml it cannot write its audit log. The app starts with no accounts, so the seed file adds ten
funded test accounts and puts their balances back after every reset.

**P3, a double submit, at `TransferStateService#confirm`** (runs/2026-11-01T07-51-19Z, seed 103554):
- 2,000 transfers, and every 50th was submitted twice with the same idempotency key: 20 duplicates fired.
- In the faulted run and the baseline, every check passed: no-lost, no-duplicates, no-unexpected, and
  no-extra-confirmations.
- The idempotency key held.
- This had 1 faulted run, not 2.

**P9, Kafka Connect resending, at `TransferConfirmationListener#onTransferConfirmed`** (runs/2026-11-01T16-41-38Z, seed
995595):
- debezium-connect was killed 10s into 2,000 transfers and started again 20s later, in both faulted runs.
- Debezium sent again what it had sent since its last stored position: 2,074 and 2,042 events for 2,000 transfers.
- Every check passed in both runs and the baseline: no-lost, no-duplicates, no-unexpected, no-lost-downstream, and
  every transfer ended CONFIRMED.
- The listener takes Debezium's duplicates: the ledger checks `existsByTransferId`.

Most requests timed out on the client side, at 5 seconds, even in the baseline. Every transfer was still applied, so
they count as information. The rows are matched to requests by the transfer ID in the app's answer.

## What Faultline needed, and got, to run these

Each item below was a failure in onboarding one of these repos. Faultline was fixed each time, never the app.

- **Reaching the app:**
  - Several Compose files.
  - Services built from prebuilt images.
  - Ports Windows reserves.
  - An image with an old manifest.
  - A Redis cache that outlived the reset.
  - Kafka Connect, which no app setting names.
- **Formats:**
  - Form posts.
  - Endpoints that exist only in an OpenAPI spec.
  - Avro and other binary messages.
  - Topics named in code.
  - `@SendTo` replies.
  - Batch listeners.
  - Path IDs.
  - A header each request must carry with its own value (`Idempotency-Key`).
- **Plans:**
  - A listener only Kafka Connect sends to is moved behind the relay through its topic setting.
  - The effect a fault on the way to the listener is checked against must be written by the listener's own service.
    A table the entry writes before sending is there whatever happens to the message.

## Issues on the original repos

None opened. Opening an issue on someone else's repo is the owner's call each time, one issue per bug, with these
reproduce steps.
