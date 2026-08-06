# 2026-08-06 · JFR 포화 구간 짧은 재현

직전 포화 진단에서 답하지 못한 "앱 CPU를 실제로 어떤 메서드가 쓰는가"를 확인하는 보조
캠페인이다. 제품 코드와 DB는 바꾸지 않는다. 기존 앱 이미지의 JRE에 포함된 `jdk.jfr`을 JVM
기동 인자로 활성화하고, 동일 요청 믹스를 60·120 arrivals/s로 각각 30초 워밍업한 뒤 300·360
arrivals/s를 각각 20초 실행한다. 낮은 부하부터 올리는 이유는 새 JVM에 곧바로 120/s를 넣은 첫
라운드에서 JIT·커넥션·쿼리 캐시가 예열되기 전에 대기열이 생겨 워밍업 자체가 실패했기 때문이다.

```bash
cd load-test/campaigns/2026-08-06_jfr-saturation-short
./tools/run-jfr.sh jfr-short-new
# 최종 JFR을 다시 텍스트 분석하려면
./tools/export-and-analyze.sh jfr-short-3
```

스크립트는 앱만 JFR 인자로 재생성하고 MySQL·nginx는 유지한다. 종료 시 JFR을 회수한 뒤 반드시
원래 `docker-compose.bench.yml`의 앱(2 vCPU·2 GiB·JFR 없음)으로 복원한다. `actuator/health`는
사용하지 않는다. Redis 제거 이후 Redis health indicator가 DOWN이라 서비스 API가 정상이어도
503이기 때문이다. 인증된 장소 조회의 HTTP 200을 readiness로 사용한다.

JFR은 샘플링 프로파일이다. 짧은 메서드와 인라이닝된 프레임이 빠질 수 있고, 샘플 비율을 정확한
CPU 시간으로 읽으면 안 된다. Artillery도 같은 macOS 호스트에서 실행되므로 응답시간과 포화점은
운영 용량이 아니라 이 로컬 환경의 재현값이다.

표준 `profile.jfc`는 이 2 vCPU 한계 구간에서 결과를 크게 왜곡했다. 최종 라운드는
`cpu-hotspots.jfc`로 실행 샘플(10ms)·native 샘플(20ms)·GC·스레드 할당 누계·20ms 이상 monitor
경합만 남겼다. allocation stack sample, socket, park/sleep 이벤트는 끈다. 따라서 **할당 총량은
볼 수 있지만 할당 객체/호출부 hotspot은 이 라운드가 답하지 않는다.**

## 실행 기록

| 라운드 | 판정 | 이유 |
|---|---|---|
| `jfr-short-1` | 폐기 | 재기동 직후 120/s 15초 워밍업 실패 — 낮은 부하부터 이미 timeout |
| `jfr-short-2` | 폐기 | 워밍업은 정상, 표준 `profile.jfc`가 300/s 결과를 과도하게 왜곡 |
| `jfr-short-3` | **분석 정본** | CPU 중심 custom JFC, 전 요청 HTTP 200·실패 0 |

결과와 해석: [`docs/perf/2026-08-06-jfr-saturation-short.md`](../../../docs/perf/2026-08-06-jfr-saturation-short.md)
