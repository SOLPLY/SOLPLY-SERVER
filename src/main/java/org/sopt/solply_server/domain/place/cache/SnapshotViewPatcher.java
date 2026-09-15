package org.sopt.solply_server.domain.place.cache;

import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 수정의 <b>표시값</b>(이름·썸네일·대표 태그·태그 이름)을 커밋 직후 메모리에 얹는다.
 * 리빌드를 기다리면 최대 폴 간격 + 리빌드 시간만큼 늦는데, 어드민은 방금 고친 것을 바로 확인한다.
 *
 * <p><b>표시값만이다.</b> 정렬 키·소속·태그 비트는 얹지 않는다 — 그것을 메모리에서 고치려면
 * 정렬 배열을 부분 수정해야 하고, 그 순간 "스냅샷은 어느 한 시점의 완결된 것"이라는 계약이
 * 깨진다. 순서에 영향을 주는 변경은 리빌드가 통째로 다시 읽어 반영한다.
 *
 * <p><b>번호는 읽은 쪽이 정한다.</b> 부르는 쪽의 쓰기 번호를 달고 가지 않는다 — 값을 다시
 * 읽는 것은 커밋 <em>뒤</em>라, 그 사이 남의 커밋이 끼면 <b>번호는 내 것인데 데이터는 그 뒤의
 * 것</b>인 어긋난 쌍이 된다. 대신 {@link SnapshotLoader}가 값과 같은 트랜잭션에서 관측한
 * 번호를 함께 돌려주고, 그 값이 그대로 패치의 번호가 된다. 설치자의 비교가 모두 관측값끼리
 * 이뤄지는 근거가 이것이다.
 *
 * <p><b>왜 afterCommit인가.</b> 커밋 전에 얹으면 롤백된 수정이 화면에 남는다. 커밋된 뒤라야
 * 읽어 온 값이 실제로 확정된 값이다.
 *
 * <p><b>실패해도 던지지 않는다.</b> 여기서 던지면 이미 커밋된 어드민 요청이 500으로 나간다.
 * 못 얹은 값은 다음 리빌드가 원본에서 그대로 가져오므로, 손실이 아니라 <b>지연</b>이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotViewPatcher {

    private final SnapshotLoader loader;
    private final SnapshotInstaller installer;

    /** 손댄 장소들의 표시값을 커밋 직후 얹는다. */
    public void patchPlacesAfterCommit(Collection<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return;
        }
        List<Long> targets = List.copyOf(placeIds);
        afterCommit(() -> {
            SnapshotLoader.ViewState state = loader.readViews(targets);
            installer.patchPlaceViews(state.metadata(), targets, state.views());
        });
    }

    /** 태그 표시값 한 벌을 커밋 직후 얹는다. */
    public void patchTagsAfterCommit() {
        afterCommit(() -> {
            SnapshotLoader.TagViewState state = loader.readTagViews();
            installer.patchTagViews(state.metadata(), state.tagViews());
        });
    }

    private void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 없이 불린 경우(테스트·직접 호출). 이미 커밋된 것과 같은 상태다
            run(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run(task);
            }
        });
    }

    private void run(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("어드민 수정의 표시값 즉시 반영 실패 - 다음 리빌드가 원본에서 가져온다", e);
        }
    }
}
