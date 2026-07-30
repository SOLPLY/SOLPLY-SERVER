package org.sopt.solply_server.domain.place.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인기순 복합 점수 전량 재계산. 트랜잭션 경계를 소유한다.
 *
 * <p>청크로 나누지 않는 이유: 집계가 단일 SQL이라 나눌 지점이 없고, 부분 반영된 상태
 * (일부 장소만 새 점수)가 오히려 순위를 어긋나게 만든다. 전체가 한 트랜잭션이라
 * 실패하면 이전 세대 값이 그대로 남고, 다음 회차가 다시 정답을 알려준다.
 *
 * <p><b>READ_COMMITTED인 이유 — 이 클래스에서 가장 중요한 한 줄이다.</b>
 * MySQL의 {@code INSERT ... SELECT}는 REPEATABLE READ(스프링 기본)에서 <b>소스 테이블</b>의
 * 스캔 행에 shared next-key 락을 건다. 실측으로 배치 트랜잭션이 {@code bookmarks} 1,073만 행에
 * 락을 잡았고(lock struct 28,518 / heap 4.4MB), 그동안 동시 북마크 INSERT가 대기했다.
 * READ COMMITTED에서는 소스 테이블 락이 <b>0건</b>이고 동시 INSERT가 즉시 성공한다.
 * 집계가 스캔 도중 커밋된 행을 일부 포함할 수 있으나, 이 값은 애초에 "대략 지금" 스냅샷이고
 * 24시간 stale을 수용하는 2급 데이터라 문제가 되지 않는다.
 * 자세한 실측 근거는 {@link PlaceStatsRepository#upsertAll} javadoc 참조.
 *
 * <p><b>격리 수준은 이 메서드가 트랜잭션을 <em>새로 시작</em>할 때만 적용된다.</b>
 * 이미 열린 트랜잭션에 참여하면 스프링이 격리 수준 지정을 무시하고 바깥 트랜잭션의 격리를
 * 그대로 쓴다(기본 {@code validateExistingTransaction = false}). 운영 경로에서는
 * {@link org.sopt.solply_server.domain.place.service.facade.PlaceStatsFacade}가
 * 트랜잭션 없이 호출하므로 여기서 새 트랜잭션이 열려 RC가 실제로 적용된다 —
 * {@code PlaceStatsBatchProcessorIT}에서 {@code SELECT @@transaction_isolation}으로
 * {@code READ-COMMITTED}가 나오는 것을 실측 검증한다.
 * <b>이 프로세서를 다른 {@code @Transactional} 서비스 안에서 호출하면 RC 보장이 조용히 사라진다.</b>
 */
@Component
@RequiredArgsConstructor
public class PlaceStatsBatchProcessor {

    private final PlaceStatsRepository placeStatsRepository;
    private final PlaceStatsProperties properties;

    /**
     * @param calculatedAt 감쇠 기준 시각. 호출자가 정해 넘기므로 같은 값이면 결과가 같다 (멱등).
     * @return UPSERT가 영향을 준 행 수. MySQL이 INSERT를 1, UPDATE를 2로 세므로 장소 수가 아니다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recalculateAll(LocalDateTime calculatedAt) {
        return placeStatsRepository.upsertAll(
                calculatedAt,
                properties.getBookmarkWeight(),
                properties.getReviewWeight(),
                properties.getHalfLifeDays());
    }
}
