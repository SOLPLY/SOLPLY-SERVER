package org.sopt.solply_server.domain.admin.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.SnapshotRefresher;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;

/**
 * <b>이미지 키가 스테이징에서 최종으로 바뀌면 목록의 썸네일도 그 자리에서 바뀌어야 한다.</b>
 *
 * <p>이 경로는 어드민 트랜잭션이 커밋된 뒤 비동기로 돌므로, 어드민 쓰기가 건 전량 재빌드는 이미
 * 끝난 뒤다 — 그 재빌드가 찍은 것은 스테이징 키이고 이동(MOVE)은 원본을 지운다. 표시값 패치를
 * 걸지 않으면 목록이 다음 전량 재빌드(≤10분)까지 <b>죽은 URL</b>을 내면서 아무 오류도 내지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceImageFieldUpdaterTest {

    private static final long PLACE_ID = 42L;

    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceStatsRepository placeStatsRepository;
    @Mock private SnapshotRefresher snapshotRefresher;
    @Mock private Place place;

    @InjectMocks private PlaceImageFieldUpdater updater;

    @Test
    void 이미지_키를_갈아_끼우면_그_장소의_표시값_패치를_건다() {
        List<PlaceImageInfo> images = new ArrayList<>();
        given(placeRepository.findById(PLACE_ID)).willReturn(Optional.of(place));
        given(place.getPlaceImageInfos()).willReturn(images);

        updater.replaceImages(PLACE_ID, List.of("place/42/최종키"));

        assertThat(images).hasSize(1);
        verify(snapshotRefresher).patchPlaceViewAfterCommit(PLACE_ID);
    }

    /**
     * <b>upsert가 패치보다 먼저다.</b> 썸네일 키가 {@code place_stats}의 칸이 된 뒤로(V40) 패치가
     * 읽는 원천이 그 칸이라, 순서가 뒤집히면 패치는 방금 갈아 끼운 최종 키가 아니라 스테이징 키를
     * 다시 싣는다 — 값은 그럴듯하고 URL만 죽어 있어 어느 단언에도 걸리지 않는 종류의 버그다.
     */
    @Test
    void place_stats의_썸네일_칸을_먼저_다시_짓고_그_다음에_패치를_건다() {
        given(placeRepository.findById(PLACE_ID)).willReturn(Optional.of(place));
        given(place.getPlaceImageInfos()).willReturn(new ArrayList<>());

        updater.replaceImages(PLACE_ID, List.of("place/42/최종키"));

        InOrder order = inOrder(placeStatsRepository, snapshotRefresher);
        order.verify(placeStatsRepository).upsertRowsForActivePlaces(List.of(PLACE_ID));
        order.verify(snapshotRefresher).patchPlaceViewAfterCommit(PLACE_ID);
    }
}
