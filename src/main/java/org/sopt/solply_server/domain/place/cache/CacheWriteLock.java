package org.sopt.solply_server.domain.place.cache;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 목록 캐시의 <b>쓰기를 한 줄로 세우는</b> 락. 여기 들어오는 것은 둘이다 — 발행물 설치
 * ({@link SnapshotInstaller#installLatest()}의 교체와 설치 id 읽기)와 어드민 훅의 전 구간
 * ({@link SnapshotRefresher} — 밀린 발행물 채택 → 후보 계산 → 발행 → 설치).
 *
 * <p><b>막는 것은 어드민 수정의 유실이다.</b> 재빌드는 DB를 읽고 그 결과로 스냅샷과 홀더의 맵을
 * 통째로 간다. 락이 없으면 이런 순서가 가능하다 — 타이머 재빌드가 원본을 다 읽는다 → 어드민이
 * 이름을 고치고 커밋한 뒤 홀더에 새 이름을 넣는다 → 재빌드가 <em>읽어 둔 옛 이름</em>으로 맵을
 * 통째로 갈아 끼운다. 어드민 수정이 조용히 사라지고, 되돌아오는 것은 다음 회차 뒤다. 락으로 줄을
 * 세우면 패치는 언제나 재빌드의 교체 <b>뒤에</b> 들어가고, 재빌드 둘도 서로 겹치지 않는다.
 *
 * <p><b>부분 패치가 들어오면서 막을 것이 하나 늘었다 — 읽고-고쳐-쓰기의 겹침이다.</b> 부분 패치는
 * "지금 최신 스냅샷을 집어 → 그 위에 손댄 장소를 얹어 → 새 스냅샷으로 공표"한다. 둘이 겹치면
 * 둘 다 같은 옛 스냅샷을 집어, 나중에 공표하는 쪽이 <b>상대가 얹은 수정을 통째로 빠뜨린 배열</b>을
 * 더 큰 번호로 올린다. 잃는 것이 표시값 한 칸이 아니라 남의 수정 전체다. 그 창을 여는 데 필요한
 * 것은 어드민 요청 둘이 겹치는 것뿐이라, 이 락이 부분 패치의 <b>전 구간</b>(집기 → 짓기 → 발급 →
 * 공표)을 감싼다.
 *
 * <p><b>락이 트랜잭션보다 먼저다 — 커넥션을 쥔 채 기다리지 않기 위해서다.</b> 순서를 뒤집으면
 * 어드민 훅은 커넥션을 먼저 받고 그것을 쥔 채 락 앞에 눕는다. 그런데 락을 쥔 쪽은 발행
 * 트랜잭션({@code SnapshotPublicationService})으로 커넥션을 하나 더 요구하므로, 어드민 둘이
 * 동시에 커밋하면 각자의 원본 트랜잭션 커넥션(커밋 뒤에도 {@code afterCommit} 동안은 아직 반납
 * 전이다) 둘 + 기다리는 쪽의 읽기 커넥션 하나로 넷이 묶이고, 발행이 다섯 번째를 기다리다
 * 타임아웃한다. 락을 먼저 잡으면 기다리는 동안 쥐는 커넥션이 없다.
 *
 * <p><b>이 락과 DB 행 락의 순서도 한 방향뿐이다:</b> 이 락이 바깥, 짧은 DB 트랜잭션이 안이다.
 * DB 행 락을 든 채 이 락을 잡는 경로를 만들지 말 것 — 발행자는 락 밖에서 짓고
 * ({@code SnapshotPublisher}), 설치는 행 락을 들지 않은 채 이 락을 잡는다.
 *
 * <p><b>커넥션 점유 — 락 대기 중 0개, 락 안에서 최대 1개다</b>(어드민 경로는 아직 반납되지 않은
 * 원본 트랜잭션 커넥션이 하나 더 있어 +1). 락 안의 1개는 읽기 트랜잭션과 발행 트랜잭션이
 * <b>겹치지 않고</b> 차례로 쓰기 때문이다 — 읽기를 닫은 뒤에 발행한다
 * ({@code SnapshotRefresher}).
 *
 * <p>재빌드도 부분 패치도 락 안에서 커밋된 수정을 못 보는 일이 없다. 읽기 트랜잭션 자체를 락
 * 안에서 열고 닫으므로 일관 읽기 스냅샷이 잡히는 시점이 언제나 락을 잡은 뒤다.
 *
 * <p><b>어드민 쓰기가 이 락 앞에 줄을 서는 것은 커밋 <em>뒤</em>다.</b> 그래서 앞사람을 기다리는
 * 동안에도 DB 락은 이미 풀려 있고, 기다리는 것은 응답 하나가 늦어지는 것뿐이다 — 부분 패치의
 * 락 구간이 전량 재빌드보다 훨씬 짧으므로(손댄 행만 읽는다) 그 줄이 길어질 자리도 아니다.
 *
 * <p><b>조회 경로는 이 락을 잡지 않는다.</b> 홀더의 {@code get}은 {@code volatile} 읽기라
 * 여기와 무관하고, 그래서 재빌드가 도는 동안에도 목록 응답은 멈추지 않는다.
 */
@Component
public class CacheWriteLock {

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
