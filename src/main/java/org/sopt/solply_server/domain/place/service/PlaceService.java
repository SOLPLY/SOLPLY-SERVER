package org.sopt.solply_server.domain.place.service;

import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final PlaceBookmarkRepository placeBookmarkRepository;
    private final ImageUrlProvider imageUrlProvider;

    public PlaceAllGetResponse findPlaceDetailsById(Long userId, Long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        // MAIN에 해당하는 태그를 가져와서 저장
        TagName primaryTag = place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(tag -> tag.getType() == TagType.MAIN)
                .findFirst()
                .map(Tag::getName)
                .orElse(null);

        List<PlaceImageInfoDto> imageInfos = place.getPlaceImageInfos().stream()
                .map(info -> PlaceImageInfoDto.of(
                        info.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(info.getImageFileKey())
                ))
                .toList();

        boolean isBookmarked = placeBookmarkRepository.existsByPlaceIdAndUserId(placeId, userId);

        return PlaceAllGetResponse.of(
                place,
                primaryTag,
                imageInfos,
                isBookmarked
        );
    }
}