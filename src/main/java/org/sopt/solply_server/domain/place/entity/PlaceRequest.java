package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.*;
import lombok.*;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "place_request")
public class PlaceRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String placeName;

    @Column(nullable = false)
    private String mainTag;

    @Column(columnDefinition = "TEXT")
    private String SubTagAIds;

    @Column(columnDefinition = "TEXT")
    private String SubTagBIds;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "place_request_images", joinColumns = @JoinColumn(name = "place_request_id"))
    @OrderBy("displayOrder ASC")
    private List<PlaceRequestImageInfo> images = new ArrayList<>();



}
