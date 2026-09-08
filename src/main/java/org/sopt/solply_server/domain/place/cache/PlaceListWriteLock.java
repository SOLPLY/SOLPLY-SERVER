package org.sopt.solply_server.domain.place.cache;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 목록 캐시의 <b>쓰기를 한 줄로 세우는</b> 락. 여기 들어오는 것은 둘이다 — 전량 재빌드
 * ({@link PlaceListSnapshotLoader#rebuild()})와 표시값 패치
 * ({@link PlaceListSnapshotRefresher}의 홀더 {@code put}).
 *
 * <p><b>막는 것은 어드민 수정의 유실이다.</b> 재빌드는 DB를 읽고 그 결과로 홀더의 맵을 통째로
 * 간다. 락이 없으면 이런 순서가 가능하다 — 타이머 재빌드가 원본을 다 읽는다 → 어드민이 이름을
 * 고치고 커밋한 뒤 홀더에 새 이름을 넣는다 → 재빌드가 <em>읽어 둔 옛 이름</em>으로 맵을 통째로
 * 갈아 끼운다. 어드민 수정이 조용히 사라지고, 되돌아오는 것은 다음 회차 뒤다. 락으로 줄을
 * 세우면 패치는 언제나 재빌드의 교체 <b>뒤에</b> 들어가고, 재빌드 둘도 서로 겹치지 않는다.
 *
 * <p><b>재빌드는 락을 트랜잭션 <em>안</em>에서 잡는다.</b> {@code rebuild()}가
 * {@code @Transactional(REQUIRES_NEW)}라 프록시가 먼저 트랜잭션을 열고 메서드 첫 줄이 락을
 * 잡는데, 그래도 유실 창이 열리지 않는다 — MySQL InnoDB의 일관 읽기 스냅샷은 {@code BEGIN}이
 * 아니라 <b>첫 SELECT</b>에서 잡히기 때문이다. 즉 트랜잭션이 열린 시점이 락보다 앞서도 실제로
 * 읽는 데이터는 락을 잡은 뒤의 상태이므로, 락 안에서 커밋된 어드민 수정을 못 보는 일이 없다.
 *
 * <p><b>조회 경로는 이 락을 잡지 않는다.</b> 홀더의 {@code get}은 {@code volatile} 읽기라
 * 여기와 무관하고, 그래서 재빌드가 도는 동안에도 목록 응답은 멈추지 않는다.
 */
@Component
public class PlaceListWriteLock {

    private final ReentrantLock lock = new ReentrantLock();

    public void run(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T call(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
