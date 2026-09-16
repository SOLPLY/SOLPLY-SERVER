package org.sopt.solply_server.domain.admin.place.service;

import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.cache.SnapshotViewPatcher;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataService;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.global.listener.ImageFieldUpdater;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class PlaceImageFieldUpdater implements ImageFieldUpdater {

    private final PlaceRepository placeRepository;
    /** 썸네일 키가 {@code place_stats}의 칸이라 이 경로도 그 칸을 다시 짓는다 (V40) */
    private final PlaceStatsRepository placeStatsRepository;
    /** 이미지 키가 바뀌면 썸네일 URL이 바뀐다 — 이유는 {@link #replaceImages} */
    private final SnapshotMetadataService snapshotMetadataService;
    private final SnapshotViewPatcher snapshotViewPatcher;

    @Override
    public TargetDir supportedDir() {
        return TargetDir.PLACE;
    }

    /**
     * S3 이동·복사가 끝난 뒤 {@code place_images}의 키를 스테이징에서 최종으로 갈아 끼운다.
     * 어드민 요청 트랜잭션이 이미 커밋된 <b>뒤에</b> 비동기로 도는 경로다
     * ({@code ImageFileKeyUpdateListener}).
     *
     * <p><b>여기서 번호를 올리지 않으면 목록이 죽은 URL을 낸다.</b> 어드민 쓰기가 올린 번호는
     * 이 트랜잭션보다 <em>먼저</em> 리빌드를 부를 수 있어 스테이징 키를 싣고, 이동(MOVE)은 원본을
     * 지운다. 그 상태가 다음 리빌드까지 남으면서 아무 오류도 나지 않는다.
     *
     * <p><b>upsert가 번호 갱신·표시값 패치보다 앞이다.</b> 썸네일 키가 {@code place_stats}의
     * 칸이라(V40) 리빌드도 커밋 직후의 표시값 패치도 읽는 원천이 그 칸이다 — 순서가 뒤집히면
     * 둘 다 방금 갈아 끼운 키가 아니라 스테이징 키를 다시 싣는다. 엔티티 변경이 그 문장보다 먼저
     * flush되는 것은 {@code upsertRowsForActivePlaces}의 {@code flushAutomatically}가 보장한다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceImages(final long placeId, final List<String> destKeys) {
        var place = placeRepository.findById(placeId).orElseThrow();

        place.getPlaceImageInfos().clear();

        int order = 1; // ✅ Place는 1번이 썸네일이니까 1부터 추천
        for (String key : new LinkedHashSet<>(destKeys)) {
            if (key == null || key.isBlank()) continue;
            place.getPlaceImageInfos().add(new PlaceImageInfo(key, order++));
        }

        placeStatsRepository.upsertRowsForActivePlaces(List.of(placeId));
        // 썸네일이 바뀌는 것은 표시값뿐이라 진행 중인 스크롤을 끊지 않는다. S3 이동이 끝난 뒤
        // 비동기로 도는 경로라 애초에 어드민이 고를 자리도 아니다.
        // ⚠️ 이 메서드는 REQUIRES_NEW다 — 어드민 트랜잭션이 이미 커밋된 뒤에 돌므로 그쪽이 올린
        //    번호로는 이 변경이 덮이지 않는다. 여기서 번호를 다시 올리지 않으면 최종 키가 어느
        //    인스턴스에도 반영되지 않고 목록이 죽은 URL을 낸다
        snapshotMetadataService.markChanged(SnapshotCursorPolicy.PRESERVE);
        snapshotViewPatcher.patchPlacesAfterCommit(List.of(placeId));
    }
}