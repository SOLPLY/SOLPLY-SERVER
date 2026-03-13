package org.sopt.solply_server.domain.place.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceSearchDocumentService {

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;

    @Transactional
    public void markDirtyByPlaceId(Long placeId) {
        placeSearchDocumentRepository.findById(placeId)
                .ifPresent(document -> {
                    document.markDirty();
                    log.info("PlaceSearchDocument DIRTY 전환 - placeId={}", placeId);
                });
    }

    @Transactional
    public void markDirtyByTagId(Long tagId) {
        List<PlaceSearchDocument> affectedDocuments = placeSearchDocumentRepository.findAllByTagId(tagId);
        affectedDocuments.forEach(document -> {
            document.markDirty();
            log.info("PlaceSearchDocument DIRTY 전환 (태그 변경) - placeId={}, tagId={}", document.getPlaceId(), tagId);
        });
    }
}
