package org.sopt.solply_server.domain.course.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "courses",
        indexes = {
                @Index(name = "idx_places_town_id", columnList = "town_id")
        }
)
public class Course {

    private Long id;

    private String name;

    private String introduction;




}