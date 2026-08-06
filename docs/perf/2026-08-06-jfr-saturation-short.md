# 포화 구간 JFR 짧은 재현 (2026-08-06)

- 형상: `93f0851`, 제품 코드·DB·요청 믹스 무변경
- 자원: 앱 2 vCPU / 2 GiB, MySQL 2 vCPU / 3 GiB
- 최종 라운드: `jfr-short-3` — 60/s 30초 → 120/s 30초 워밍업, 300/s·360/s 각 20초
- 원자료: [`load-test/campaigns/2026-08-06_jfr-saturation-short`](../../load-test/campaigns/2026-08-06_jfr-saturation-short/)

## 결론

**태그 조회의 특정 애플리케이션 메서드가 CPU를 독점한다는 증거는 나오지 않았다.** 실행 샘플의
가장 큰 일관된 묶음은 Hibernate 경로(300/s **20.8%**, 360/s **22.3%**)였고 MySQL Connector/J
프레임도 각각 **13.0%·12.7%**의 스택에 포함됐다. 두 값은 inclusive라 서로 겹칠 수 있어 더하면
안 된다. 개별 leaf 메서드는 어느 것도 **6.1%를 넘지 않았다.**

오히려 두 구간에서 반복된 신호는 **실행 JAR의 class/resource URL 처리와 그 캐시 monitor 경합**이다.
`URL` 주소/hash 처리, `ParseUtil.encodePath`, Spring Boot jar `Handler` 계열 leaf를 합치면 Java CPU
표본의 약 **13.2%(300)·9.8%(360)**다. 20ms 이상 `JavaMonitorEnter`는 300에서 **155회 / 누적
11.5 thread-seconds**, 360에서 **261회 / 18.4 thread-seconds**였고, 그중 Boot jar cache/internal
monitor가 각각 약 **66%·54%**를 차지했다. 다만 스택이 5프레임에서 잘려 **어떤 상위 요청 코드가
resource lookup을 촉발했는지는 이 녹화로 귀속할 수 없다.** 개선 대상으로 확정하기보다 후속
프로파일의 구체적 표적으로 보는 것이 맞다.

태그 경로만 유별나게 느린 모양도 없었다. 300/s의 첫 페이지 p50은 popular+tag **50.9ms**,
latest+tag **50.9ms**, popular city 무태그 **54.1ms**, popular town 무태그 **51.9ms**였다. 두 번째
페이지도 popular city 태그/무태그가 모두 **16.9ms**였다. 360/s에서는 모든 endpoint가 초 단위로
함께 밀렸다. 이것은 endpoint별 CPU 귀속은 아니지만, 적어도 **태그 요청만 단독으로 무너진다는
가설과는 맞지 않는다.**

## 최종 라운드 결과

| 도착률 | arrivals | HTTP 요청/응답 | 실패 | 전체 p50 | p95(참고) | Java CPU sample | JVM CPU |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 300/s | 6,000 | 9,234 / 9,234 | 0 | 45.2ms | 301.9ms | 782 | 약 1.88 cores 평균 |
| 360/s | 7,200 | 11,083 / 11,083 | 0 | 2,671ms | 4,676ms | 819 | 약 2.09 cores 평균* |

`JFR CPULoad × Docker VM 11 CPU`로 환산했다. `2.09`처럼 cgroup 쿼터 2.0을 조금 넘는 값은 1초
표본 경계/정규화 오차이며, 자원 판정의 정본은 직전 캠페인의 cgroup 값이다. 여기서는 두 구간이
CPU 천장에 붙어 있는지를 확인하는 보조 지표로만 쓴다.

| 내부 신호 (20초 실제 부하 창) | 300/s | 360/s | 해석 |
|---|---:|---:|---|
| 추정 할당량 | 10,254 MiB (513 MiB/s) | 9,129 MiB (456 MiB/s) | 매우 크지만 객체/호출부 샘플은 오버헤드 때문에 끔 |
| GC | 44회 / pause 합 316ms / max 74ms | 38회 / 440ms / max 79ms | 증가 요인이지만 초 단위 p50 붕괴의 주원인은 아님 |
| monitor enter ≥20ms | 155회 / 11.5 thread-s | 261회 / 18.4 thread-s | 360에서 경합 신호 증가 |

할당 누계는 각 JVM thread의 `ThreadAllocationStatistics` 차분 합이다. 360에서 값이 줄어든 것은
대기열 때문에 같은 20초 창에서 실제로 일을 마친 양이 달라졌기 때문일 수 있다. 객체별 allocation
hotspot은 측정하지 않았으므로 이 수치만으로 최적화 대상을 정하면 안 된다.

## CPU hotspot 상세

`ExecutionSample`만 CPU 표본으로 집계했다. `NativeMethodSample`에는 `EPoll.wait`처럼 잠든 I/O
스레드가 들어가므로 CPU 비율에서 제외했다.

| inclusive stack group | 300/s | 360/s |
|---|---:|---:|
| Hibernate | 20.8% | 22.3% |
| MySQL Connector/J | 13.0% | 12.7% |
| Spring Data | 4.4% | 5.3% |
| Spring Security | 2.8% | 3.9% |
| Jackson | 1.9% | 2.2% |
| JWT | 0.3% | 0.9% |

상위 leaf는 `URL` host/hash 처리(300 **6.0%**, 360 **2.7%**),
`sun.net.www.ParseUtil.firstEncodeIndex`(**4.7%·5.0%**), `HashMap.getNode`(**4.0%·4.0%**),
`ThreadLocalMap.getEntry`(**2.6%·3.2%**), Spring Boot jar `Handler.indexOfSeparator`
(**2.4%·2.1%**) 등이었다. 작은 공통 비용이 넓게 분산된 모양이며 특정 도메인 메서드 하나가
튀지는 않았다.

## 폐기·관측자 효과

1. `jfr-short-1`은 재기동 직후 120/s를 15초만 넣어 JIT·커넥션·쿼리 경로가 차가운 상태에서
   대기열이 먼저 생겼다. 120/s부터 p50 6.3초·timeout 1,303건이라 **워밍업 실패로 폐기**했다.
2. `jfr-short-2`는 60→120/s로 정상 예열했지만 표준 `profile.jfc`가 allocation stack·socket·park
   이벤트까지 수집해 300/s p50이 **2.78초**가 됐다. **프로파일러 오버헤드로 폐기**했다.
3. `jfr-short-3`은 CPU sample, GC, thread allocation 누계, 20ms 이상 monitor만 남긴 custom JFC로
   줄였다. 그래도 직전 무프로파일 라운드의 300/s p50 6ms보다 45ms로 높다. 따라서 이 라운드의
   latency를 용량 수치로 재사용하지 않고 **hotspot의 방향만** 읽는다.

Artillery의 `firstCounterAt`은 실제 앱 CPU 상승보다 8.6~9.1초 빨랐다. 프로파일 분석은 JFR
`CPULoad`가 Docker VM의 10%(약 1.1 cores)를 3회 연속 넘는 첫 시점으로 실제 20초 창을 정렬했다.
이는 결과를 고르는 기준이 아니라 서로 다른 시계열의 시계만 맞춘 것이다.

## 한계와 다음 확인

- 같은 호스트의 Artillery가 앱/DB와 자원을 나눈다.
- 10ms sampling이라 짧거나 inline된 메서드는 빠질 수 있고, sample 비율은 정확한 CPU 시간이 아니다.
- endpoint 이름을 JVM stack에 심지 않았으므로 태그/무태그별 CPU sample을 직접 분리하지 못했다.
- 포화 상태에서는 작은 profiler 비용도 queueing을 크게 증폭한다. 이번 응답시간은 운영 용량이 아니다.
- Boot jar cache 경합의 상위 호출자를 확인하려면 stack depth를 명시적으로 늘리거나 async-profiler로
  **300/s 한 구간만** 다시 보면 된다. 현 결과만으로 코드 변경을 시작하지 않는다.

실행 뒤 앱은 원래 `JAVA_TOOL_OPTIONS=-Xmx1280m -XX:MaxMetaspaceSize=256m`, 2 vCPU / 2 GiB로
재생성했고 인증된 장소 조회 HTTP 200을 확인했다.
