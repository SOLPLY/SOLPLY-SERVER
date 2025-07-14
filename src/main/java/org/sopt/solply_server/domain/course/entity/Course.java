package org.sopt.solply_server.domain.course.entity;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

import lombok.*;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.global.entity.BaseTimeEntity;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "courses",
        indexes = {
                @Index(name = "idx_course_town_id", columnList = "town_id"),
                @Index(name = "idx_course_is_shared", columnList = "is_shared")
        }
)
public class Course extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String introduction;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT false")
    private boolean isShared;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "town_id", nullable = false)
    private Town town;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("placeOrder ASC")
    private List<CoursePlace> coursePlaces = new ArrayList<>();

    public static Course createUserCourse(String name, String introduction, Town town) {
        return Course.builder()
                .name(name)
                .introduction(introduction)
                .isShared(false) // 사용자 생성 코스는 기본 비공개
                .town(town)
                .coursePlaces(new ArrayList<>())
                .build();
    }

    public void addCoursePlace(CoursePlace coursePlace) {
        this.coursePlaces.add(coursePlace);
    }

    public static String generateUniqueName(String baseName, int sequence) {
        if (sequence == 0) {
            return baseName;
        }
        return String.format("%s (%d)", baseName, sequence);
    }

}