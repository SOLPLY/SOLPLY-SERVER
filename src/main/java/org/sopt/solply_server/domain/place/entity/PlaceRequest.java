package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.*;
import lombok.*;
import org.sopt.solply_server.domain.user.entity.User;
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
    private String address;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Builder.Default
    @ElementCollection
    @CollectionTable(
            name = "place_request_image",
            joinColumns = @JoinColumn(name = "place_request_id")
    )
    private List<PlaceRequestImageInfo> images = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "placeRequest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PlaceRequestTag> placeRequestTags = new ArrayList<>();

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;
}
