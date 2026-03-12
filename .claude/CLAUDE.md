# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Communication

항상 한국어로 답변한다.

## Project Overview

SOLPLY (솔플리) is a solo leisure place and course curation service built with Java 21 + Spring Boot 3.3.5. It provides personalized recommendations for solo activities based on user interests.

## Common Commands

### Build & Run
```bash
# Build (skip tests)
./gradlew build -x test

# Run locally
./gradlew bootRun

# Run with specific profile
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Testing
```bash
# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "org.sopt.solply_server.domain.bookmark.BookmarkEventListenerTest"

# Run tests with output
./gradlew test --info
```

### Database Migrations (Flyway)
```bash
# Run migrations for dev
./gradlew flywayMigrate -PflywayUrl=... -PflywayUser=... -PflywayPassword=...

# Check migration info
./gradlew flywayInfo
```

### Docker
```bash
# Start dev environment
docker-compose -f docker/docker-compose.dev.yml up -d

# Build image
docker build -f docker/Dockerfile -t solply-server .
```

## Architecture

### Package Structure

```
org.sopt.solply_server/
├── domain/          # Business domains (DDD-style)
└── global/          # Cross-cutting infrastructure
```

**Domain modules** (`domain/`): Each follows a consistent layered structure:
- `controller/` → REST endpoints
- `service/` → Business logic (some use facade pattern, e.g. `CoursePlaceService`)
- `entity/` → JPA entities
- `repository/` → Spring Data JPA + QueryDSL repositories
- `dto/` → Request/response DTOs
- `cache/` → Domain-specific Redis caching

**Domains:** admin, auth, bookmark, course, file, place, recommend, review, tag, test, town, user

**Global infrastructure** (`global/`):
- `config/` → Spring configuration (Security, Redis, QueryDSL, Swagger, AWS, Feign)
- `exception/` → `ErrorCode` enum, `BusinessException` hierarchy, `GlobalExceptionHandler`
- `jwt/` → JWT filter, provider, resolver
- `dto/` → `CustomApiResponse<T>` — all API responses use this wrapper
- `annotation/` → `@CurrentUserId`, `@CurrentSocialLoginPlatform` for controller params

### Key Patterns

**API Response:** All endpoints return `CustomApiResponse<T>` wrapper.

**Exception Handling:** Throw `BusinessException(ErrorCode)` or its subclasses (`EntityNotFoundException`, `UnauthorizedException`). `GlobalExceptionHandler` handles all.

**Authentication:** JWT-based. Use `@CurrentUserId` annotation on controller parameters to get the authenticated user's ID. OAuth2 providers: Kakao, Apple, Google.

**QueryDSL:** Used for complex queries. Generated Q-classes are in `build/generated/querydsl/`. Run `./gradlew compileJava` to regenerate.

**Database Migrations:** Flyway scripts in `src/main/resources/db/migration/`. DDL mode is `validate` — schema changes require a new migration file (`V{n}__description.sql`).

**Soft Delete:** Entities use `is_deleted` flag with `deleted_at` timestamp. Do not hard-delete user data.

### Environments & Configuration

| Profile | Config File | Notes |
|---------|-------------|-------|
| (default/local) | `application.yml` | MySQL on localhost:3308, Redis on localhost:6381 |
| dev | `application-dev.yml` | Uses env vars (DB_URL, REDIS_HOST, etc.) |
| prod | `application-prod.yml` | Uses env vars |

Swagger UI (`/swagger-ui/index.html`) is only enabled on the `dev` profile.

### External Integrations
- **AWS S3 + CloudFront**: File uploads via presigned URLs
- **Redis**: Sessions, caching, recommendations
- **Discord Webhook**: Optional notifications
- **Feign Clients**: External OAuth provider APIs


