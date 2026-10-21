# AGENTS.md

This file is the agent-facing working agreement for the `SmartRAG` repository.

## Purpose

SmartRAG (SmartRAG) is an enterprise AI knowledge management system built around a RAG workflow:

- Spring Boot backend
- Vue 3 + TypeScript frontend
- MySQL for primary persistence
- Redis for cache/session/short-lived chat context
- Elasticsearch for retrieval
- Kafka for async file processing
- MinIO for object storage

Use this file as the first repo-local reference before making changes.

## First Read

When a new agent thread starts in this repo, check these in order:

1. `AGENTS.md`
2. `CLAUDE.md`
3. repo root `.env` and `src/main/resources/application*.yml`
4. `frontend/.env*`
5. current runtime state: frontend dev server, backend process, ports, and browser behavior

Do not assume generic Spring Boot conventions before checking the actual local setup.

## Local Runtime Rules

### Backend

This project is typically run from the IDE with hot deployment.

Important:

- Do not restart the backend by default.
- After changing Java code, prefer compiling to trigger hot reload:

```bash
mvn -q -DskipTests compile
```

- Only attempt a manual restart if the user explicitly asks for it or if runtime evidence proves hot reload is not active.

### Frontend

The frontend is usually already running in dev mode.

Preferred validation target:

```text
http://localhost:9527
```

Do not waste time on full rebuild/deploy loops when the local dev server is already live.

### Browser Verification

For UI or interaction bugs, verify in a real browser.

Common pages:

- `http://localhost:9527/#/chat`
- `http://localhost:9527/#/chat-history`

When validating frontend issues:

1. open the real page
2. inspect network requests and response bodies
3. inspect console output
4. only then decide whether the bug is frontend, backend, data, or environment

## Configuration Sources

### Backend config

Primary local config comes from:

- repo root `.env`
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`

Typical local values include:

- MySQL on `localhost:3306`
- Redis on `localhost:6379`
- backend on `localhost:8081`

The local `.env` may contain values with special characters. Do not casually `source .env` in shell snippets unless you know the contents are shell-safe.

### Frontend config

Primary frontend config comes from:

- `frontend/.env`
- `frontend/.env.test`
- Vite proxy/runtime env

In local dev, the frontend proxies backend requests through the Vite dev server, so browser network requests may appear as:

- `/proxy-default/...`

even though the backend target is `http://localhost:8081/api/v1`.

## Repo-Specific Engineering Notes

### Chat history

- Redis is for short-lived chat context and session state.
- Persistent history must live in MySQL.
- When debugging chat history, inspect all three layers:
  - browser request/response
  - backend controller/service path
  - database contents

Do not stop at “frontend shows empty”; verify whether:

- the request was sent
- the backend returned an empty array
- the database actually contains rows
- the running backend has loaded the latest code

### Multi-tenant behavior

SmartRAG uses organization tags and user/org relationships.

When changing queries or admin views, verify whether filtering is driven by:

- user identity
- org tag
- role
- explicit query params

Do not assume “missing data” is only a UI issue; it may be an unintended filter.

### References / retrieval evidence

If a chat response contains reference mappings, preserve them through persistence and history rendering. Do not regress the reference preview path when changing chat history storage.

## Preferred Commands

### Backend

Compile only:

```bash
mvn -q -DskipTests compile
```

Run tests when needed:

```bash
mvn test
```

### Frontend

Targeted lint:

```bash
cd frontend && pnpm exec eslint <file>
```

Type check:

```bash
cd frontend && pnpm typecheck
```

## Editing Guidance

- Preserve existing repo patterns.
- Prefer minimal, end-to-end fixes over speculative refactors.
- If a change touches frontend and backend behavior, verify both sides.
- If a fix depends on runtime state, re-run the natural validation path after the change.

## What To Avoid

- Do not assume restart-first backend workflows.
- Do not assume Redis means durable persistence.
- Do not claim a UI issue is fixed without checking the live page.
- Do not change shell startup files or global environment for temporary repo tests.
- Do not rely only on source inspection when runtime evidence is cheap to collect.

## Done Criteria

A task is not complete if any of these are still unclear:

- which runtime is active
- whether the latest code is actually loaded
- what the browser request returned
- whether the backing data store contains the expected data

For this repo, “done” usually means:

1. code changed
2. backend compiled
3. browser re-tested
4. network response confirmed
5. any relevant DB/Redis state checked when the symptom is data-related
