package org.sopt.solply_server.domain.course.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "course_bookmark",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_place_bookmark_user_place", columnNames = {"user_id", "course_id"})
        },
        indexes = {
                @Index(name = "idx_course_bookmark_user_course",
                        columnList = "user_id, course_id",
                        unique = true
                )
        }
)
public class CourseBookmark extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public static CourseBookmark create(Course course, User user) {
        return CourseBookmark.builder()
                .course(course)
                .user(user)
                .build();
    }

}