> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 검증 기록: 4구조 비교를 위한 HTTP 연결과 최소 계측

작성일: 2026-09-21. 작업 트리: `feat-place-list-town-cache`.
정본 지시: [비교 연결 작업 지시](../handoff/2026-09-21-comparison-integration.md).
요구 계약: 원본 캠페인의 `docs/bench-interface.md`(H1·H2·H3·H4).
선행 계약 검증: [동네 버전·캐시](2026-09-21-town-cache-contract.md).

**이 Task는 구현과 테스트만 한다.** 네 구조의 등가성 수집과 실제 성능 측정은 다음 로컬 측정
Task의 몫이고, 그 부하는 내가 실행하지 않았다.

## 1. 실행 환경과 명령

| 항목 | 값 |
|---|---|
| 빌드 명령 | `JAVA_HOME=<temurin-21.0.8> ./gradlew -Dorg.gradle.java.home=<같은 경로> clean build` |
| gradle exit code | **0** |
| Gradle | 8.14.2 |
| 컴파일·테스트 JDK | Eclipse Temurin 21.0.8+9-LTS |
| MySQL | Testcontainers `mysql:8.0` + Flyway |

**런처 JDK를 21로 지정한 이유.** 이 머신의 기본 `java`는 25라, Gradle이 빌드 스크립트를 다시
파싱해야 할 때 Groovy가 `Unsupported class file major version 69`로 죽는다(실측). 스크립트가
캐시돼 있는 동안은 드러나지 않다가 `build.gradle`을 건드리는 순간 터진다. 최종 빌드는 그 함정을
피하려고 런처를 명시했다. **`build.gradle`은 한 글자도 바꾸지 않았으므로** 측정 담당자가 기존
방식이 JDK 25의 캐시된 스크립트에 기대어 성공한다고 보장할 수는 없다. 측정용 빌드도 JDK 21을
명시하는 것이 재현 조건이다. 앱 의존성과 toolchain 설정을 새로 바꾼 것은 아니다.

## 2. 결과

| 지표 | 값 |
|---|---|
| 전체 테스트 | **752** |
| 실패 | 0 |
| 오류 | 0 |
| skip | 0 |

새 테스트 **45개**의 내역(합이 맞는지 확인한 값이다):

| 클래스 | 종류 | 새 테스트 |
|---|---|---|
| `DbDirectHttpArmIT` | MySQL+MVC | 7 |
| `PlaceListMetersTest` | 단위 | 7 |
| `BenchPrepResetServiceTest` | 단위 | 6 |
| `BenchPrepResetTownIT` | MySQL+MVC | 10 |
| `BenchPrepResetGlobalIT` | MySQL+MVC | 5 |
| `BenchDisabledByDefaultIT` | MySQL+MVC | 3 |
| `TownListReaderTest`(기존에 추가) | 단위 | 3 |
| `TownCacheConcurrencyIT`(기존에 추가) | MySQL | 4 |
| **합** | | **45** |

선행 Task의 그린은 707이었고 `707 + 45 = 752`로 맞는다. **그 707은 이 Task의 코드 변경 전
수치이므로 여기서 재사용하지 않는다** — 위 752가 현재 형상의 새 근거다.

원자료: [빌드 전체 로그](../../../verification/logs/2026-09-21-comparison-integration-build.log),
[클래스별 XML 집계](../../../verification/logs/2026-09-21-comparison-integration-tests.txt).

## 3. H3 — `DB_DIRECT`를 실제 요청 경로에 연결

**연결의 증거는 게이지가 아니라 정적 5축 목록 요청이 200과 본문을 내는 것이다.** 이 구성은
전에도 기동에 성공했고 `arm_info`도 보고했다 — 거절은 요청마다 일어났다. 거리순과 북마크 검색은
구성 분기에 닿기 전에 갈라지므로 그 둘로 확인하면 연결됐다고 잘못 읽는다.

| 요구 | 구현 | 테스트 |
|---|---|---|
| 정적 5축이 실제 HTTP로 200 | `PlaceListRequestOrchestrator#takesTownPath`가 더는 던지지 않는다 + `PlaceService#listPlacesFromDb` | `DbDirectHttpArmIT#정적_다섯_축이_전부_목록을_낸다` |
| 기존 검증·북마크·응답 조립 재사용 | 같은 `respond(...)`를 지난다 | `DbDirectHttpArmIT#표시_필드가_실려_나온다` |
| 필터 | 같은 `filterPrint`·태그 인자 | `DbDirectHttpArmIT#태그_필터가_걸린다` |
| 두 페이지 커서 왕복 | 같은 커서 코덱 | `DbDirectHttpArmIT#커서_두_페이지가_이어진다` |
| **같은 범위 표현** | 리더가 한 read view에서 번호 관측 | `DbDirectHttpArmIT#커서는_동네_범위_표현을_싣는다` |
| **번호가 오르면 만료** | `TownDbDirectReader#requireLiveCursor` | `DbDirectHttpArmIT#번호가_오른_뒤_온_커서는_만료다` |
| 거리순은 기존 경로 | `takesTownPath`가 거리순을 먼저 가른다 | `DbDirectHttpArmIT#거리순은_이_구성에서도_기존_경로로_답한다` |

**예산은 리더 호출 전부터 걸린다.** 이 구성은 `townPath`로 들어와 진입 시각에 시계를 걸고,
검증 조회·번호 관측·DB 실행이 전부 그 안이다. 캐시 적재가 없다는 것이 예산이 없다는 뜻이
아니다 — DB 실행과 커넥션 대기가 예산을 넘길 수 있다.

**표시값은 리더가 같은 read view에서 실어 온 것만 쓴다**(frozen displays). 전역 표시 홀더를
보지 않으므로 홀더가 비어 있어도 행이 빠지지 않고, 못 찾으면 조용히 빠지는 대신 드러난다
(`respond(..., dropMissingDisplays = false)`).

## 4. H1 — 계측

### 4.1 입구 — 어디에 무엇을 두었나

| meter | 세는 자리 | 단위 |
|---|---|---|
| `solply_place_list_arm_info` | `PlaceListMeters` 생성자 | 이 JVM의 구성. 값 1 고정, 정보는 라벨 |
| `solply_town_cache_lookups_total` | `TownLoadRegistry#acquire`의 **첫** 조회 | 요청이 필요로 한 (동네, 번호) |
| `solply_town_cache_loads_total` | `TownLoadRegistry#run` | **적재 1회**(합류한 대기자 수가 아니다) |
| `solply_town_cache_arrays_built_total` | 사전정렬: `TownSourceLoader#assemble` / 요청정렬: `TownListReader#pageBySortingNow` | 배열 한 벌 |
| `solply_town_cache_array_uses_total` | `TownListReader#page` / `#pageBySortingNow` | 사전정렬은 **동네 배열**, 요청정렬은 **요청** |
| `solply_town_cache_evictions_total` | `TownPlacesCache`의 removal listener | 용량으로 빠진 항목 |
| `solply_place_list_budget_exceeded_total` | `PlaceListRequestOrchestrator#settleExceptionally` | **실패로 확정한 요청** |
| `solply_place_list_prepare_seconds` | 동네: `TownLoadRegistry#run` / 전역: `SnapshotInstaller#rebuildAndInstall` | 준비 1회 |
| `solply_global_snapshot_builds_total` | `SnapshotInstaller#rebuildAndInstall` | **지은** 횟수 |
| `solply_global_snapshot_poll_total` | `SnapshotLoadCoordinator#pollRebuild` | 폴 **발화** |

노출 이름은 `BenchPrepResetTownIT#프로메테우스_본문에_요구한_이름이_전부_있다`가
`/actuator/prometheus` 200 본문에서 열 개를 글자 그대로 확인한다.

> **테스트에서 걸린 함정.** 스프링 부트는 테스트에서 지표 <b>내보내기</b>를 기본으로 꺼
> `SimpleMeterRegistry`만 남긴다. 그 상태로는 프로메테우스 이름 변환(점 → 밑줄, `_total`,
> `_seconds`)을 확인할 수 없고, 실제로 이 단언이 `SimpleMeterRegistry`로 먼저 깨졌다.
> `@AutoConfigureObservability`로 해결했다. 같은 이유로 `/actuator/prometheus`가 403이었는데,
> 원인은 노출 설정이 아니라 **엔드포인트가 등록되지 않아 actuator 체인이 매칭하지 못한 것**이다.

### 4.2 적중률의 분모 — 승자 재확인과 내부 조회

**분모는 "요청이 필요로 한 (동네, 번호) 수"다.** 캐시 조회는 코드상 두 자리에서 일어난다.

1. `acquire` 진입의 조회 — **센다.** 요청이 물은 바로 그 한 번이다.
2. 적재 등록에 이긴 쪽의 **승자 재확인** — **세지 않는다.** 첫 조회와 등록 사이에 다른 비행이
   끝나 게시했을 수 있어 한 번 더 보는 것인데, 이것을 세면 **미스 하나가 조회 둘**을 만들어
   적중률의 분모가 요청 수와 어긋난다(적중률이 실제보다 낮게 보인다).

그래서 `lookups{hit} + lookups{miss}` = 요청들이 필요로 한 (동네, 번호)의 총수이고,
`hit / (hit+miss)`가 그대로 적중률이다.
증거: `BenchPrepResetTownIT#미스_한_번은_조회_한_번으로_센다`.

**적재는 공유 1회로 센다.** 합류한 대기자는 `loads`를 올리지 않고 자기 미스로만 드러난다 —
미스 다섯이 적재 하나로 접히는 것이 공유가 실제로 일어났다는 증거다.

### 4.3 배열 — 세운 수와 쓴 수

```
그 구간의 축 s 재사용 횟수 = Δarray_uses{sort=s} / Δarrays_built{sort=s}
```

- **사전 정렬**: 적재 1회가 동네 하나에 다섯 축을 세운다 → 축마다 `built`가 적재 수와 같다.
  사용은 **동네 배열 단위**라 동네 셋을 읽은 응답은 3 오른다.
  쓰이지 않은 축은 `uses = 0`이고, **그 0이 "다섯을 세워 하나만 썼다"의 수치다.**
- **요청 정렬**: 합집합 하나를 한 축으로 세워 그대로 쓴다 → **요청당 생성 1, 사용 1**.
  동네 수만큼 세지 않는다(동네마다 정렬하지 않는다).

증거: `TownListReaderTest#요청_정렬은_동네가_둘이어도_생성_하나_사용_하나다`,
`#사전_정렬의_사용은_동네_배열_단위다`, `#쓰지_않은_축은_사용이_0이다`,
`BenchPrepResetTownIT#사전_정렬은_적재마다_다섯_축을_세운다`, `#쓴_축만_사용으로_오른다`.

**누계 비율의 해석.** 카운터는 누계이고 도구가 라운드 앞뒤로 차분한다. 그 비는 **통제된 구간의
평균**이지 개별 배열 수명별 분포가 아니다 — 같은 구간 안에서 어떤 배열은 한 번, 어떤 배열은
열 번 쓰였어도 같은 평균이 나온다. **앱은 리셋 기능을 두지 않았다**(리셋을 놓친 라운드와
리셋된 라운드가 섞이면 차분이 음수가 된다).

### 4.4 예산 카운터의 단위와 상한

**단위는 요청 하나다.** 세는 자리는 요청의 확정 CAS에 이긴 **한 곳**뿐이라

- 요청 하나가 두 번 세어지지 않는다,
- **이미 성공으로 확정된 요청의 늦은 시계는 올리지 않는다**(CAS에 진다),
- 커서 만료는 세지 않는다 — 예산을 넘긴 것이 아니라 계약대로 답한 것이다.

이유를 아는 곳은 안쪽(시계·적재 대기·재관측 한도)이라 `PlaceListBudgetException`이 그것을
실어 올린다. 라벨은 `timeout`·`load_failed`·`version_moved` 셋으로 닫혀 있고,
실행기 거절처럼 "준비할 자리를 확보하지 못한" 끊김은 `load_failed`로 센다.

증거: `TownCacheConcurrencyIT#예산_초과는_요청_하나에_한_번만_센다`,
`#성공한_요청의_늦은_시계는_세지_않는다`, `#커서_만료는_예산_카운터를_올리지_않는다`,
`#DB_실패로_끊긴_요청은_load_failed로_센다`.

**`DB_DIRECT`에서도 0으로 강제하지 않는다.** 세 후보(`TOWN_PRESORTED`,
`TOWN_REQUEST_SORT`, `DB_DIRECT`)는 같은 `townPath`의 총 요청 예산을 적용한다.
기존 `GLOBAL_SNAPSHOT`은 별도 전역 경로와 기존 대기 예산을 유지하므로 네 구성의
예산 계약이 같다는 뜻은 아니다.

### 4.5 전역 스냅샷 비용은 모든 구성에서 실제 발생한 대로

거리순이 이번 범위 밖이라 **legacy 전역 스냅샷은 모든 구성에서 상주·기동·폴한다.**
`SnapshotScheduler.buildOnStartup`과 `SnapshotLoadCoordinator.pollRebuild`가 그것이다.
그 값을 "해당 없으니 0"으로 고정하면 앱이 실제로 지는 비용이 원자료에서 사라지고, 동네 구성이
실제보다 싸 보인다.

증거: `BenchPrepResetTownIT#동네_구성에서도_전역_meter가_살아_있다`
(동네 구성인데 `global_snapshot_builds`가 양수다 — 기동이 실제로 한 벌 지었다).

**읽을 때 두 줄로 나눈다.** ① 그 구성이 정적 5축을 답하려고 새로 지는 몫(동네 적재·배열, 또는
DB 실행) ② 거리 경로 때문에 남는 전역 스냅샷의 빌드·폴 비용. 둘을 합쳐 "이만큼 줄었다"고
말하지 않는다.

**수정한 누락 하나 — 같은 번호의 준비가 0으로 빠지던 문제.** `globalBuilt`가 설치 성공 뒤에만
있어서, H2의 `rebuild_global`이 전량 읽기와 정렬을 실제로 다 하고도 단조 가드가 마지막 대입을
건너뛰면 `builds`와 prepare 타이머가 **둘 다 0**이었다. 설치 여부와 독립적으로 세도록 고쳤다.
음성 대조를 실제로 돌렸다 — meter 호출을 설치 성공 뒤로 되돌리면
`BenchPrepResetGlobalIT#같은_번호로_다시_태워도_전역_준비가_계측된다` 하나만 실패한다.

**`poll{outcome}`의 뜻을 정확히.** `rebuilt`는 폴이 **리빌드를 띄우기로 판정한 발화**다.
그 판정이 새 빌드 하나를 뜻하지는 않는다 — 이미 같은 시점을 담은 비행이 떠 있으면 거기 붙는다.
**실제로 지은 횟수는 `global_snapshot_builds_total`**이고, **설치까지 간 횟수는 어느 meter도
세지 않는다**(설치 거절은 정상 경로라 지표로 삼을 값이 아니다). 셋을 같은 뜻으로 읽지 말 것.

## 5. H2 — `/bench/prep/reset`

| 구성 | action | 하는 일 | 측정창 |
|---|---|---|---|
| `GLOBAL_SNAPSHOT` | `rebuild_global` | 호출 **안에서** 전량을 다시 짓는다 | 호출 직전부터 응답까지 — `prepareMillis`가 정본 |
| `TOWN_*` | `invalidate_towns` | 해당 항목만 비운다 | 다음 목록 요청의 시작부터 응답까지 |
| `DB_DIRECT` | `noop` | 준비할 것이 없다(200) | 요청 1회가 곧 그 값 |

지키는 것 넷과 그 증거:

| 계약 | 테스트 |
|---|---|
| 동네 번호를 올리지 않는다(`versionsBumped`는 언제나 0) | `BenchPrepResetTownIT#번호와_원본을_건드리지_않는다`, `BenchPrepResetGlobalIT#동네_번호를_올리지_않는다` |
| DB를 고치지 않는다 | `BenchPrepResetTownIT#번호와_원본을_건드리지_않는다`(place_stats 행 수 불변), `BenchPrepResetGlobalIT`(전역 metadata 불변) |
| 비우기만 하고 적재를 미리 태우지 않는다 | `BenchPrepResetTownIT#비우기만_하고_다음_요청이_적재한다` |
| `dryRun`은 무변경 | `BenchPrepResetTownIT#dryRun은_비우지_않는다`, `BenchPrepResetGlobalIT#dryRun은_아무것도_짓지_않는다` |
| `preparedDuringCall`·`prepareMillis` 필수 | `BenchPrepResetGlobalIT#준비가_호출_안에서_끝났음을_응답이_말한다` |
| 명시 무효화는 **축출이 아니다** | `BenchPrepResetTownIT#초기화는_축출로_세지_않는다` |

## 6. 격리 선택과 그 경계

**고른 것: 프로파일 + 기본 false 프로퍼티 + 조건부 보안, 세 겹 전부.**

1. `@Profile("bench")` — 컨트롤러·서비스·보안 체인 모두.
2. `@ConditionalOnProperty("solply.bench.enabled", havingValue="true")` — 기본 **false**, 운영
   설정에서의 실제 활성 여부는 이번에 검사하지 않았다.
3. `SecurityConfig#benchChain` — 위 둘이 맞을 때만 서고, 그때만 `/bench/**`가 permitAll이다.
   꺼져 있으면 `/bench/**`는 `appChain`의 `anyRequest().authenticated()`에 걸린다.

증거: `BenchDisabledByDefaultIT`(빈 없음 · 보안 체인 없음 · 무인증 호출이 200이 아님).

**jar 경계 — 알고 고른 맞교환.** `@Profile`·`@ConditionalOnProperty`는 빈 등록을 막을 뿐
**바이트코드는 운영 jar에 그대로 들어간다.** 바이트코드까지 없애려면 별도 bench source set이
필요한데, 빌드 경로가 둘로 갈려 측정 담당자가 "같은 이미지"를 굽기 어려워진다. 이번 범위가
비교 측정용 이미지 하나라 **세 겹 런타임 차단**을 골랐고, 실제 방어선은 **운영 설정에
`solply.bench.enabled` 키를 두지 않는 것**이다.

**계측·통로가 들어간 이미지와 아닌 이미지를 태그로 구분하는 것은 측정 담당자의 몫이다.**
이 Task는 그 구분을 코드로 강제하지 않는다.

## 7. GLOBAL 기준선은 그대로다

`GLOBAL_SNAPSHOT`은 기존 v7 전역 metadata 계약 그대로이고 새 동네 계약으로 개조하지 않았다.
전역 경로를 검증하는 기존 IT들은 `list-source = GLOBAL_SNAPSHOT`으로 명시 고정돼 있다
(`PlaceListSnapshotCatchUpIT`, `PlaceListRequestOrchestratorTest`).

거리순은 네 구성 전부에서 기존 전역 경로다 — 이번에 손대지 않았고 최적화·벤치도 하지 않았다.

## 8. H4는 만들지 않았다

`GET /bench/list-state`는 선택이고, 게이트는 `arm_info`로 열린다. 불필요한 범위 확장을 피해
만들지 않았다. 동네별·번호별 분해가 필요해지면 그때 추가한다.

## 9. 직접 검증하지 않은 경계

- **네 구조의 결과 등가성**(0단계)과 **실제 성능 수집**. 다음 로컬 측정 Task의 몫이고,
  이 Task에서 부하를 돌리지 않았다.
- **도구와의 실제 연동.** meter 이름과 응답 필드는 계약대로 맞췄지만,
  `bench/bench-metrics.sh`·`prep-reset.sh`를 실제로 실행해 보지는 않았다.
- **`DB_DIRECT`의 `budget_exceeded` 실제 증가.** 계측 자리와 시계는 세 후보의 `townPath`
  공통이지만, DB 실행이 예산을 넘겨 끊기는 상황을 이 구성에서 재현한 테스트는 없다.
- **운영 이미지에서의 프로파일 오염.** 세 겹이 막는다는 것은 테스트로 확인했으나, 실제 배포
  환경변수 조합을 검사하지는 않았다.
- **계측 자체의 비용.** 보정하지 않았고 재지도 않았다. 계측 이미지로 잰 응답시간을 비계측
  이미지의 값과 같은 표에 놓지 말 것.
- **`cache max-places=200000` 등 기본값.** 여전히 측정 근거 없는 잠정치다.

## 10. 하지 않은 것

커밋·푸시·PR·배포, 운영/dev 호출, 다른 컨테이너 변경, 거리순 확장, 로컬 bench 부하 실행.
원본 checkout의 비교 도구는 손대지 않았다.
