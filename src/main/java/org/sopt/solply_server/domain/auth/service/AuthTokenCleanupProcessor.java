package org.sopt.solply_server.domain.auth.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.auth.repository.AdminAuthCodeRepository;
import org.sopt.solply_server.domain.admin.auth.repository.AdminOAuthStateRepository;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정리 회차의 한 문장 단위. <b>덩어리마다 자기 트랜잭션을 연다</b> — 한 번에 다 지우면 그만큼의
 * 행을 커밋까지 잠그고, 중간에 실패하면 지운 것까지 통째로 되돌아간다.
 */
@Component
@RequiredArgsConstructor
public class AuthTokenCleanupProcessor {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminOAuthStateRepository adminOAuthStateRepository;
    private final AdminAuthCodeRepository adminAuthCodeRepository;

    /**
     * 보존 기간이 지난 <b>계열</b>을 한 덩어리 지운다.
     *
     * <p><b>계약: 고르는 단위도 지우는 단위도 계열 전체다.</b> 선택 조건
     * {@code MAX(expires_at) < cutoff}는 "이 계열에는 기준 시각 이후까지 사는 토큰이 하나도 없다"는
     * 뜻이므로, 살아 있는 계열은 선택되지 않고 선택된 계열은 부모·자식이 함께 사라진다.
     * 부모-자식 관계가 반쪽만 남는 상태는 생기지 않는다.
     *
     * <p>계열 단위여야 하는 이유는 이력이 재사용 판정의 근거이기 때문이다. 회전된 부모만 먼저
     * 지우면 그 부모로 들어온 재사용 요청이 "행이 없다"로 보여 전체 폐기가 일어나지 않는다.
     *
     * <p>조회와 삭제가 두 문장인 것은 MySQL의 다중 테이블 DELETE가 {@code LIMIT}을 받지 않기
     * 때문이고, 두 문장이 같은 {@code cutoff} 술어를 쓰는 것이 그 분리를 상쇄한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int deleteExpiredRefreshFamilyBatch(long cutoffEpochSecond, int batchSize) {
        List<String> familyIds = refreshTokenRepository.findExpiredFamilyIds(cutoffEpochSecond, batchSize);
        if (familyIds.isEmpty()) {
            return 0;
        }
        return refreshTokenRepository.deleteExpiredTokensOfFamilies(familyIds, cutoffEpochSecond);
    }

    /**
     * 만료된 어드민 임시 데이터를 한 덩어리 지운다. 소비된 행도 만료 시각이 지나면 함께 걸린다.
     *
     * <p>소비와 경쟁하지 않는다 — 소비는 {@code expires_at > now}인 행만 건드리고 정리는
     * {@code expires_at < now}인 행만 건드린다. 두 집합은 겹치지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int deleteExpiredAdminStateBatch(long nowEpochMilli, int batchSize) {
        return adminOAuthStateRepository.deleteExpired(nowEpochMilli, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int deleteExpiredAdminAuthCodeBatch(long nowEpochMilli, int batchSize) {
        return adminAuthCodeRepository.deleteExpired(nowEpochMilli, batchSize);
    }
}
