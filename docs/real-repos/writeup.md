# Breaking four Spring Boot + Kafka repos on purpose

Faultline is a crash-test lab for Spring Boot and Kafka apps. On its own demo app it confirms all 10 planted bugs, and
nothing on the bug-free build. A demo built for the tool proves little, though. So I pointed it at four open-source
repos that nobody had planted bugs in, and checked by hand every bug it confirmed.

The short version:
- **Three real bugs.** Each one reproduced in two of two runs with a fault, and never without it.
- **One repo came out clean,** and the write-up says exactly what was tested there.
- **Faultline had to learn a lot along the way:** about a dozen things. Every time, the fix went into Faultline, never
  into the app.

## How a finding is earned

Faultline reads the code and finds places that match a risky pattern, such as "saves, then publishes an event". An LLM
judges each match. It then breaks the app at that exact place, under real traffic: it crashes the JVM there, corrupts a
message on its way, or makes a call time out. Afterwards it checks the data the app left behind.

A bug is **confirmed** only when three things hold:
- The fault fired as planned in every run.
- A check of the app's own data failed in at least two runs with the fault.
- The same check passed in a baseline run without the fault.

Anything less is "suspected". The LLM never decides a verdict, and nothing is reported without a command that
reproduces it.

## What it found

### An order is saved, but its event is lost (ewolff/microservice-kafka)

The order service saves the order, then calls `kafkaTemplate.send(...)` and doesn't wait for the result. The send only
puts the record in the producer's buffer.

Faultline crashed the order service just before the send, on every 100th order, twice per run:
- 7 orders in the first run, and 4 in the second, were saved with no event. More orders were lost than there were
  crashes, because each crash also took the other requests' records still in the buffer.
- Two of the lost orders had already been answered with HTTP 200. The customer was told the order went through, and
  shipping never heard of it.

### One unreadable record stops order creation (ionutbalosin/ecommerce-app)

order-service reads Avro with a plain deserializer and no error handling. A record it can't read fails inside the
consumer's poll, where Spring Kafka's default error handler can't skip it, so the consumer tries it again forever.

Faultline corrupted the 300th of 2,000 checkout messages:
- Order creation stopped at 299 orders in both runs, with about 1,700 messages left on the topic.
- Without the corruption, every answered checkout became an order.

### One bad record stops a tenth of the notifications (KHolodilin/spring-transactional-outbox-kafka)

This is the same class of bug in a repo that otherwise does things carefully: a transactional outbox and Debezium. The
notification consumer's own deserializer throws for a record that isn't JSON, and nothing catches it.

The topic has 10 partitions:
- The partition with the bad record stopped: 192 and 190 notifications were never recorded.
- The other nine kept flowing, which is why this kind of bug is easy to miss.
- In 30 seconds, the consumer logged the same exception 15,287 times.

## What came out clean

BankForge had two risks tested, and both held:
- **Double submits:** every 50th transfer was sent twice with the same idempotency key, and none was applied twice.
- **Kafka Connect resending:** Debezium was killed mid-traffic and started again. It resent up to 74 events it had
  already delivered, and the ledger skipped each one.

The double-submit test had one run with the fault instead of two.

## What wasn't tested

- Two shortlisted repos were not run: cuongnh28/spring-microservices-blueprint, and yas, which was the stretch goal.
- Most risks the scan flagged were not run. Each plan runs the app three times, twice with the fault and once
  without, so I ran the risks most likely to matter.
- A clean result means only that those faults, at those places, did not break the app.

## What Faultline had to learn

Nearly every repo broke Faultline before Faultline broke it:

| Area | What it had to handle |
|---|---|
| Starting the app | several Compose files; images a build script makes; ports Windows reserves; Kafka Connect, which no app setting names |
| Requests and messages | form posts; endpoints that exist only in an OpenAPI spec; Avro; topics named in code; `@SendTo` replies; batch listeners; a header each request needs with its own value |
| Plans | a topic only Debezium writes to, where Faultline moves the listener instead of the sender; and a rule that a fault on the way to a listener must be checked in what the listener writes |

That last rule came from a plan that checked the wrong table, so every check passed while the consumer was stuck.

## Reproduce it

Each repo's folder in [docs/real-repos/](README.md) has:
- the commit it ran at;
- the small `faultline/` folder that was added to the repo, holding hints, never code;
- the plan, and the two commands that rerun it.
