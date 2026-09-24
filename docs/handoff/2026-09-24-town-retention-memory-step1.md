# 단계 1·2 보고 — 동네 캐시 보관 메모리 조사와 TownPlaces 크기 계측

캠페인: `load-test/campaigns/2026-09-24_town-retention-memory/README.md` 1·2단계.
작성: 2026-09-24. 수행 모델: Claude Opus 5.5 (`claude-opus-5-5`, 세션 환경 정보로 확인).
제품 코드·설정·커밋·푸시·부하 테스트 없음. 기존 변경(dirty 파일)은 건드리지 않았다.

## 요약

- 현재 벤치 데이터는 **6,320 장소 / 66 동네**(동네당 42~101개)다. 제품의 적재 문장·변환 코드·팩토리로 만든 `TownPlaces` 한 벌은 **배열 없이 1.97 MiB(327 B/장소), 정적 5축 배열 포함 시 2.11 MiB(350 B/장소)**다.
- 지금 용량 상한 `max-places=200,000`을 채우면 **약 63 MiB(배열 없음)~68 MiB(5축 전부)**다. 이 값은 합성 데이터로 잰 것이며 표에 합성 표시를 했다.
- 적용 중인 스케줄(application.yml이 기본값을 덮음)로 보면, 65분 창 안에서 동네 하나의 버전은 **평시 최대 8개, 01:00~01:50 부근에서 최대 9개**가 생길 수 있다. 관리자 수정(A회)은 별도로 더해지고, 배치 커밋 지연까지 치면 경계에서 +1이 생길 수 있다.
- 전 동네 × 9버전 × 5축은 현재 데이터 기준 약 19 MiB(계산값)다. 1,280 MiB 힙에서 **캐시 그래프는 이번 판단의 병목이 아니다**. 적재 중 임시 메모리와 비캐시 기본 힙은 이번에 재지 않았다(5단계 범위).

## 1. 유효 배치 설정과 버전 증가 경로

설정 출처: `src/main/resources/application.yml`의 `solply.place-stats.*`. `application-dev.yml`·`application-prod.yml`·`docker/docker-compose.bench.yml`에는 이 키가 없다(`grep -n "cron\|place-stats"` 결과 없음). 운영 compose 원본은 GitHub Secret에 있어 **환경변수로 덮어쓰는지는 확인하지 못했다**.

| 회차 | 코드 기본값 | 적용값 | 올리는 버전 | 호출 |
|---|---|---|---|---|
| 리뷰 카운트 | `0 5/15 * * * *` | **`0 30 * * * *`** (매시 :30) | 전 동네 | `PlaceStatsBatchProcessor#recalculateReviewCounts` → `markAllTownsChanged` |
| 북마크 델타 | `0 0/15 * * * *` | 기본값 그대로 (:00 :15 :30 :45) | **해당 장소가 속한 동네만**, 소비한 전표가 있을 때만 | `BookmarkCountDeltaProcessor#consumeAndApply` → `markSortKeysChanged` |
| 인기점수 | `0 10 * * * *` | **`0 0 1 * * *`** (매일 01:00) | 전 동네 | `updateScores` → `markAllTownsChanged` |
| 카운트 안전망 | `0 25 1 * * *` | **`0 45 1 * * *`** (매일 01:45) | 전 동네 | `recalculateCountsAndClearOutbox` → `markAllTownsChanged` |
| 기동 시 최초 적재·채점 | — | 비어 있을 때 한 번 | 전 동네 | `recalculateCountsIfEmpty` / `recalculateScoresIfNeverScored` |
| 관리자 장소 수정 | — | 수시 | 지문(동네, 태그 비트마스크)이 바뀐 장소의 이전·이후 동네 | `AdminPlaceService` → `markChangedIfSearchAffecting` |

버전을 올리지 않는 경로: 이미지 필드(`PlaceImageFieldUpdater:67`), 태그 이름 변경(`AdminTagService:190`). `markTownsChanged`는 호출하는 곳이 없다. 버전 증가는 모두 배치·쓰기 트랜잭션 안에서 일어나므로 롤백되면 함께 사라진다.

### 65분 창 안의 버전 수 상한

보관 단위는 `(동네, 버전)`이고, 버전은 요청이 적재해야 캐시에 들어간다. 적재 시점부터 TTL 65분을 세면, 어떤 순간에 남아 있는 한 동네의 버전 수는 **직전 65분 동안 한 번이라도 최신이었던 버전 수 = 1 + 그 창 안의 증가 횟수**를 넘지 못한다.

| 창 위치 | 리뷰(매시) | 델타(15분) | 일일 2회 | 증가 합 | 버전 상한 |
|---|---|---|---|---|---|
| 평시 (예: 10:25~11:30) | 2 | 5 | 0 | 7 | **8** |
| 01시대 (예: 00:30~01:35, 00:40~01:45) | 1~2 | 5 | 1~2 | 8 | **9** |

- 계산 근거: 65분 창에는 60분 간격 사건이 최대 2개, 15분 간격 사건이 최대 5개 들어간다. 01:00과 01:45는 45분 차이라 한 창에 같이 들어갈 수 있다. 00:30 리뷰와 01:45 안전망은 75분 차이라 한 창에 같이 들어가지 않는다.
- :30에는 리뷰와 델타가 같이 돈다. 락 이름이 다르고 트랜잭션도 따로라서 버전이 2 오른다. 위 표에 이미 반영했다.
- 델타의 5회는 그 동네에 15분 안에 북마크 토글이 있었을 때만 생긴다. 토글이 없는 동네는 평시 3개가 상한이다.
- **표에 들어가지 않는 것:** 관리자 수정 A회(상한 없음). 배치가 길어져 커밋이 발화 시각보다 늦어지는 경우(`lockAtMostFor` 10분/30분 안)에는 경계에서 +1이 생길 수 있다. 그래서 보수적인 상한은 **평시 9, 01시대 10, 여기에 A를 더한 값**이다.
- 이 상한은 "65분 = 매시 배치 + 여유 5분"이라는 후보가 **평시 2버전**을 보장한다는 뜻이 아니다. 델타 때문에 최대 8버전까지 될 수 있다.

## 2. 데이터 규모와 분포 (벤치 DB, 읽기 전용)

벤치 DB: 기존 컨테이너 `solply-bench-mysql`을 `docker start`만 해서 띄웠다. 볼륨은 기존 `docker_solply-bench-mysql-data`를 그대로 썼다. 다른 서비스와 볼륨에는 손대지 않았다. 컨테이너의 실제 메모리 제한은 1.5 GiB로 compose의 `3g`와 다르다(`docker inspect` 값, 이번에는 바꾸지 않았다).

```
flyway_schema_history 최신: 48  (워크트리에는 V49가 미적용으로 있다)
place_stats: 6,320행, 66동네 / places 6,320 / bookmarks ~1,010만 / place_reviews ~19.6만
동네당 장소 수: min 42, max 101, avg 95.8 — 42·46·55·56·59·62·99 각 1곳, 100 58곳, 101 1곳
place_list_town_versions: 66행, version 1~4
name: 평균 11.65자(최대 15), 6,311/6,320이 비ASCII → UTF-16 문자열
thumbnail_file_key: 평균 17.2자(최대 44), NULL 1개 / main_tag_id ≤ 6 / 좌표 NULL 없음 / max place_id 16000
```

이 데이터는 시드 생성기(`load-test/seed/generate-bench-seed.mjs` 계열)가 만든 것이다. **운영 데이터의 문자열 길이·분포와 같다는 근거는 없다.**

## 3. TownPlaces 메모리 계측

### 방법

- 하니스: `load-test/campaigns/2026-09-24_town-retention-memory/bench/TownFootprint.java`. 원본 출력: `results/footprint-run2.txt`.
- 입력: 제품 `TownSourceLoader`와 같은 SELECT로 벤치 `place_stats` 전체 행을 JDBC `getObject`로 읽었다. 컬럼 타입은 `Long,Long,Long,BigDecimal,LocalDateTime,Integer,Integer,BigDecimal,Double,Double,String,Long,String`이다.
- 변환: 제품의 `TownSourceLoader#toEntry`/`#toView`(private static)를 리플렉션으로 그대로 호출했다. 동네별 묶기만 `assemble`을 옮겨 적었고, 객체 생성은 제품의 `TownPlaces.objectsOnly` / `TownPlaces.presorted`를 썼다. 합성 데이터에 5축을 붙일 때는 제품의 `order()`를 호출했다.
- 차이: 운영 경로는 Hibernate 네이티브 쿼리를 거친다. 날짜 타입(Timestamp 또는 LocalDateTime)이 다를 수 있지만 epoch로 바꾼 뒤 버려지므로 보관 크기에는 영향이 없다. 문자열은 두 경로 모두 드라이버의 `getString`이 만든다.
- JVM: 호스트 Temurin 21.0.8 arm64, `-Xms1280m -Xmx1280m`, G1. 압축 oop·클래스 포인터 사용, 8바이트 정렬. 벤치 앱 이미지(eclipse-temurin:21-jre-alpine, linux/arm64)의 기본 레이아웃과 같은 조건이다.
- **정의 1, reachable(JOL `GraphLayout`):** 주어진 뿌리에서 닿는 모든 객체의 얕은 크기 합. identity 기준으로 중복을 제거한다.
- **정의 2, graph-exclusive 추정("retained"):** `|X ∪ S| − |S|`를 한 번의 파싱으로 구한다. S는 JVM 공유 객체인 `PlaceSortType` 상수와 `Long` 캐시(−128~127), `Boolean`, `""`다. **GC 루트 지배(dominator) 관계를 증명한 값이 아니다.** 요청이 붙잡은 참조(`Gathered`)가 있거나 다른 버전과 객체를 공유하는 경우는 반영하지 않았다. 지금 코드는 버전마다 DB에서 새로 읽어 새 객체를 만들므로, 버전 사이에 공유되는 객체는 없다고 봤다.
- **교차 확인, `jcmd GC.class_histogram` 전후 차이:** full GC 뒤 live 객체의 클래스별 증가분이다. 캐시 그래프만이 아니라 하니스 JVM 전체의 변화이며, **벤치 앱의 기본 힙으로 쓰면 안 된다.**
- 인덱스: 현재 `TownPlaces`에는 태그 인덱스가 없다(스캔 구조를 유지). 그래서 측정값에도 포함되지 않는다.
- 폐기한 1차 실행: 처음에는 JOL `subtract`로 공유 객체를 뺐다. 이 방식은 주소를 비교하기 때문에 두 번의 파싱 사이에 GC가 객체를 옮기면 틀린다. 실제로 `PlaceSortType`이 빠지지 않았고, 같은 실행에서 히스토그램 파싱 예외도 났다. 두 문제를 고친 2차 실행 결과만 남겼다.

### 실데이터 (6,320 장소 / 66 동네)

| 구성 | reachable | graph-exclusive | B/장소 |
|---|---|---|---|
| 배열 없음(`objectsOnly`, 적재 직후 채택 구조) | 2,068,272 B (1.97 MiB) | 2,065,224 B (1.97 MiB) | 326.8 |
| 정적 5축 전부(`presorted`) | 2,216,288 B (2.11 MiB) | 2,212,824 B (2.11 MiB) | 350.1 |

동네별 graph-exclusive 크기: 배열 없음 min 14,648 / median 32,640 / max 32,920 B, 5축 min 15,808 / median 34,960 / max 35,280 B.

히스토그램 교차 확인: 배열 없음 +2.10 MiB, 5축 +2.29 MiB. JOL 값보다 약 0.13~0.18 MiB 크다. 차이는 매번 약 1,150개씩 늘어나는 `HashMap$Node`·`[J`·`String`·`[B`이며, 하니스가 보관하는 이전 히스토그램 맵 자체다. 이것을 빼면 JOL 값과 맞는다.

장소 1개당 구성(배열 없음, JOL 분해):

| 항목 | B/장소 |
|---|---|
| `PlaceEntry` | 80 |
| `PlaceView` | 32 |
| `Double` 위도·경도 박싱 2개 | 48 |
| 표시값 맵 키 `Long`(placeId 박싱, ≤127은 공유) | 24 |
| 문자열 2개(`String` 24×2 + `byte[]` 평균 36) | 120.7 |
| 표시값 맵(`Map.copyOf` → `MapN` 테이블, 항목당 참조 4칸) | 16.2 |
| 원소 배열 `PlaceEntry[]` | 4.2 |
| 동네 고정비(`TownPlaces` 48 + `MapN` 32 + 빈 CHM 2개 128) | 동네당 208 B |

정적 정렬 배열 1축은 `16 + 4n` B다. 5축이면 장소당 20 B에 동네당 약 320 B(배열 머리 5개, CHM 테이블, 노드 5개)가 더해진다. 현재 데이터에서는 23.4 B/장소다.

### 합성 확대 — **합성 데이터, 실데이터 아님**

실데이터 행을 32벌 복제했다. 동네·장소 id를 벌마다 새로 매기고, 문자열은 `new String(toCharArray())`로 복사해 공유를 끊었다. 필드 길이 분포는 실데이터와 같고, 동네 크기 분포도 실데이터 그대로(동네 2,112개 × 약 96)다.

| 합성 202,240 장소 | JOL graph-exclusive | 히스토그램 |
|---|---|---|
| 배열 없음 | 66,184,704 B (**63.12 MiB**, 327.3 B/장소) | 62.60 MiB |
| 같은 객체에 5축 추가 | 71,076,864 B (**67.78 MiB**, 351.4 B/장소) | +4.85 MiB (25.1 B/장소) |

규모에 따라 선형으로 늘어난다. 실데이터와 합성의 B/장소 차이는 0.5 B 이하다.

### 버전 수별 예상 (계산값, 실측 아님)

| 보관 상태 | 배열 없음 | 5축 전부 |
|---|---|---|
| 현재 데이터 1벌 (6,320) | 1.97 MiB (실측) | 2.11 MiB (실측) |
| 전 동네 × 8버전 (평시 상한) | 15.8 MiB | 16.9 MiB |
| 전 동네 × 10버전 (보수 상한) | 19.7 MiB | 21.1 MiB |
| 가중치 상한 200,000 장소 | 62.4 MiB | 67.0 MiB (합성 실측 63.1/67.8과 일치) |

- 버전 수 가정은 모든 동네의 모든 버전을 요청이 실제로 적재한다는 최악이다.
- Caffeine 항목 노드와 `TownCacheKey`는 재지 않았다. 항목 1개(동네×버전)당 수십~100 B대로 추정하며, 이 역시 계산값이다.

## 4. 메모리 판단에 대한 주의

- graph-exclusive는 **캐시가 놓으면 회수될 크기의 추정**이다. 진행 중인 요청이 옛 버전을 붙잡고 있으면 TTL이나 축출 뒤에도 그 요청이 끝날 때까지 남는다.
- G1 영역 크기는 1 MiB이고 1 MiB 영역의 절반인 512 KiB를 넘는 배열은 humongous로 할당된다. 동네 1곳이 약 13만 장소를 넘어야 해당되므로 현재 분포에서는 생기지 않는다.
- 이번 값은 **보관 그래프**다. 적재 중 행 원본(Object[]·BigDecimal·날짜 객체)과 중간 HashMap·ArrayList, 정렬 중의 복제 배열처럼 **작업 중에만 생기는 메모리**와 비캐시 기본 힙은 포함하지 않았다.
- 합성 32벌을 보관한 하니스 JVM의 full GC 뒤 전체 live는 86,221,744 B였다. 이 값은 하니스 JVM 전체이며 앱 기본 힙이 아니다.

## 5. 벤치 앱 전제조건과 힙 계측 도구 (확인만, 기동하지 않음)

- 이미지 `solply-bench-app:towncache-8aa914d2b85f`는 2026-09-20T19:23Z에 빌드됐다. `TownPlaces.java` 등 현재 워크트리 소스는 09-21 이후에 바뀌었으므로 **이미지가 현재 코드보다 오래됐다**. `build/libs` 부트 jar도 09-21 07:02 산출물이다. 앱으로 측정하려면 `./gradlew bootJar` 후 `docker compose -f docker/docker-compose.bench.yml up -d --build bench-app`이 필요하다.
- 앱이 기동하면 Flyway가 미적용 `V49__place_stats_main_tag_sort_indexes.sql`을 벤치 DB에 적용한다. 벤치 DB에 대한 쓰기이므로 사전 합의가 필요하다.
- `solply-bench-lb`는 `bench-app`이 없어 `host not found in upstream` 오류로 재시작을 반복하고 있다. 손대지 않았다.
- `load-test/data/users.csv`가 있다(09-21).
- 앱 이미지의 JRE에는 `java`, `jfr`만 있고 **`jcmd`가 없다**. 그래서 컨테이너 안에서 히스토그램이나 `GC.heap_info`를 볼 수 없다.
- 쓸 수 있는 도구:
  - Actuator `/actuator/prometheus`, `/actuator/metrics`(노출 목록: health, info, metrics, prometheus). `jvm_gc_live_data_size_bytes`(GC 뒤 old 영역 live), `jvm_memory_used_bytes{area="heap"}`, `jvm_gc_pause_seconds`를 HTTP로 뽑을 수 있다. heapdump 엔드포인트는 노출되어 있지 않다.
  - 기존 JFR 오버레이 `2026-08-28_gc-decomposition/bench/jfr.compose.yml` + `gc.jfc`(GCHeapSummary·G1HeapSummary·GC pause 이벤트)와 판독기 `AnalyzeGcJfr.java`. 이 판독기는 원 캠페인 주석상 "실측으로 돌려 본 적 없음"이다.
  - `BENCH_JAVA_TOOL_OPTIONS`로 `-Xlog:gc`를 붙여 GC 전후 힙 크기를 로그로 남길 수 있다.
  - `load-test/shared/sampler.sh`는 컨테이너 CPU·메모리와 MySQL 커넥션만 모은다. JVM 지표는 모으지 않는다.
  - JMX 원격 포트는 열려 있지 않다.

## 6. 권장하는 다음 최소 측정

1. 캐시 그래프 크기는 위 수치로 충분하다. 동네 캐시 자체의 반복 측정은 필요 없다.
2. 다음에 필요한 것은 **앱 안에서 비캐시 기본 힙과 적재 중 임시 메모리를 나눠 보는 것**이다. 절차:
   - 현재 코드로 이미지를 다시 빌드한다(V49 적용 합의 필요).
   - 기동 직후 캐시가 빈 상태에서 `jvm_gc_live_data_size_bytes`를 기준선으로 잡는다.
   - 전 동네 첫 페이지를 한 번씩 적재한다.
   - `-Xlog:gc` 또는 Actuator로 GC 뒤 live 증가분을 본다. 약 2 MiB/벌이 기대값이다.
   - 버전 증가를 몇 번 일으켜 여러 벌을 보관시킨 뒤 같은 지표를 본다.
   - 이 확인은 5단계의 겹침 측정 안에 묶으면 따로 창을 열 필요가 없다.
3. 용량 후보를 정하는 데 필요한 사실은 두 가지다. 가중치 200,000은 현재 필드 길이 기준으로 약 63~68 MiB이고, 65분 TTL에서는 전 동네 × 최대 9~10버전(+관리자 수정)이 보관된다.

## 산출물

- `load-test/campaigns/2026-09-24_town-retention-memory/bench/TownFootprint.java` — 하니스.
- `load-test/campaigns/2026-09-24_town-retention-memory/results/footprint-run2.txt` — 원본 출력(동네별 CSV 포함).
- 실행 명령:
  ```
  J21=~/Library/Java/JavaVirtualMachines/temurin-21.0.8/Contents/Home
  CP="build/classes/java/main:<scratch>/jol-core-0.17.jar:<scratch>/boot/BOOT-INF/lib/*"
  $J21/bin/javac -d <scratch>/cls -cp "$CP" .../bench/TownFootprint.java
  $J21/bin/java -Xms1280m -Xmx1280m -XX:+EnableDynamicAgentLoading -Djdk.attach.allowAttachSelf=true \
    -cp "<scratch>/cls:$CP" TownFootprint "jdbc:mysql://localhost:3310/solply_bench_db?..." solplyuser solplyuserpwd 32
  ```
  - `jol-core-0.17.jar`는 Maven Central에서 받았다(sha1 `4c98e9e6…5b85` 일치).
  - 의존 jar는 `build/libs` 부트 jar의 `BOOT-INF/lib`에서 풀었다.
  - 클래스는 `build/classes/java/main`(09-23 21:32, 소스보다 새것)을 썼다.
- 벤치 MySQL은 기동한 상태로 두었다.
