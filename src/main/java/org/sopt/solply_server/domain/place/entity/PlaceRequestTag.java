package org.sopt.solply_server.domain.place.entity;

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
import org.sopt.solply_server.domain.tag.entity.Tag;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "place_request_tag",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_place_request_tag_place_tag", columnNames = {"place_request_id", "tag_id"})
        },
        indexes = {
                @Index(name = "idx_place_request_tag_tag_id", columnList = "tag_id")
        }
)
public class PlaceRequestTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_request_id", nullable = false)
    private PlaceRequest placeRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

}