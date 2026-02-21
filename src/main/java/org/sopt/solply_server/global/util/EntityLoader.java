package org.sopt.solply_server.global.util;

import java.util.List;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRequestRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class EntityLoader {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final PlaceRepository placeRepository;
    private final TownRepository townRepository;
    private final TagRepository tagRepository;
    private final PlaceRequestRepository placeRequestRepository;
    private final AdminPlaceRepository adminPlaceRepository;

    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
    }

    public Course getActiveCourse(Long courseId) {
        return courseRepository.findByIdAndActiveTrue(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));
    }

    public Course getActiveCourseWithTagsAndPlaces(Long courseId) {
        return courseRepository.findActiveByIdWithTagsAndPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));
    }

    public Place getPlace(Long placeId) {
        return placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    public Place getPlaceWithTownAndCheckpoints(Long placeId) {
        return placeRepository.findByIdWithTownAndCheckpoints(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    public Place getPlaceWithTown(Long placeId) {
        return adminPlaceRepository.findByIdWithTown(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    public List<Place> getPlacesWithTown(List<Long> placeIds) {
        return placeRepository.findAllByIdsWithTown(placeIds);
    }


    public Town getTown(Long townId) {
        return townRepository.findById(townId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN));
    }

    public Town getActiveTown(Long townId) {
        return townRepository.findTownByIdAndActiveTrue(townId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN));
    }


    public Tag getTag(Long id) {
        return tagRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TAG));
    }

    public Tag getActiveTag(Long id) {
        return tagRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TAG));
    }

    public PlaceRequest getPlaceRequest(Long requestId) {
        return placeRequestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE_REQUEST));
    }

}