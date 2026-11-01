# Faultline

A crash-test lab for Spring Boot + Kafka apps. Faultline reads a repository, finds the places where a crash or slowdown could lose or duplicate data, breaks the app at exactly those places, and reports only the bugs it actually reproduced.

> Work in progress.

## Layout

```text
demo-app/   the crash-test dummy: a payments service with planted bugs
tool/       Faultline itself (Maven multi-module)
deploy/     Docker Compose for Kafka, Postgres and helpers
```

## Local infrastructure

```bash
cd deploy
docker compose up -d
```

Kafka listens on `localhost:9092` (from your machine) and `kafka:19092` (from containers). Postgres is on `localhost:5432` (db/user/password: `demo`). The demo app's services and their helpers (Toxiproxy on `localhost:8474`, the fake payment provider on `localhost:9090`) are in the `demo` profile; Faultline starts them itself during a run. The demo app charges every payment at that provider, so to run its jar on its own, start the provider first: `docker compose --profile demo up -d provider` (it is built from the CLI jar).

## Setup

```bash
cp .env.example .env                              # then add your LLM key (free at https://aistudio.google.com)
(cd demo-app && ./mvnw -q package -DskipTests)    # build the demo app jar
(cd tool && ./mvnw -q package -DskipTests)        # build the CLI and the fault agent
```

Faultline talks to any OpenAI-compatible LLM API; Google Gemini is the default. The settings are in [.env.example](.env.example).

## Point Faultline at an app

Four steps, each writing a file the next one reads. Everything about an app goes in `.faultline/<app>/`, outside the app itself. On a repo you did not write, start with `faultline doctor` (below).

```bash
./faultline understand demo-app   # LLM maps the app        -> .faultline/demo-app/app-map.json
./faultline scan demo-app         # JavaParser + LLM review -> .faultline/demo-app/risks.json
./faultline plan demo-app         # draft a plan, you approve it -> .faultline/demo-app/plan.yaml
./faultline run .faultline/demo-app/plan.yaml --seed 42
```

- **understand** reads the docs, Spring config, migrations, build files and the signatures of every endpoint and listener, and asks the LLM for the app's entry points, data stores and user journeys. Every handler it names is checked against the code.
- **scan** looks for eight risky patterns: P1 save, then acknowledge; P2 work handed to a thread pool; P3 no duplicate check; P4 read, modify, save without locking; P5 an outside call without an idempotency key; P6 retries with no backoff or limit; P7 no dead-letter handling; P8 save, then publish an event. Cheap JavaParser checks find candidates, then the LLM judges each pattern's candidates in one call, with the code around them, the configuration, the schema and the app map. Code reaches the LLM without comments, so it judges what the code does. Risks are ranked by how bad the outcome is (money, then lost data, then slow recovery), then by confidence. `--set KEY=VALUE` judges the app with other settings; `--pattern P1,P4` looks for only some patterns.
- **plan** turns the highest-ranked risk it can test into `plan.yaml`. Each pattern has its own fault, placed where Faultline finds the risky code:

  | Pattern | Fault |
  | --- | --- |
  | P1 save, then acknowledge | crash just before the acknowledgment |
  | P2 work handed to a thread pool | crash the moment the listener returns, while the handed-off work still runs |
  | P3 no duplicate check | the client submits every 50th request twice |
  | P4 read, modify, save | a barrier holds one worker between its read and its write until another worker has written |
  | P5 outside call without a key | the fake provider charges, then replies after the caller has given up |
  | P6 retries without backoff | the fake provider goes down, and stays down while it gets too many requests |
  | P7 no dead-letter handling | the relay sits in front of the listener and corrupts one message |
  | P8 save, then publish | crash just before the publish |

  Faultline works out what each request body must look like: from the app's OpenAPI (or Swagger) spec when it has one, else from the handler's `@RequestBody` type, with its field types, enums and validation annotations (`@NotNull`, `@Min` ...). The LLM drafts the traffic from that (a request template with `${seq}`, `${seed}` and `${int:MIN:MAX}`), where each request's effect lands, the reset, and up to five rules the data must keep, as SQL that returns the rows breaking them. Its answer is checked against the code, the request body and the migrations. A drafted rule (`drafted: true`) counts only once a baseline run without the fault has passed it: one that fails there is dropped, since the rule is wrong, not the app.

  `--compose deploy/docker-compose.yml --profile demo` runs the app as its Compose services: Faultline reads which of them consume from Kafka, where the API answers, and its helpers (P4 needs two workers, P5 and P6 the fake provider).

  It shows the plan and asks `Approve? [y / n / edit / review]`: `edit` opens the file, `review` goes through the fault, each check and each rule, and you keep (Enter) or drop (`n`) each one. Nothing is written until you approve.

## Onboard a repo you did not write: faultline doctor

```bash
./faultline doctor ../microservice-kafka --compose ../microservice-kafka/docker/docker-compose.yml
```

`doctor` is the first command on a new repo. It says whether Faultline can test it, and why not: Spring Boot on Java 17 or newer, `@KafkaListener` consumers (not Spring Cloud Stream or RabbitMQ), a Postgres database (not H2, MySQL or MongoDB), a Compose service that builds the app, and a complete checkout (on Windows, a clone silently leaves out paths that are too long: clone with `git -c core.longpaths=true`). Exit code 1 means out of scope.

Then it boots the stack once and writes `.faultline/<repo>/environment.json`, which `understand`, `scan` and `plan` read:

- **the app's services**: those that build a Spring Boot module of the repo (the Dockerfile's folder, or the build context), each judged by its configuration as it runs: the module's files, the profiles `SPRING_PROFILES_ACTIVE` switches on, and its Compose environment (`SPRING_DATASOURCE_URL` sets `spring.datasource.url`). Values Compose fills in from your shell or `.env` are not written down.
- **how Faultline reaches Kafka and each database from this machine**: a port the Compose file publishes, else Faultline's own override (`.faultline/<repo>/compose.faultline.yml`) publishes the database on 127.0.0.1 and gives Kafka a listener for this machine (apache/kafka, confluentinc/cp-kafka, bitnami/kafka and wurstmeister/kafka). The app's files stay as they are.
- **the live schema** of every database, as the app made it (migrations, Liquibase or Hibernate's `ddl-auto`), with the rows each table started with, and the topics and consumer groups.

Before booting it checks every port the stack publishes, and names the container that holds a taken one instead of moving it elsewhere; it also stops when a Dockerfile copies a jar that is not built yet, with the build command. Then it removes the stack again (`--keep` leaves it up). Exit code 2 means it could not run.

## Onboard an app: faultline/hints.yaml

What Faultline cannot read from the code goes in one file in the app's folder, `faultline/hints.yaml`, the only thing you add to the app. `plan` copies it into the plan:

```yaml
auth:                       # sent on every request
  header: Authorization
  value: Bearer ${env:SHOP_TOKEN}   # read from the environment or .env when the run starts, never written to a plan
seed: seed.sql              # SQL next to this file, loaded after every reset: test data the requests refer to
kafka:
  bootstrap: localhost:29092   # only for a broker doctor cannot give a listener of its own
images:                     # a helper service's image that today's Docker cannot pull, run as another (exact version)
  zookeeper: zookeeper:3.4.14
skip: [notification-service]   # services of the app to leave out, such as one that does not start under the repo's Compose file
```

The seed runs again after every reset, so write it to allow that (`insert ... on conflict do nothing`). If the app uses Spring Security and there is no `auth`, `plan` warns that its requests may be refused.
- **run** starts the app with the fault agent, sends the traffic, restarts the app after each planned crash, waits for Kafka lag 0, checks every request's effect and writes `report.html`. A plan says how often: by default 2 runs with the fault and 1 baseline run without it.

LLM answers are cached in `.faultline/cache/`, so running a step again on an unchanged app costs no call.

## Read the result

Each run writes `runs/<timestamp>/` with `plan.yaml` (the plan exactly as run, seed included), `result.json` and `report.html`, plus one folder per run (`run-1`, `run-2`, `baseline-1`) holding `history.jsonl` (every request and its outcome), `attempt.json`, `app.log` and `faults.jsonl` (one line per fault that fired).

The report labels the plan's risk:

- **confirmed**: the fault fired as planned, a data check failed in enough runs (the plan's `confirm.min_failing`), and every baseline run without the fault passed.
- **suspected**: the fault fired, but the data came out right (or wrong too rarely). Try it with the demo app's fix on:
  `./faultline run .faultline/demo-app/plan.yaml --seed 42 --app-arg=--payments.dedupe=claim-first`
- **not tested**: the fault never fired as planned, or the baseline failed too, so the runs say nothing either way.

Drafted rules that a baseline failed, or that no baseline checked, are left out of every label; the report lists them under "Rules left out".

## Break harder: Docker, process faults and the benchmark

A plan can run the app as Docker Compose services instead of a jar (`target.compose`). The demo app then runs as an `api` that takes the HTTP traffic and three workers that consume from Kafka, next to two helpers: Toxiproxy, which sits between each worker and Postgres, and `provider`, a fake payment provider the workers charge every payment at. Faultline recreates them all for each run with that run's settings and puts the fault agent where the fault needs it.

```bash
(cd demo-app && ./mvnw -q package -DskipTests)   # Faultline builds the app's image from this jar,
(cd tool && ./mvnw -q package -DskipTests)       # and the provider's from the CLI jar
./faultline benchmark                             # every planted bug, then the bug-free build
```

Faults come in six layers:

- **code** (the agent): `crash`, `delay`, `throw` or `barrier`, `before` or `after` any `Class#method` (or at `before-ack`), on every `nth_hit`th call. A barrier holds the call, for at most `duration`, until one of its `partners` (other services) returns from `until`, so a race between two workers happens every run.
- **process** (Docker): `kill`, `pause` (freeze) or `restart` a service `at` a time after the traffic starts, for a `duration`.
- **network** (Toxiproxy, `toxiproxy:` in the plan): add `latency` to, stall (`timeout`) or `cut` the connections of a proxy, such as `worker-1-db`, `at` a time for a `duration`.
- **service** (the stub, `stub:` in the plan): make the fake provider answer every `nth_hit`th charge with an `error`, `slow`ly, or charge it and reply too late (`late-reply`), or go through an `outage`, after which it stays down while it gets more than 50 requests a second.
- **traffic**: `duplicate` every `nth_hit`th request, like a client that submits twice.
- **messaging**: a chaos relay between two topics (`relay: {from, to}`) that will `duplicate`, `reorder` or `corrupt` every `nth_hit`th message. The app must publish to `from`.

Besides no-lost, no-duplicates and no-unexpected, a plan can check:

- `per-key-order`: requests with the same `order.key` applied in the order they happened.
- `no-lost-downstream`: every applied request also produced its event on the `downstream` topic.
- `recovery-within`: once the fault ended, everything sent before it was applied within `recovery_within`.
- its own `rules`: SQL that must return no rows, such as one charge per payment. The fake provider keeps its ledger in the same database (`stub.charges`), so a rule can check what the provider saw.

`traffic.warmup` sends that many requests before the run and waits until the app has finished them, then resets the database again: a JVM that has just started is slow on its first requests, and slow enough to cause a race of its own. Faults start counting after the warm-up.

The demo app's 10 planted bugs are switched by settings, all on by default; [benchmark/bugs.yaml](benchmark/bugs.yaml) lists them with the bug-free values and a hand-written plan for each. `faultline benchmark` scores the scan first: each bug that has a pattern is scanned with only that bug on and must be flagged at its location, and the bug-free build is scanned for every pattern, where anything flagged is a false flag (`--scan-only` stops there, `--skip-scan` leaves it out). Then it runs each plan with only its bug on, then again with every bug off, where nothing may be confirmed, and writes `benchmark.json`. `--plan` tries a bug with another plan, such as `./faultline benchmark --bug B10 --plan benchmark/b10-network.plan.yaml`, which reaches B10 through a slow database link instead of the agent.

`--ai-plans` runs the plans Faultline draws up itself instead of the hand-written ones: for each bug, the risk its scan flagged at the bug's location becomes a plan, as `faultline plan` would make it, run as the Compose services in `bugs.yaml`. Bugs without a code pattern (B9, B10) get no plan, and the scores say how many bugs the AI plans targeted and confirmed. `--ai-plans --scan-only` draws up the plans without running them.

Exit code 0 means every check passed, 1 means a check failed (or you did not approve a plan, or the benchmark confirmed a bug on the bug-free build), 2 means Faultline could not run.
