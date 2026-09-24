# 단계 3 보고 — 서울 전체 비중 중첩 1회, DbDirectHttpArmIT 진단

캠페인: `load-test/campaigns/2026-09-24_town-retention-memory/README.md` "서울 전체 조회 비중 추가 확인".
작성 2026-09-24, Claude Opus 5.5. 커밋·푸시 없음. 앱 재빌드·재기동·평상시 반복 없음.

## 1. 서울 전체 비중 중첩 (`results/seoul-20260924-190055/`)

- 대상은 단계 2와 **같은 앱 프로세스**다(18:53 기동, 적재 누계 900, 번호 14~17에서 시작).
- 시나리오 `scenarios/list-120-seoul.yml`: city 60 · scroll 20 · town 10 · tag 10. 기대 120 req/s 중 서울 전체가 100 req/s(83.3%)다. **사용자 가정이며 운영 실측이 아니다.**
- warm 20초 + 측정 60초. 측정 창에서 15초 간격으로 번호를 4회 올렸다. 러너는 `bench/run-seoul.sh`, sampler 라벨은 `seoul-overlap`이다.

| 항목 | 값 |
|---|---|
| 요청 | 9,639 / 80초 = 약 120.5/s, **전부 200**, vusers.failed 0 |
| 지연 (warm 포함, 로컬 참고) | p50 7 / p95 12.1 / p99 40.9 / max 287 ms |
| 적재 | +235(시작 = 완료), 미스 +418 → 합류 183, **실패·예산 초과(timeout·load_failed·version_moved)·축출 0** |
| 번호 | 14~17 → 18~21 |
| 앱 CPU (3초 샘플, 200% 상한) | 평균 47.5%, 최대 64.5%. MySQL 평균 20.7%, 커넥션 11 |
| 자연 GC (부하 중) | Young 10회, 합계 0.239 s. Mixed·Full 없음 |
| live 스레드 | 38 → 80 (적재 풀이 늘어남, 쉬는 스레드는 60초 뒤 정리) |

**보관 세대 (부하 뒤 진단 GC, `results/diag/seoul-20260924-190055.*`)**

| | 단계 2 끝(C) | 이번 뒤 |
|---|---|---|
| `TownPlaces` | 900 | **1,135** |
| `PlaceEntry` (전역 6,320 포함) | 93,420 | **116,920** |
| GC 뒤 live heap | 113.0 MiB | **120.8 MiB** |
| 컨테이너 memory.current | 1.615 GiB | 1.613 GiB (pre-touch 1280m라 캐시 변화가 드러나지 않음) |

- `TownPlaces` 1,135는 모두 누계 적재 1,135회와 같다. 축출과 만료는 0이다. 최초 항목이 들어간 18:53 + 65분이 아직 지나지 않았다.
- 서울 요청이 많은 조건에서 **나빠진 지표를 관측하지 못했다**는 뜻이다.
  - 요청 섞기와 보관 세대 수가 동시에 달라졌으므로, 단계 2(A 9,519, B 9,605 전부 200, heap 86.8/104.8/113.0 MiB) 대비 개선으로 읽지 않는다.

**옛 커서의 id·순서 비교**
- 방법: 번호를 올리기 직전마다 서울 전체 커서를 하나 잡았다. 같은 순간(같은 번호) 그 커서의 다음 페이지 id 목록을 기대값으로 남겼다.
- 부하가 끝난 뒤(번호 4회 증가 이후) 같은 커서를 다시 보냈다.
- 결과: **4/4 모두 200, 20건, id와 순서가 기대값과 동일**(`cursors/{1..4}.expected` 대 `.replay`).

## 2. DbDirectHttpArmIT.태그_필터가_걸린다 진단

- 증상(단계 2 1차 실행): 기대 `[340]`인데 실제 `[]`.
- 원인: **테스트 픽스처가 오래됐다.**
  - DB 직행 경로의 메인 태그 조건이 `PlaceListDbQueryRepository.appendTagFilters`(572~575행)의 `AND ps.main_tag_id = :mainTagId`다(V49 설계). V49 주석에 "선택 시 (town_id, main_tag_id)를 동등 조건으로" 좁힌다고 되어 있다. 같은 설계를 `PlaceListDbQueryRepositoryIT.mainTagUsesEqualityAndKeepsSubTagMask`가 이미 단언한다.
  - 그런데 `DbDirectHttpArmIT.createPlace`는 `place_stats`에 `tag_bitmask`만 심고 `main_tag_id`는 NULL로 두었다. 그래서 태그 요청에 걸리는 행이 없었다.
- 수정(테스트만): `createPlace`가 비트마스크에 `SEED_MAIN_TAG` 비트가 있으면 `main_tag_id`도 같은 값으로 심는다. 필드 주석도 함께 고쳤다. 제품 코드와 정책은 그대로다.
- 재실행: `./gradlew test --tests '...DbDirectHttpArmIT'` → **7개 중 실패 0**(`results/tests/3-dbdirect-rerun.txt`).
- 참고: 캐시 경로(`TownListReader`)는 여전히 비트마스크로 메인 태그를 거른다. 두 경로가 같은 결과를 내려면 V49가 검증하는 "장소당 MAIN 하나" 전제가 필요하다. 이번 범위에서 바꾸지 않았다.

## 3. 주석 정리 확인

단계 2에서 요청받은 주석 정리가 반영된 상태다.
- `TownPlaces`: "실행 상한은 다음 단계", "예산이 정렬을 묶는다" 문장 제거
- `TownLoadRegistry`: 가상 스레드·드라이버 설명 제거

`grep '다음 단계|가상'` 결과 해당 없음. 벤치 이미지의 jar는 이 주석 정리 전 빌드지만 동작은 같다.

## 4. 남은 상태

- 벤치 스택은 떠 있다. 번호는 18~21이고, 캐시에 1,135항목이 있으며 약 19:58부터 TTL로 빠지기 시작한다.
- 새 산출물(무시 경로 `load-test/`):
  - `scenarios/list-120-seoul.yml`, `bench/run-seoul.sh`
  - `results/seoul-20260924-190055/`, `results/diag/seoul-*`, `results/metrics/seoul-overlap.*`, `results/tests/3-dbdirect-rerun.txt`
