package org.sopt.solply_server.domain.place.cache;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 목록 캐시의 <b>쓰기를 한 줄로 세우는</b> 락. 여기 들어오는 것은 둘뿐이다.
 *
 * <ul>
 *   <li><b>리빌드 설치</b> — {@code SnapshotInstaller#rebuildAndInstall}이 지은 맵·정렬 배열을
 *       홀더에 걸고, 그 직전에 "이미 더 새것을 싣고 있나"를 확인하는 구간.</li>
 *   <li><b>커밋 직후 표시값 패치</b> — {@link SnapshotViewPatcher}가 손댄 장소의 이름·썸네일을
 *       지금 맵에 얹는 구간.</li>
 * </ul>
 *
 * <p><b>막는 것은 어드민 수정의 유실이다.</b> 리빌드는 DB를 읽고 그 결과로 스냅샷과 홀더의 맵을
 * 통째로 간다. 락이 없으면 이런 순서가 가능하다 — 폴이 띄운 리빌드가 원본을 다 읽는다 → 어드민이
 * 이름을 고치고 커밋한 뒤 홀더에 새 이름을 넣는다 → 리빌드가 <em>읽어 둔 옛 이름</em>으로 맵을
 * 통째로 갈아 끼운다. 어드민 수정이 조용히 사라지고, 되돌아오는 것은 다음 리빌드 뒤다.
 *
 * <p>락은 그 둘이 겹치지 않게 줄을 세우기만 한다. <b>어느 쪽이 이기는지를 정하는 것은 락이 아니라
 * 번호다</b> — 설치는 자기가 읽어 온 revision이 지금 설치된 것보다 새로울 때만 들어가고, 패치는
 * 자기 관측 revision이 그보다 새로울 때만 얹힌다. 둘의 순서가 어긋나 도착해도 결과가 같은 근거가
 * 그 비교이고, 규칙의 정본은 {@link SnapshotInstaller} javadoc이다.
 *
 * <p><b>락이 트랜잭션보다 먼저다 — 커넥션을 쥔 채 기다리지 않기 위해서다.</b> 순서를 뒤집으면
 * 커밋 직후의 패치가 표시값을 읽을 커넥션을 먼저 받고 그것을 쥔 채 락 앞에 눕는다. 어드민 둘이
 * 동시에 커밋하면 각자의 원본 트랜잭션 커넥션(커밋 뒤에도 {@code afterCommit} 동안은 아직 반납
 * 전이다)과 기다리는 쪽의 읽기 커넥션이 함께 묶여, 풀이 작을수록 먼저 말라붙는다. 지금 경로는
 * <b>읽기를 락 밖에서 끝내고 락 안에서는 대입만</b> 하므로 그 모양이 생기지 않는다.
 *
 * <p><b>이 락과 DB 행 락의 순서도 한 방향뿐이다:</b> 이 락이 바깥, 짧은 DB 트랜잭션이 안이다.
 * DB 행 락을 든 채 이 락을 잡는 경로를 만들지 말 것 — 지금은 어느 쪽도 그러지 않는다. 리빌드는
 * 읽기 트랜잭션을 닫은 뒤에 락을 잡고, 표시값 패치는 어드민 트랜잭션이 <b>커밋된 뒤</b>에
 * 잡는다.
 *
 * <p><b>커넥션 점유 — 락 대기 중 0개, 락 안에서 0개다.</b> 원본 읽기도 표시값 읽기도 락
 * <em>밖</em>이고, 락 안에서는 맵·참조를 갈아 끼우는 순수 계산만 돈다.
 *
 * <p><b>어드민 쓰기가 이 락 앞에 줄을 서는 것은 커밋 <em>뒤</em>다.</b> 그래서 앞사람을 기다리는
 * 동안에도 DB 락은 이미 풀려 있고, 기다리는 것은 응답 하나가 늦어지는 것뿐이다.
 *
 * <p><b>조회 경로는 이 락을 잡지 않는다.</b> 홀더의 {@code get}은 {@code volatile} 읽기라
 * 여기와 무관하고, 그래서 리빌드가 도는 동안에도 목록 응답은 멈추지 않는다.
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
