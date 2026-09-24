# 동네 캐시 dev 반영 전 점검 (2026-09-25)

실행안 `docs/superpowers/plans/2026-09-25-town-cache-dev-release.md`의 작업자 범위 결과다. 커밋·푸시·원격 접속은 하지 않았다. 민감값은 이 문서에 옮기지 않았다.

## 수정한 파일

| 파일 | 내용 |
|---|---|
| `.gitignore` | `!src/test/resources/config/application.yml` 예외 한 줄 추가 (주석 포함) |

- 이 예외로 새로 드러나는 yml은 그 파일 하나뿐이다(`git ls-files --others --exclude-standard`로 확인). 내용은 `solply.place-list-town-cache.redis.enabled: false` 한 키와 주석이며 민감값이 없다.
- 결과: `src/test/resources/config/`가 untracked로 보인다. 커밋에 넣을지는 메인이 정한다.
- 제품 코드는 고치지 않았다.

## 점검 결과

### 1. 배포 패키징 — 문제 없음

- CD는 `application.yml`(ignored)을 만들지 않고 `application-dev.yml`만 Secret으로 만든다. 그래서 dev jar에는 로컬 `application.yml`의 `solply.place-list-town-cache.*` 블록이 들어가지 않는다.
- 그래도 괜찮다. 새 설정은 모두 자바 기본값이 로컬 `application.yml` 값과 같다.
  - `PlaceListTownCacheProperties`: redis `enabled=true`, connect/command timeout 200ms, payload TTL 65분, publish retries 1, 로컬 보관 65분, 추정 용량 64MB, 요청 예산 1초
  - `PlaceListProperties.listSource` 기본값 `TOWN_LAZY_SORT`
- 새 코드에 기본값 없는 `@Value`는 없다(diff와 새 파일 전수 grep).
- 비교 측정 통로(`global/bench`, `benchChain`)는 `@Profile("bench")` + `solply.bench.enabled=true`를 함께 요구한다. dev 프로필에서는 서지 않는다.

### 2. Redis 키 앞머리 격리 — 확인

- `TownSnapshotStoreConfig`가 `app.env-prefix`(기본 `local`)를 키 앞머리로 쓴다.
- 메인 checkout의 `docker/docker-compose.dev.yml`(ignored) dev-app 환경변수에 `APP_ENV_PREFIX: dev`가 있다. Spring 느슨한 바인딩으로 `app.env-prefix`에 들어간다.
- 기존 S3 코드가 `${app.env-prefix}`를 기본값 없이 요구하므로, 이 값이 없으면 지금 dev도 기동하지 못한다. 즉 현재 떠 있는 dev에는 이미 값이 있다.
- 접속 대상은 기존 `spring.data.redis.*`(dev-app의 `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`)를 그대로 쓴다. 새 환경변수는 필요 없다.
- 단, 확인한 것은 로컬 사본이다. GitHub Secret(`DOCKER_COMPOSE_DEV_YML_BASE64`)이 로컬 사본과 같은지는 확인하지 못했다.

### 3. 테스트 설정 재현성

- 원래 `src/test/resources/config/application.yml`이 전역 `*.yml` 규칙에 걸려 새 체크아웃에 없었다. 그러면 기본 프로필 IT가 동네 공유 사본을 켠 채 돌아, 로컬 Redis 유무에 따라 결과가 갈린다. 위 예외로 해결.
- `application-test.yml`은 이미 추적 중이고, 이번 diff에서 같은 키(`redis.enabled: false`)를 추가했다.
- **남는 제약(이번 작업 이전부터 있던 것):** `@ActiveProfiles("test")`가 없는 IT가 많다(`AuthMySqlSupport` 계열, `PlaceListDbQueryRepositoryIT` 등). 이들은 ignored인 `src/main/resources/application.yml`에 기대므로 새 체크아웃에서는 그 파일 없이 전체 테스트가 돌지 않는다. CI는 `build -x test`라 배포에는 영향이 없다. 범위 밖이라 손대지 않았다.

### 4. Flyway V48·V49 — 배포 전 확인 필요

- dev-app은 `FLYWAY_ENABLED: "true"`라 기동 시 V48(`place_list_town_versions` 생성)과 V49가 적용된다.
- **V49는 한 장소에 서로 다른 MAIN 태그가 둘 이상이면 `SIGNAL`로 실패한다.** 실패하면 dev-app이 기동하지 못한다. 배포 전에 dev DB에서 아래 읽기 전용 쿼리가 0행인지 확인하는 것을 권한다.

  ```sql
  SELECT pt.place_id
  FROM place_tag pt
  JOIN tags t ON t.id = pt.tag_id
  JOIN place_stats ps ON ps.place_id = pt.place_id
  WHERE t.type = 'MAIN'
  GROUP BY pt.place_id
  HAVING COUNT(DISTINCT pt.tag_id) > 1;
  ```

- V49는 `place_stats`에 인덱스 5개를 만든다. dev 데이터 규모라면 짧게 끝날 것으로 보지만 측정하지 않았다.

### 5. 자원 설정 — 제안만, 값은 정하지 않음

- `docker/Dockerfile`에 JVM 옵션이 없고, 로컬 dev compose에 `cpus`/`mem_limit`도 없다. 이 경우 JVM 최대 힙은 호스트 메모리의 25%다.
- 동네 캐시는 추정 64MB에 전역 스냅샷 한 벌이 따로 상주한다(추정 바이트이지 힙 상한이 아니다).
- 기존 배포 스크립트와 가장 적게 부딪히는 방법: Dockerfile은 두고, dev compose Secret의 dev-app `environment`에 `JAVA_TOOL_OPTIONS`(예: `-XX:MaxRAMPercentage=...` 또는 `-Xmx...`)를 넣고 필요하면 `mem_limit`을 함께 둔다. `deploy.sh`가 dev-app만 `--force-recreate --no-deps`로 다시 만들므로 이 변경은 다음 배포에 그대로 반영된다.
- 캐시 용량 조정이 필요하면 환경변수 `SOLPLY_PLACELISTTOWNCACHE_MAXESTIMATEDBYTES`로 덮을 수 있다(느슨한 바인딩).
- 실제 호스트 사양을 받은 뒤 값을 정한다.

## HTTP 스모크 경로

- `GET /api/towns` — 인증 없이 허용. 동네 id를 얻는다.
- `GET /api/places?townId={id}&isBookmarkSearch=false&sort=POPULAR&size=10` — 인증 없이 허용(`SecurityConfig`의 `GET /api/places` permitAll). 정적 5축(LATEST·POPULAR·RATING·REVIEW_COUNT·BOOKMARK_COUNT)이 동네 경로로 간다.
  - 응답의 `nextCursor`로 2페이지를 한 번 더 부르면 커서(동네별 번호) 경로까지 지난다.
  - 시 단위 id를 넣으면 하위 동네 합집합 경로를 지난다.
  - `sort=DISTANCE`는 기존 전역 경로이며 첫 페이지에 `latitude`/`longitude`가 필요하다.
- 인증 사용자 경로(북마크 여부 표시)가 필요하면 `POST /api/test/login/{userId}`로 **기존** 테스트 유저 토큰을 받는다. 프로필 제한이 없는 공개 엔드포인트다.
  - `POST /api/test/login`(인자 없음)은 유저를 새로 만든다 — dev 데이터가 생기므로 쓰지 않는다.
  - 두 엔드포인트 모두 로그인 시 토큰 저장이 일어날 수 있다. 토큰은 출력하지 않는다.
  - 기존 테스트 유저 id는 메인이 정해야 한다.
- 관리자 API(`/api/admin/**`)는 쓰지 않는다.
- 관측: `/actuator/health`, `/actuator/prometheus`는 허용돼 있다.

## 검증

| 항목 | 결과 |
|---|---|
| `git check-ignore -v src/test/resources/config/application.yml` | 새 예외(`.gitignore:43`)에 걸려 무시되지 않음 |
| 새로 드러난 yml | 그 파일 하나 |
| `./gradlew test --tests 'org.sopt.solply_server.domain.place.cache.town.*' --tests '*PlaceListRequestOrchestratorTest' --tests '*PlaceServiceSnapshotSourceTest' --tests '*SolplyServerApplicationTests'` | 211건 통과, 실패·오류 0 |

미검증:
- 전체 829건 회귀는 다시 돌리지 않았다(제품 코드를 바꾸지 않았다).
- GitHub Secret의 `APPLICATION_DEV_YML`·`DOCKER_COMPOSE_DEV_YML_BASE64`가 로컬 사본과 같은지.
- dev DB의 V49 선행 조건.
- 배포된 dev의 HTTP 응답.

## 메인이 채워야 할 정보

1. 실제 dev 배포 대상 호스트와 메모리·CPU (JVM 옵션·`mem_limit` 값을 정하려면 필요)
2. dev DB에서 위 V49 선행 쿼리 결과
3. dev Compose Secret에 `APP_ENV_PREFIX: dev`가 있는지 (로컬 사본에는 있음)
4. 인증 스모크를 할 경우 쓸 기존 테스트 유저 id
