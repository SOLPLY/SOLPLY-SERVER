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
import org.sopt.solply_server.domain.place.cache.SnapshotViewPatcher;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataService;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;

/**
 * <b>이미지 키가 스테이징에서 최종으로 바뀌면 목록의 썸네일도 따라 바뀌어야 한다.</b>
 *
 * <p>이 경로는 어드민 트랜잭션이 커밋된 뒤에 돌므로, 그 전에 지어진 표시값은 스테이징 키이고
 * 이동(MOVE)은 원본을 지운다. 번호를 올리지 않으면 목록이 다음 리빌드까지 <b>죽은 URL</b>을
 * 내면서 아무 오류도 내지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceImageFieldUpdaterTest {

    private static final long PLACE_ID = 42L;

    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceStatsRepository placeStatsRepository;
    @Mock private SnapshotMetadataService snapshotMetadataService;
    @Mock private SnapshotViewPatcher snapshotViewPatcher;
    @Mock private Place place;

    @InjectMocks private PlaceImageFieldUpdater updater;

    /**
     * <b>이 경로는 어드민 트랜잭션이 커밋된 뒤에 따로 돈다({@code REQUIRES_NEW}).</b> 그래서
     * 어드민 쓰기가 올린 번호로는 여기서 바뀐 썸네일 키가 덮이지 않는다 — 여기서 번호를 다시
     * 올리지 않으면 최종 키가 어느 인스턴스에도 반영되지 않고 목록이 죽은 URL을 낸다.
     */
    @Test
    void 이미지_키를_갈아_끼우면_번호를_올리고_그_장소의_표시값을_얹는다() {
        List<PlaceImageInfo> images = new ArrayList<>();
        given(placeRepository.findById(PLACE_ID)).willReturn(Optional.of(place));
        given(place.getPlaceImageInfos()).willReturn(images);

        updater.replaceImages(PLACE_ID, List.of("place/42/최종키"));

        assertThat(images).hasSize(1);
        verify(snapshotMetadataService).markChanged(SnapshotCursorPolicy.PRESERVE);
        verify(snapshotViewPatcher).patchPlacesAfterCommit(List.of(PLACE_ID));
    }

    /**
     * <b>썸네일 교체는 진행 중인 스크롤을 끊지 않는다.</b> 바뀌는 것이 표시값 한 칸뿐이라 정렬
     * 순서가 그대로이기 때문이다. 여기가 {@code ADVANCE}로 바뀌면 S3 이동이 끝날 때마다 그 순간
     * 스크롤 중이던 사용자가 전부 첫 페이지로 되돌아간다 — 어드민이 고를 수도 없는 자리에서.
     */
    @Test
    void 썸네일_교체는_커서_회차를_올리지_않는다() {
        given(placeRepository.findById(PLACE_ID)).willReturn(Optional.of(place));
        given(place.getPlaceImageInfos()).willReturn(new ArrayList<>());

        updater.replaceImages(PLACE_ID, List.of("place/42/최종키"));

        verify(snapshotMetadataService).markChanged(SnapshotCursorPolicy.PRESERVE);
    }

    /**
     * <b>upsert가 번호 올리기보다 먼저다.</b> 썸네일 키가 {@code place_stats}의 칸이 된 뒤로(V40)
     * 리빌드가 읽는 원천이 그 칸이라, 순서가 뒤집히면 번호를 보고 달려온 리빌드가 방금 갈아 끼운
     * 최종 키가 아니라 스테이징 키를 다시 싣는다 — 값은 그럴듯하고 URL만 죽어 있어 어느 단언에도
     * 걸리지 않는 종류의 버그다.
     */
    @Test
    void place_stats의_썸네일_칸을_먼저_다시_짓고_그_다음에_번호를_올린다() {
        given(placeRepository.findById(PLACE_ID)).willReturn(Optional.of(place));
        given(place.getPlaceImageInfos()).willReturn(new ArrayList<>());

        updater.replaceImages(PLACE_ID, List.of("place/42/최종키"));

        InOrder order = inOrder(placeStatsRepository, snapshotMetadataService);
        order.verify(placeStatsRepository).upsertRowsForActivePlaces(List.of(PLACE_ID));
        order.verify(snapshotMetadataService).markChanged(SnapshotCursorPolicy.PRESERVE);
    }
}
