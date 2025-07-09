package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "places",
        indexes = {
                @Index(name = "idx_places_town_id", columnList = "town_id")
        }
)
public class Place extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String introduction;

    private String address;

    @Column(columnDefinition = "TEXT")
    private String contactNumber;

    @Column(columnDefinition = "geography(POINT, 4326)")
    private Point location;

    @Column(nullable = false)
    private Long placeDefaultId;

    @Column(nullable = false)
    private String placeType;

    /**
     * 아래와 같은 형태로 DB 저장
     * place_id: 1 / platform: INSTAGRAM / url: https://instagram.com/example
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "place_social_links", joinColumns = @JoinColumn(name = "place_id"))
    @MapKeyColumn(name = "platform") // INSTAGRAM
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "url", columnDefinition = "TEXT")
    private Map<SnsPlatform, String> snsLinks = new HashMap<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "place_images", joinColumns = @JoinColumn(name = "place_id"))
    @OrderBy("displayOrder ASC") // displayOrder가 낮은 순서로 DB 내에서 정렬
    private List<PlaceImageInfo> placeImageInfos = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "town_id", nullable = false)
    private Town town;

}