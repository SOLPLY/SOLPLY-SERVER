package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.*;
import java.util.Collection;
import lombok.*;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "place_requests")
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
    @OrderBy("displayOrder ASC")
    @ElementCollection
    @CollectionTable(
            name = "place_request_images",
            joinColumns = @JoinColumn(name = "place_request_id")
    )
    private List<PlaceRequestImageInfo> images = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "placeRequest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PlaceRequestTag> placeRequestTags = new ArrayList<>();

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;


    public void addTags(Collection<Tag> tags) {
        if (tags == null || tags.isEmpty()) return;
        for (Tag tag : tags) {
            this.placeRequestTags.add(
                    PlaceRequestTag.builder()
                            .placeRequest(this)
                            .tag(tag)
                            .build()
            );
        }
    }
}
