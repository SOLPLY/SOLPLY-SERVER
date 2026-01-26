package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "places",
        indexes = {
                @Index(name = "idx_places_town_id", columnList = "town_id"),
                @Index(name = "idx_places_created_by_created_at", columnList = "created_by, created_at")
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

    @OneToMany(mappedBy = "place", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PlaceTag> placeTags = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String contactNumber;

    private String openingHours;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(nullable = false) private Long placeDefaultId;

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


    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "place_images", joinColumns = @JoinColumn(name = "place_id"))
    @OrderBy("displayOrder ASC") // displayOrder가 낮은 순서로 DB 내에서 정렬
    private List<PlaceImageInfo> placeImageInfos = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "town_id", nullable = false)
    private Town town;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    public static Place create(
            String name,
            String introduction,
            String address,
            Long placeDefaultId,
            Double latitude,
            Double longitude,
            String contactNumber,
            String openingHours,
            String placeType,
            Map<SnsPlatform, String> snsLinks,
            List<String> imageFileKeys,
            Town town,
            User createdBy,
            Tag mainTag,
            List<Tag> option1Tags,
            List<Tag> option2Tags
    ) {
        Place p = new Place();
        p.name = name;
        p.introduction = introduction;
        p.address = address;
        p.placeDefaultId = placeDefaultId;
        p.latitude = latitude;
        p.longitude = longitude;
        p.contactNumber = contactNumber;
        p.openingHours = openingHours;
        p.placeType = placeType;
        p.town = town;
        p.createdBy = createdBy;

        p.applySnsLinks(snsLinks);
        p.replaceImagesByKeys(imageFileKeys);
        p.replaceTags(mainTag, option1Tags, option2Tags);

        return p;
    }

    public void update(
            String name,
            String introduction,
            String address,
            Long placeDefaultId,
            Double latitude,
            Double longitude,
            String contactNumber,
            String openingHours,
            String placeType,
            Town town,
            Map<SnsPlatform, String> snsLinks,
            List<String> imageFileKeys,
            Tag mainTag,
            List<Tag> option1Tags,
            List<Tag> option2Tags
    ) {
        this.name = name;
        this.introduction = introduction;
        this.address = address;
        this.placeDefaultId = placeDefaultId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.contactNumber = contactNumber;
        this.openingHours = openingHours;
        this.placeType = placeType;
        this.town = town;

        applySnsLinks(snsLinks);
        replaceImagesByKeys(imageFileKeys);
        replaceTags(mainTag, option1Tags, option2Tags);
    }
    private void applySnsLinks(Map<SnsPlatform, String> links) {
        this.snsLinks.clear();
        if (links == null || links.isEmpty()) return;

        // 값이 null/blank면 제거하고 저장
        links.forEach((platform, url) -> {
            if (platform == null) return;
            if (url == null) return;
            String trimmed = url.trim();
            if (trimmed.isBlank()) return;
            this.snsLinks.put(platform, trimmed);
        });
    }

    private void replaceImagesByKeys(List<String> imageFileKeys) {
        this.placeImageInfos.clear();
        if (imageFileKeys == null || imageFileKeys.isEmpty()) return;

        // 중복 제거(순서 유지) + displayOrder 1부터
        int order = 1;
        for (String key : new java.util.LinkedHashSet<>(imageFileKeys)) {
            if (key == null) continue;
            String k = key.trim();
            if (k.isBlank()) continue;
            this.placeImageInfos.add(new PlaceImageInfo(k, order++));
        }
    }

    private void replaceTags(Tag mainTag, List<Tag> option1Tags, List<Tag> option2Tags) {
        this.placeTags.clear();
        Set<Tag> notDuplicated = new LinkedHashSet<>();

        // MAIN (필수)
        if (mainTag != null && notDuplicated.add(mainTag)) {
            this.placeTags.add(PlaceTag.of(this, mainTag));
        }

        // OPTION1 (1개 이상)
        if (option1Tags != null) {
            for (Tag t : option1Tags) {
                if (t == null) continue;
                if (notDuplicated.add(t)) this.placeTags.add(PlaceTag.of(this, t));
            }
        }

        // OPTION2 (선택)
        if (option2Tags != null) {
            for (Tag t : option2Tags) {
                if (t == null) continue;
                if (notDuplicated.add(t)) this.placeTags.add(PlaceTag.of(this, t));
            }
        }
    }

    // === 편의 메서드 추가 ===

    /** active tag만 */
    public List<Tag> getActiveTags() {
        return placeTags.stream()
                .map(PlaceTag::getTag)
                .filter(Tag::isActive)
                .toList();
    }

    /** active MAIN만 */
    public Optional<Tag> getActiveMainTag() {
        return placeTags.stream()
                .map(PlaceTag::getTag)
                .filter(Tag::isActive)
                .filter(tag -> tag.getType() == TagType.MAIN)
                .findFirst();
    }

    /** active tagId Set (추천/스코어링용) */
    public Set<Long> getActiveTagIds() {
        return placeTags.stream()
                .map(PlaceTag::getTag)
                .filter(Tag::isActive)
                .map(Tag::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 썸네일 이미지 파일 키 추출 (첫 번째 이미지)
     */
    public String getThumbnailFileKey() {
        return this.placeImageInfos.stream()
                .findFirst()
                .map(PlaceImageInfo::getImageFileKey)
                .orElse(null);
    }

}