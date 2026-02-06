package org.sopt.solply_server.domain.course.entity;

import jakarta.persistence.*;
import lombok.*;
import org.sopt.solply_server.domain.tag.entity.Tag;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(
        name = "course_tag",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_course_tag_course_tag", columnNames = {"course_id", "tag_id"})
        },
        indexes = {
                @Index(name = "idx_course_tag_course_id", columnList = "course_id"),
                @Index(name = "idx_course_tag_tag_id", columnList = "tag_id")
        }
)
public class CourseTag {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    public static CourseTag of(Course course, Tag tag) {
        CourseTag ct = new CourseTag();
        ct.course = course;
        ct.tag = tag;
        return ct;
    }
}