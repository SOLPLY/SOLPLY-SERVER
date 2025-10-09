package org.sopt.solply_server.domain.place.service;

import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.PlaceRequestImageInfo;
import org.sopt.solply_server.domain.place.repository.PlaceRequestRepository;
import org.sopt.solply_server.global.listener.ImageFieldUpdater;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class PlaceRequestImageUpdater implements ImageFieldUpdater {

    private final PlaceRequestRepository placeRequestRepository;

    @Override
    public TargetDir supportedDir() {
        return TargetDir.PLACE_REQUEST;
    }


    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceImages(final long requestId, final List<String> destKeys) {
        var pr = placeRequestRepository.findById(requestId).orElseThrow();
        pr.getImages().clear();
        int order = 0;
        for (String key : new LinkedHashSet<>(destKeys)) {
            pr.getImages().add(new PlaceRequestImageInfo(key, order++));
        }
    }
}