package org.sopt.solply_server.domain.place.service;

import static java.util.stream.Collectors.toList;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.dto.PlaceThumbnailDto;
import org.sopt.solply_server.domain.place.dto.response.PlaceAllGetResponse;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilteringGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
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
    private final TownRepository townRepository;
    private final TagRepository tagRepository;
    private final TagValidator tagValidator;

    public PlaceAllGetResponse findPlaceDetailsById(final Long userId, final Long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        // MAIN에 해당하는 태그를 가져와서 저장
        TagName primaryTag = getPrimaryTag(place);

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

    public PlaceFilteringGetResponse findPlacesByTownAndTag(
            final Long userId, final Long townId, final Long mainTagId, final List<Long> subTagIdList) {
        Town selectedTown = townRepository.findById(townId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN));

        List<Place> places;

        // 메인 태그만 있는 경우
        if (mainTagId != null && subTagIdList == null) {
            Tag mainTag = tagRepository.findById(mainTagId)
                    .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TAG));
            tagValidator.validateMainTag(mainTag);
            places = placeRepository.findPlacesByTownAndMainTag(selectedTown, mainTagId);

        }
        // 메인 태그와 서브 태그가 모두 있는 경우
        else if (mainTagId != null && subTagIdList != null) {
            Tag mainTag = tagRepository.findById(mainTagId)
                    .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TAG));
            List<Tag> subTags = tagRepository.findAllById(subTagIdList);
            tagValidator.validateMainTagAndSubTag(mainTag,subTags);
            places = placeRepository.findPlacesByTownAndMainTagAndSubTags(selectedTown, mainTagId, subTagIdList);
        }
        // 전체조회
        else {
            places = placeRepository.findAll();
        }

        List<PlaceThumbnailDto> placeThumbnailDtoList = places.stream()
                .map(place -> PlaceThumbnailDto.of(place.getId(),
                                place.getName(),
                                imageUrlProvider.getImageUrl(getThumbnailFileKey(place)),
                                getPrimaryTag(place),
                                placeBookmarkRepository.existsByPlaceIdAndUserId(place.getId(), userId)
                        )
                )
                .toList();


        return PlaceFilteringGetResponse.from(placeThumbnailDtoList);
    }


    private static TagName getPrimaryTag(Place place) {
        return place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(tag -> tag.getType() == TagType.MAIN)
                .findFirst()
                .map(Tag::getName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLACE_TAG_REQUIRED));
    }

    public String getThumbnailFileKey(Place place) {
        return place.getPlaceImageInfos().stream()
                .findFirst()
                .map(PlaceImageInfo::getImageFileKey)
                .orElse(null);
    }
}