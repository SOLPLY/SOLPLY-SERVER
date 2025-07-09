package org.sopt.solply_server.domain.course.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Table(name = "course_place",
        indexes = {
                @Index(name = "idx_course_place_course_id_place_id", columnList = "course_id, place_id", unique = true),
                @Index(name = "idx_course_place_place_id", columnList = "place_id")
        }
)
public class CoursePlace extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int placeOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

}