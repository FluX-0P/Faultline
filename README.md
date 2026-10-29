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
