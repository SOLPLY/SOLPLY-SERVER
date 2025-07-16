package org.sopt.solply_server.domain.place.util;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlaceValidator {

    private final PlaceRepository placeRepository;

    public void validatePlaceExists(Long placeId) {
        if (!placeRepository.existsById(placeId)) {
            throw new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE);
        }
    }
}