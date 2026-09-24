> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 측정 종료 뒤 Opus5 최종 정리 지시

측정 Task는 21:50:17 UTC succeeded로 종료했고 메인이 자원 종료와 슬롯 반환을 확인했다. 이 문서를 참조한 최종화 Dispatch는 앱 파일/Gradle 검증을 실행할 수 있다. 측정 캠페인 원자료와 기존 이미지에는 변경하지 않는다.

## 확인된 주석 오류

- `PlaceListProperties.ListSource.DB_DIRECT`는 통합 단계에서 실제 요청 경로에 연결됐다. 아직 연결하지 않아 선택시 거부된다는 주석은 과거 상태다. 현재 RR metadata→page→hydrate 및 공통 요청 예산 경계로 정정한다.
- V48 마지막 주석: 마이그레이션 즉시 모든 스크롤이 decode에서 거부된다는 설명은 틀리다. v7/v8 decode를 둘 다 유지하고, 새 town 경로의 G/T scope 비교가 기존 정적 커서를 만료시킨다. GLOBAL/거리 경로 v7 호환은 유지한다. 번호를 이관하지 않고 없는 row를0으로 읽는 실제 동작을 설명한다.
- V48의 '번호가 오르면 반드시 무언가 바뀌었다' 단정은 값이 같아도 성공한 전체 배치에서 bump한다는 합의와 맞지 않는다. 번호는 새 탐색 세대/무효화 경계다. 표시값은 성공한 배치 후 해당 동네를 성공적으로 적재한 때 반영된다.
- `TownVersionService` 클래스 주석은 `(town, tagMask)` 지문만으로 모든 정렬 키 변경까지 덮는다고 말하지 않는다. 관리자 대상/태그 변경은 지문, 통계 정렬 키 변경은 별도 호출, 전체 배치는 처리 대상 모두 bump라는 실제 경계를 설명한다.

## 최종 선택

메인 결정(21:29 UTC): 기본 listSource를 TOWN_REQUEST_SORT로 바꾼다. 두 town 방식의 CPU/지연/할당 이득과 준비 비용 회수를 구별하지 못했고, 인기 한 축만 조회해도 presort는 미사용4축을 준비했다. 객체만 적재하고 필요한 요청 합집합을 정렬하는 단순성을 선택한다. 성능 우월/비열등성 증명이 아니다. GLOBAL은 새 계약의 채택 후보가 아닌 비용 기준선이고 DB_DIRECT는 이 창에서 앱 CPU 절감 확인 없이 DB CPU와 표본 추정 할당이 컸다. 비교 경로를 제거하거나 거리 경로를 바꾸지 않는다. 운영 예산200000은 근거 없는 확정값으로 승격하지 않는다.

## 실행시 검증 경계

최종 기본값 변경이 있으면 그에 맞는 테스트 및 JDK21 전체 build를 Opus5가 수행하고 로그/XML을 남긴다. 측정 이미지와 최종 소스/JAR의 형상을 구분한다. 기존 비교 원자료를 새 빌드의 실측으로 바꾸지 않는다. 원본 checkout 앱/ignored 설정 보존, commit/push/PR/deploy 금지.


## 로컬 벤치의 이미 적용된 V48 경계

측정 DB에는 측정 이미지의 V48이 이미 적용돼 있다. 이번 SQL 주석 정정은 DDL 변경 없이 수행하되, Flyway checksum에 영향을 줄 수 있다는 점을 최종 형상 기록에 명시한다. 측정 DB/volume/이전 이미지의 재현 상태를 보존하기 위해 이 Task에서 migration history repair나 DB 변경을 하지 않는다. 새 clean build의 Testcontainers 검증과 이미 측정한 DB의 적용 형상을 혼동하지 않는다. 가능하면 같은 Flyway checksum 계산기로 정정 전후 checksum을 확인해 보고하고, 실제 값이 같거나 다르다는 증거 없이 같다고 가정하지 않는다.


## clean build 전에 측정 아티팩트 보존

현재 `build/libs/solply-server-0.0.1-SNAPSHOT.jar`는 측정에 사용한 JAR일 수 있다. SHA256이 `8aa914d2b85f8592a2189bba77b178d04c0c7a2156c8d54e8fd10dd0b4240e23`인지 확인하고, 맞으면 clean이 지우기 전에 ignored `docs/verification/logs/measured-8aa914d2b85f.jar`에 로컬 보존한다. 이 경로는 메인이 `git check-ignore`로 ignored인 것을 확인했다. JAR 내부 설정이나 비밀값을 출력/공유하지 않는다. 측정 이미지 자체도 덮지 않는다. 이번 Task가 수정하는 세 앱 파일의 변경 전 사본 또는 Task만의 전후 patch를 같은 ignored logs 아래 보존해 이전 측정 소스와 최종 코드의 차이를 추적할 수 있게 한다. 새 JAR은 새 SHA로 식별한다.


## 도구 검증 로그 보완(원본 도구는 수정하지 않음)

측정 worker의 최신 26/39/45 selftest 실행 출력이 파일에 보존되지 않았다면, 원본 캠페인의 읽기 전용 검증을 실행하고 이 worktree의 `docs/verification/logs/2026-09-21-tools-final.log`에 명령·실제 출력·exit를 남긴다. 대상은 bash -n(bench/*.sh), node --check 대상 도구, `python3 bench/jfr-window.py --selftest`, `node bench/analyze-round.mjs selftest`, `node bench/equivalence.mjs selftest`다. 정확한 실제 selftest CLI는 해당 파일 끝의 argv 분기를 확인한다. HTTP/DB/부하 실행은 하지 않는다. 이미 측정 worker가 최종 로그를 남겼다면 그 경로와 결과를 읽고 중복 실행하지 않아도 된다. 원본 캠페인 파일을 편집하지 않는다.
