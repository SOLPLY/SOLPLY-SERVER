package org.sopt.solply_server.global.bench;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비교 측정이 <b>준비를 다시 태우려고</b> 부르는 통로. 1-B(웜 준비)와 번호 변경 뒤 라운드가 쓴다.
 *
 * <p><b>이 통로는 원본을 고치지 않는다.</b> DB도, 동네 번호도 건드리지 않는다 — 번호가 오르면
 * 진행 중인 커서가 만료돼 그 라운드의 요청 성격이 통째로 바뀐다. 그래서 응답의
 * {@code versionsBumped}는 언제나 0이고, 도구가 그 값을 원자료에 남긴다.
 *
 * <p><b>구성마다 측정창이 다르다.</b> 전역은 이 호출 <em>안에서</em> 전량을 짓고
 * ({@code preparedDuringCall: true}) 동네는 비우기만 해 다음 요청이 적재한다
 * ({@code false}). 두 창의 값을 같은 칸에 넣으면 전역 스냅샷의 준비 비용이 통째로 사라진다.
 *
 * <p><b>서는 조건이 셋이다</b> — 프로파일, 프로퍼티, 보안. {@link BenchProperties} 참조.
 */
@RestController
@RequestMapping("/bench")
@Profile("bench")
@ConditionalOnProperty(prefix = "solply.bench", name = "enabled", havingValue = "true")
public class BenchPrepResetController {

    private final BenchPrepResetService service;

    public BenchPrepResetController(BenchPrepResetService service) {
        this.service = service;
    }

    /**
     * @param request {@code townIds} 생략·빈 배열이면 그 구성의 준비 전체.
     *                {@code dryRun}이면 <b>아무것도 비우지 않고</b> 무엇을 비울지만 답한다 —
     *                게이트 확인이 이 값으로 부르므로 라운드를 오염시키면 안 된다
     */
    @PostMapping("/prep/reset")
    public ResponseEntity<BenchPrepResetResponse> reset(
            @RequestBody(required = false) BenchPrepResetRequest request) {

        BenchPrepResetRequest safe = request == null
                ? new BenchPrepResetRequest(List.of(), false)
                : request;
        return ResponseEntity.ok(service.reset(safe.townIdsOrEmpty(), safe.dryRunOrFalse()));
    }
}
