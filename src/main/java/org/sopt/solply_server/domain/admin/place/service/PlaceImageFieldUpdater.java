package org.sopt.solply_server.domain.admin.place.service;

import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshotRefresher;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.global.listener.ImageFieldUpdater;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class PlaceImageFieldUpdater implements ImageFieldUpdater {

    private final PlaceRepository placeRepository;
    /** 이미지 키가 바뀌면 썸네일 URL이 바뀐다 — 이유는 {@link #replaceImages} */
    private final PlaceListSnapshotRefresher placeListSnapshotRefresher;

    @Override
    public TargetDir supportedDir() {
        return TargetDir.PLACE;
    }

    /**
     * S3 이동·복사가 끝난 뒤 {@code place_images}의 키를 스테이징에서 최종으로 갈아 끼운다.
     * 어드민 요청 트랜잭션이 이미 커밋된 <b>뒤에</b> 비동기로 도는 경로다
     * ({@code ImageFileKeyUpdateListener}).
     *
     * <p><b>여기서 표시값 패치를 걸지 않으면 목록이 죽은 URL을 낸다.</b> 어드민 쓰기가 건 재빌드는
     * 이 트랜잭션보다 <em>먼저</em> 끝나므로 스테이징 키를 찍고, 이동(MOVE)은 원본을 지운다. 그
     * 상태가 다음 전량 재빌드(≤10분)까지 남으면서 아무 오류도 나지 않는다.
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

        placeListSnapshotRefresher.patchPlaceViewAfterCommit(placeId);
    }
}