package org.sopt.solply_server.domain.admin.place.service;

import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

    @Override
    public TargetDir supportedDir() {
        return TargetDir.PLACE;
    }

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
    }
}