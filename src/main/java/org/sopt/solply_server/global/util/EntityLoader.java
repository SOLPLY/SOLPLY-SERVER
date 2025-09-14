package org.sopt.solply_server.global.util;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserInterestTown;
import org.sopt.solply_server.domain.user.repository.UserInterestTownRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EntityLoader {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final PlaceRepository placeRepository;
    private final TownRepository townRepository;
    private final UserInterestTownRepository userInterestTownRepository;

    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));
    }

    public Course getCourse(Long courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));
    }

    public Course getCourseWithPlaces(Long courseId) {
        return courseRepository.findByIdWithPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));
    }

    public Place getPlace(Long placeId) {
        return placeRepository.findById(placeId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));
    }

    public Town getTown(Long townId) {
        return townRepository.findById(townId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_TOWN));
    }

//    public List<UserInterestTown> getInterestTownsWithTownsByIds(Long userId) {
//        return userInterestTownRepository.findAllByUserWithTown(userId);
//    }
}