# Monjaro

A small Jira-style backend for managing projects, memberships, tasks, and progress. Built as an internship portfolio project with Java 21, Spring Boot, Spring Security, PostgreSQL, and Docker.

## Run with Docker

A local `.env` with random credentials was prepared in this workspace. For a fresh clone, install Docker Desktop (or Docker Engine with Compose), then:

```sh
cp .env.example .env
```

Edit `.env`: replace `DATABASE_PASSWORD` and set `JWT_SECRET` to a random secret of at least 32 bytes. Generate a suitable secret with `openssl rand -hex 32`. Do not commit `.env`.

```sh
docker compose up --build -d
docker compose logs -f app
```

The first build downloads Java/Maven dependencies. Once the logs report the application has started:

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- API base: http://localhost:8080/api

Only Docker is required for this path. PostgreSQL data persists in the `postgres_data` volume; Flyway runs migrations automatically on startup. Both published ports bind to localhost.

Stop the services with `docker compose down`. This preserves database records. Changing the database password in `.env` does not change the password of an already initialized PostgreSQL volume; change it inside PostgreSQL as well.

## First API calls

Register a user (the response includes a token):

```sh
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alex","email":"alex@example.com","password":"a-long-demo-password"}'
```

Alternatively, `POST /api/auth/login` accepts `email` and `password`. Copy the returned `accessToken` into Swagger's **Authorize** dialog, or use it as `TOKEN` below:

```sh
TOKEN='paste-access-token-here'
curl -X POST http://localhost:8080/api/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Internship portfolio","description":"Build and document my backend"}'
```

Copy the returned project ID:

```sh
PROJECT_ID='paste-project-id-here'
curl -X POST "http://localhost:8080/api/projects/$PROJECT_ID/tasks" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Implement authentication","priority":"HIGH"}'
```

Register a second account, then use the owner's token to add it with `POST /api/projects/{id}/members` and `{"email":"second@example.com","role":"MEMBER"}`. The response contains its `userId`, which can be used as a task's `assigneeId`.

## API

All endpoints except registration, login, and API documentation require a bearer token.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/auth/register` | Register and receive a token |
| POST | `/api/auth/login` | Log in and receive a token |
| GET | `/api/users/me` | Read your profile |
| GET / POST | `/api/projects` | List your projects / create a project |
| GET / PATCH / DELETE | `/api/projects/{id}` | Read / update / delete a project |
| GET / POST | `/api/projects/{id}/members` | List members / add an existing account |
| PATCH / DELETE | `/api/projects/{id}/members/{userId}` | Change a role / remove a member |
| GET / POST | `/api/projects/{id}/tasks` | List tasks / create a task |
| GET / PATCH / DELETE | `/api/projects/{id}/tasks/{taskId}` | Read / update / delete a task |
| GET | `/api/projects/{id}/progress` | Task counts and completion percentage |

Project and task lists use zero-based `page` (default 0) and `size` (default 20, maximum 100). Responses contain `content`, `page`, `size`, and `totalElements`. Task lists accept `status`, `priority`, and `assigneeId` filters, for example:

```text
GET /api/projects/{id}/tasks?status=IN_PROGRESS&priority=HIGH&page=0&size=20
```

### Domain rules

- Registration requires a unique username (3–40 letters, digits, or underscores), unique email, and a 12–72-character printable ASCII password. The password bound follows BCrypt's 72-byte limit. Usernames and emails are stored lowercase; login uses email.
- Passwords are BCrypt-hashed and never returned. HS256 JWTs expire after 30 minutes (with the decoder's standard clock-skew tolerance). There are no refresh tokens or server-side logout; log in again after expiry. Changing `JWT_SECRET` invalidates existing tokens.
- Any account can create a project and becomes its single owner. Ownership cannot be transferred or removed in this MVP.
- Project names are required; descriptions are optional. Owners can edit or delete their projects and manage memberships. Only existing users can be added; no invitation email is sent.
- Members can create, edit, assign, and delete all tasks in their project. Viewers can only read. Project permissions are checked against the database on every request; demotion/removal takes effect without issuing a new JWT.
- Tasks have a required title, optional description, status (`TODO`, `IN_PROGRESS`, `DONE`), priority (`LOW`, `MEDIUM`, `HIGH`), optional assignee, and optional ISO date (`YYYY-MM-DD`). Defaults are `TODO` and `MEDIUM`. Any status transition is allowed. Past due dates are allowed.
- An assignee must belong to that project (including owners or viewers). Removing a member unassigns their tasks atomically. The composite database foreign key also enforces membership.
- PATCH preserves omitted fields. For tasks, explicit `null` clears `assigneeId` or `dueDate`; other nullable PATCH values are ignored. Empty strings clear descriptions. Unknown fields and invalid enum values are rejected.
- Progress is `done / total * 100`, rounded to two decimal places; empty projects report 0%.
- Outsiders receive 404 for project resources. A member without sufficient permissions receives 403. Invalid input returns 400, duplicate identities or memberships return 409, and missing/invalid tokens return 401. Error responses use problem-detail JSON.
- Project deletion cascades to its tasks and memberships. Project writes use a database row lock so membership changes and task assignment cannot race each other. This favors simple, correct behavior for a small application.

## Development and tests

For local Java development, install JDK 21, Maven 3.9+, and Docker. Start only PostgreSQL:

```sh
docker compose up -d db
```

Set `DATABASE_PASSWORD` and `JWT_SECRET` in your shell or IDE (Spring Boot does not load `.env` itself). The default database URL is `jdbc:postgresql://localhost:5432/monjaro` with user `monjaro`.

```sh
mvn spring-boot:run
```

Run the integration tests:

```sh
mvn verify
```

Tests require a running Docker daemon. Testcontainers creates an isolated PostgreSQL 17 instance and supplies test-only credentials; no `.env` or existing development database is needed. Tests cover registration, password storage, login, expired tokens, project visibility, roles, ownership protection, task CRUD, filtering/pagination, progress, PATCH clearing, project/task isolation, and member-removal cleanup. Tests fail if Docker is unavailable; they do not silently skip. GitHub Actions runs the tests and builds the runtime image.

The Docker image build skips tests because Testcontainers requires a Docker daemon; run `mvn verify` separately or use CI before deployment.

### Layout

```text
src/main/java/com/monjaro/
  auth/       Registration, login, JWT configuration
  users/      Profile and user lookup
  projects/   Projects, membership permissions, progress
  tasks/      Tasks, filtering, assignment
  common/     Error handling, pagination, OpenAPI
src/main/resources/db/migration/  Versioned Flyway schema
src/test/java/com/monjaro/        PostgreSQL integration tests
```

The implementation uses Spring JDBC and explicit SQL to make joins, constraints, and transactions visible. Services own business rules; controllers validate HTTP input. Authentication is built on Spring Security's JWT resource-server support. Versions follow [Spring Boot's Java compatibility](https://docs.spring.io/spring-boot/3.5/system-requirements.html) and [springdoc's compatibility matrix](https://springdoc.org/v2/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot).

## MVP boundaries

No frontend, comments, attachments, subtasks, invitations, password reset, or refresh tokens. This is a local portfolio MVP; no hosted service has been provisioned. A future internet-facing deployment should add HTTPS, authentication rate limits, and an account recovery flow.

## Verification status

All 19 Java source files passed Java 21 syntax parsing using an IDE-bundled JDK. The Maven POM passed XML parsing, and application, Compose, and CI configuration passed YAML parsing. These checks do not verify Java types, dependency compatibility, or application behavior. Compilation, automated tests, and Docker startup have **not** been run: Maven, Docker, and outbound dependency-download access were unavailable. Run `mvn verify` and `docker compose up --build` in a suitable environment before treating the app as verified.
