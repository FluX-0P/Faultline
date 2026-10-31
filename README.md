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

Kafka listens on `localhost:9092` (from your machine) and `kafka:19092` (from containers). Postgres is on `localhost:5432` (db/user/password: `demo`).

## Setup

```bash
cp .env.example .env                              # then add your LLM key (free at https://aistudio.google.com)
(cd demo-app && ./mvnw -q package -DskipTests)    # build the demo app jar
(cd tool && ./mvnw -q package -DskipTests)        # build the CLI and the fault agent
```

Faultline talks to any OpenAI-compatible LLM API; Google Gemini is the default. The settings are in [.env.example](.env.example).

## Point Faultline at an app

Four steps, each writing a file the next one reads. Everything about an app goes in `.faultline/<app>/`, outside the app itself.

```bash
./faultline understand demo-app   # LLM maps the app        -> .faultline/demo-app/app-map.json
./faultline scan demo-app         # JavaParser + LLM review -> .faultline/demo-app/risks.json
./faultline plan demo-app         # draft a plan, you approve it -> .faultline/demo-app/plan.yaml
./faultline run .faultline/demo-app/plan.yaml --seed 42
```

- **understand** reads the docs, Spring config, migrations, build files and the signatures of every endpoint and listener, and asks the LLM for the app's entry points, data stores and user journeys. Every handler it names is checked against the code.
- **scan** finds Kafka listeners that save, then acknowledge (pattern P1), and asks the LLM whether a duplicate check in the same transaction protects them. Code reaches the LLM without comments, so it judges what the code does.
- **plan** turns the most confident risk into `plan.yaml`: the fault, the traffic (a request template with `${seq}`, `${seed}` and `${int:MIN:MAX}`), where each request's effect lands, and the checks. It shows the plan and asks `Approve? [y / n / edit]`; nothing is written until you approve.
- **run** starts the app with the fault agent, sends the traffic, restarts the app after each planned crash, waits for Kafka lag 0, checks every request's effect and writes `report.html`. A plan says how often: by default 2 runs with the fault and 1 baseline run without it.

LLM answers are cached in `.faultline/cache/`, so running a step again on an unchanged app costs no call.

## Read the result

Each run writes `runs/<timestamp>/` with `plan.yaml` (the plan exactly as run, seed included), `result.json` and `report.html`, plus one folder per run (`run-1`, `run-2`, `baseline-1`) holding `history.jsonl` (every request and its outcome), `attempt.json`, `app.log` and `faults.jsonl` (one line per fault that fired).

The report labels the plan's risk:

- **confirmed**: the fault fired as planned, a data check failed in enough runs (the plan's `confirm.min_failing`), and every baseline run without the fault passed.
- **suspected**: the fault fired, but the data came out right (or wrong too rarely). Try it with the demo app's fix on:
  `./faultline run .faultline/demo-app/plan.yaml --seed 42 --app-arg=--payments.dedupe=claim-first`
- **not tested**: the fault never fired as planned, or the baseline failed too, so the runs say nothing either way.

## Break harder: Docker, process faults and the benchmark

A plan can run the app as Docker Compose services instead of a jar (`target.compose`). The demo app then runs as an `api` that takes the HTTP traffic and three workers that consume from Kafka; Faultline recreates them for each run with that run's settings and puts the fault agent on one of them.

```bash
(cd demo-app && ./mvnw -q package -DskipTests)   # Faultline builds the image from this jar
./faultline benchmark                             # every planted bug, then the bug-free build
```

Faults come in four layers:

- **code** (the agent): `crash`, `delay` or `throw`, `before` or `after` any `Class#method` (or at `before-ack`), on every `nth_hit`th call.
- **process** (Docker): `kill`, `pause` (freeze) or `restart` a service `at` a time after the traffic starts, for a `duration`.
- **traffic**: `duplicate` every `nth_hit`th request, like a client that submits twice.
- **messaging**: a chaos relay between two topics (`relay: {from, to}`) that will `duplicate`, `reorder` or `corrupt` every `nth_hit`th message. The app must publish to `from`.

Besides no-lost, no-duplicates and no-unexpected, a plan can check `per-key-order` (requests with the same `order.key` applied in the order they happened) and its own `rules`: SQL that must return no rows, such as one charge per payment.

The demo app's planted bugs are switched by settings, all on by default; [benchmark/bugs.yaml](benchmark/bugs.yaml) lists them with the bug-free values and a hand-written plan for each. `faultline benchmark` runs each plan with only its bug on, then again with every bug off, where nothing may be confirmed, and writes `benchmark.json`. So far it covers B1, B3, B7, B9 and B10; B2, B4, B5, B6 and B8 come in part 3 of Phase 5.

Exit code 0 means every check passed, 1 means a check failed (or you did not approve a plan, or the benchmark confirmed a bug on the bug-free build), 2 means Faultline could not run.
