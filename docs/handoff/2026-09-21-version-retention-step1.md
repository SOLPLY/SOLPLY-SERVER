# 단계 1 완료 보고 — 동네·버전별 보관과 기존 버전으로 다음 페이지 조회

기준서: `docs/blog/2026-09-21-place-list-design-evidence.md`.
계획서: `docs/superpowers/plans/2026-09-21-place-list-version-retention.md` 단계 1.
진행·근거 정본: `docs/blog/2026-09-21-place-list-design-evidence.md` 3·4·5절.
작성: 2026-09-21. **테스트를 실행하지 않은 상태의 보고다.**

## 무엇을 바꿨나

### 캐시 키를 `(동네, 버전)`으로 바꿨다

`TownPlacesCache`의 키가 동네 ID 하나에서 `TownCacheKey(townId, version)`가 됐다. 같은 동네의 여러 버전이 나란히 남고, 새 버전이 올라와도 이전 버전이 밀려나지 않는다. 이것이 스크롤 유지의 근거다 — 요청이 이어 볼 수 있는 이유가 "이미 참조를 잡아서"가 아니라 "그 버전이 아직 저장소에 있어서"가 됐다.

키에 버전이 들어가면서 게시의 단조 가드를 걷어냈다. 오래 걸린 옛 적재가 새 버전의 자리에 닿을 경로 자체가 없어 비교할 이유가 없다. 그래서 `publish`의 반환값도 없앴다.

동네 단위로 동작해야 하는 두 메서드는 번호를 가리지 않도록 고쳤다. `invalidate(townIds)`는 그 동네의 모든 버전을 비우고, `cachedTownIds()`는 버전이 여럿이어도 동네를 한 번만 돌려준다.

### 첫 페이지와 다음 페이지를 갈랐다

`TownPlaceListService.gather`가 커서 유무로 두 절차를 나눈다.

- **첫 페이지·새로고침:** 예전과 같다. 관련 동네의 버전을 한 read view에서 관측하고, 없으면 공유 적재에 합류해 남은 예산만큼 기다린다. 적재가 더 새 버전을 봤으면 남은 예산 안에서 다시 관측한다(`firstPageReobserveLimit`).
- **다음 페이지:** DB를 보지 않는다. 커서가 실은 `(동네, 버전)` 조합을 캐시에서 그대로 찾고, 하나라도 없으면 `EXPIRED_PLACE_CURSOR`다.

걷어낸 것은 다음 페이지에서 DB 최신 번호와 커서를 견주던 비교다. 그 비교가 있으면 커서가 선 버전의 데이터가 아직 캐시에 있는데도 스크롤이 끊긴다.

만료로 끊는 조건은 셋뿐이다. (1) 커서가 지목한 버전이 캐시에 없다, (2) 커서가 실은 동네 집합이 이번 요청의 leaf 집합과 다르다, (3) 커서의 범위 표현이 동네 좌표계의 것이 아니다(거리순·전역 커서가 동네 경로로 온 경우). 정렬·필터 지문 검증은 기존 자리인 `PlaceService`의 커서 대조에 그대로 있다.

사라진 버전을 DB로 복구하지 않는다. 원본 테이블에는 지금 상태만 있어 그 버전의 장소 집합과 정렬 값을 다시 만들 방법이 없고, 최신 버전으로 대신 답하면 커서 좌표가 다른 좌표계에서 해석돼 항목이 빠지거나 겹친다.

### 다음 단계로 넘기는 계약

`Gathered`는 확보한 동네 객체의 참조를 들고 있다. 캐시에서 그 항목이 뒤이어 빠져도 이 요청은 자기가 잡은 불변 객체로 응답 조립을 끝낸다.

## 변경 파일

### main

| 파일 | 변경 |
|---|---|
| `domain/place/cache/town/TownPlacesCache.java` | 키를 `TownCacheKey`로, 단조 가드 제거, `get(TownCacheKey)` 추가, `invalidate`·`cachedTownIds` 동네 단위 유지 |
| `domain/place/cache/town/TownPlaceListService.java` | 첫 페이지/다음 페이지 분리, 캐시 전용 이어보기, 버전 대조 제거, `TownPlacesCache`·`PlaceListMeters` 주입 |
| `domain/place/cache/town/TownLoadRegistry.java` | `publish` 반환값 제거에 맞춘 호출부·주석 수정 |
| `domain/place/cache/town/TownCacheKey.java` | 구버전 보관 근거를 주석에 추가 |

### test

| 파일 | 변경 |
|---|---|
| `cache/town/TownPlaceListServiceCursorTest.java` | **신규** — 다음 페이지 계약 5건(이어보기, 참조 수명, 버전 부재, 동네 집합 불일치, 다른 좌표계) |
| `cache/town/TownPlacesCacheTest.java` | 이전 정책 단언 교체 — 공존/키 분리/동네 단위 무효화 |
| `cache/town/TownCacheListFlowIT.java` | 이전 정책 단언 교체 — 번호가 올라도 이어감, 사라지면 만료, 두 버전 공존 |
| `cache/town/TownCacheConcurrencyIT.java` | 이전 정책 단언 교체 — 커서 요청이 적재를 부르지 않음, 만료 트리거를 캐시 부재로 |
| `cache/town/TownLoadRegistryTest.java` | 옛 적재가 자기 자리에 남는다는 단언으로 수정 |

### 문서

`docs/blog/2026-09-21-place-list-design-evidence.md`(진행 현황·3·4·5절), `docs/superpowers/plans/2026-09-21-place-list-version-retention.md`(단계 1 체크·상태), `TODO.md`, 이 문서.

## 충족한 완료 조건

계획서 단계 1의 완료 조건 — "v1 첫 페이지 → DB v2 변경 → v1 다음 페이지가 같은 목록을 이어 가고, v1 제거 후에는 새로고침 오류가 나는 코드 경로가 완성되어 있다".

두 경로 모두 코드로 완성했고 대응 테스트 소스를 작성했다.

- 이어보기: `TownCacheListFlowIT#번호가_올라도_옛_커서는_같은_목록을_이어_간다`(새 버전에서만 보이는 장소를 심어 옛 집합임을 확인), `#새_번호를_적재해도_옛_번호의_스크롤이_이어진다`, `TownCacheConcurrencyIT#번호가_올라도_남아_있는_번호로_이어_간다`, `TownPlaceListServiceCursorTest#커서의_번호가_남아_있으면_이어_간다`.
- 만료: `TownCacheListFlowIT#커서가_선_번호가_사라지면_만료다`, `TownCacheConcurrencyIT#커서_요청은_적재를_부르지_않고_없으면_만료다`, `TownPlaceListServiceCursorTest#커서의_번호가_하나라도_없으면_만료다`.

## 실행한 검증 / 실행하지 않은 것

**실행한 것은 컴파일뿐이다.** `./gradlew compileJava`와 `./gradlew compileTestJava`가 통과했다.

**실행하지 않았다:** 위 테스트를 포함한 모든 단위·통합 테스트, 전체 빌드, 회귀, 성능 측정. 계획서가 단계 4로 모아 두었고 이번 작업 지시도 실행을 금지했다. 따라서 위 테스트가 통과한다는 주장은 이 문서에 없다 — **작성했다는 것과 통과한다는 것은 다르다.**

## 남은 문제

1. **버전별 보관 기간과 보관량 제한이 없다.** 용량 상한은 장소 수 기준 그대로인데 이제 같은 동네가 여러 버전으로 상한을 차지한다. 이전 버전이 언제까지 남는지는 용량 압력에만 달려 있고, 보관 기간은 정의되지 않았다. 단계 3의 범위다.
2. **다음 페이지가 성공할 확률을 아직 말할 수 없다.** 버전 변경 빈도·스크롤 지속 시간·버전별 보관량을 재지 않았다.
3. **다음 페이지의 DB 접근 한 건 감소를 측정하지 않았다.** 버전 조회 쿼리가 사라진 것은 코드로 확인되지만 절감량은 수치가 없다.
4. **`TownDbDirectReader`(비교용 DB 직결 경로)는 손대지 않았다.** 그 경로는 캐시가 없어 버전이 바뀌면 만료한다. 두 경로의 만료 조건이 이제 다르므로 비교 측정에서 그 차이를 계약 차이로 기록해야 한다.
5. **단계 2의 전제와 겹치는 부분.** 지금은 적재 시점에 구성에 따라 정적 5축을 만들거나 객체만 만든다. 요청한 축만 최초 생성해 재사용하는 변경은 단계 2이며 이번에 손대지 않았다.

## 작업 중 받은 추가 지시 반영

메인이 전달한 사용자 지시(측정 유용성 사전 판정)를 문서 두 곳에 넣었다. 구현 범위는 바뀌지 않았다.

- `docs/blog/2026-09-21-place-list-design-evidence.md` 10절에 "측정을 실행하기 전에 통과해야 하는 판정" 항목 추가 — 주장·불확실성 / 결과별 달라지는 결정 / 측정 대상·비교 기준·단위·형상과 산포 대비 구별 가능성 / 최소 실험과 종료 조건.
- `docs/superpowers/plans/2026-09-21-place-list-version-retention.md` 단계 5의 표 앞에 같은 판정을 넣고, 표가 실행 확정 목록이 아니라 판정을 통과할 때만 수행할 후보임을 명시.

두 곳 모두 elapsed를 CPU 근거로, 할당량을 retained로 쓰지 않는다는 것과 반복 산포 안의 차이는 미확정으로 끝낸다는 것을 함께 적었다. 측정 실행은 여전히 이번 범위 밖이다.
