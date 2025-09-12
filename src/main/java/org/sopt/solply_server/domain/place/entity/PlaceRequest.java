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
    private String address;

    @Column(nullable = false)
    private String placeName;

    @OneToMany(mappedBy = "placeRequest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PlaceRequestTag> placeRequestTags = new ArrayList<>();

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;
}
