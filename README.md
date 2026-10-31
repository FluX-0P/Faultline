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
- **run** starts the app with the fault agent, sends the traffic, restarts the app after each planned crash, waits for Kafka lag 0, checks every request's effect and writes `report.html`.

LLM answers are cached in `.faultline/cache/`, so running a step again on an unchanged app costs no call.

## Read the result

Each run writes `runs/<timestamp>/`: `plan.yaml` (the plan exactly as run, seed included), `run.json`, `history.jsonl` (every request and its outcome), `result.json`, `app.log`, `faults.jsonl` (one line per crash) and `report.html`.

The report labels the plan's risk:

- **confirmed**: the fault fired as planned and a check failed. With the demo app's bug B1: 3 crashes, 3 payments applied twice.
- **suspected**: the scan flagged it, the fault fired, and every check passed. Try it with the B1 fix on:
  `./faultline run .faultline/demo-app/plan.yaml --seed 42 --app-arg=--demo.fixes.dedupe=true`
- **not tested**: the fault never fired, so the run says nothing either way.

Exit code 0 means every check passed, 1 means a check failed (or you did not approve a plan), 2 means Faultline could not run.
