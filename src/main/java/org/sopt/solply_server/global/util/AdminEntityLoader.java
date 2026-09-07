package org.sopt.solply_server.global.util;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.course.repository.AdminCourseRepository;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRequestRepository;
import org.sopt.solply_server.domain.admin.tag.repository.AdminTagRepository;
import org.sopt.solply_server.domain.admin.town.repository.AdminTownRepository;
import org.sopt.solply_server.domain.admin.user.repository.AdminUserRepository;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminEntityLoader {

    private final AdminTagRepository adminTagRepository;
    private final AdminTownRepository adminTownRepository;
    private final AdminPlaceRepository adminPlaceRepository;
    private final AdminCourseRepository adminCourseRepository;
    private final AdminUserRepository adminUserRepository;
    private final AdminPlaceRequestRepository adminPlaceRequestRepository;

    public Course getCourse(Long courseId) {
        return adminCourseRepository.findById(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));
    }

    public Course getCourseWithPlacesAndTown(Long courseId) {
        return adminCourseRepository.findByIdWithPlacesAndTown(courseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_COURSE));
    }

    public User getUser(Long userId) {
        return adminUserRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
    }

    public Town getTown(Long townId) {
        return adminTownRepository.findById(townId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN));
    }

    public Tag getTag(Long id) {
        return adminTagRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TAG));
    }

    public Place getPlace(Long placeId) {
        return adminPlaceRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    public Place getPlaceWithTown(Long placeId) {
        return adminPlaceRepository.findByIdWithTown(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    public PlaceRequest getPlaceRequest(Long requestId) {
        return adminPlaceRequestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE_REQUEST));
    }

    public Place getPlaceWithTownAndCheckpoints(Long placeId) {
        return adminPlaceRepository.findByIdWithTownAndCheckpoints(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    public List<Course> getAllWithTownByTownId(Long townId) {
        return adminCourseRepository.findAllWithTownByTownId(townId);
    }
}