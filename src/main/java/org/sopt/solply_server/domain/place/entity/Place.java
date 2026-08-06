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
import jakarta.persistence.OrderColumn;
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
                @Index(name = "idx_places_town_active_created", columnList = "town_id, active, created_at"),
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

    @BatchSize(size = 50)
    @OneToMany(mappedBy = "place", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PlaceTag> placeTags = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String contactNumber;

    private String openingHours;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

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
    @CollectionTable(name = "place_checkpoints", joinColumns = @JoinColumn(name = "place_id"))
    @OrderColumn(name = "display_order") // 순서 컬럼
    @Column(name = "content", nullable = false)
    private List<String> checkpoints = new ArrayList<>();


    /**
     * 목록 응답의 썸네일이 여기서 온다({@code getThumbnailFileKey}). 배치 크기를 목록 경로의
     * 페이지 최대치({@code MAX_PAGE_SIZE} = 50)에 맞춰, 지연 로딩이 <b>페이지당 정확히 1회</b>로
     * 고정됨을 코드에 못박는다 — 20이면 50건 페이지가 3회로 쪼개진다.
     *
     * <p><b>요청당 statements는 이 값으로 줄지 않는다.</b> 목록 경로의 +2(placeTags fetch join이
     * 붙은 장소 조회 + 이 컬렉션)를 +1로 합치자던 후속(perf 문서 §7.2)은 <b>실현 불가로
     * 재분류한다</b>: placeTags·placeImageInfos가 둘 다 List bag이라 이중 fetch join이
     * {@code MultipleBagFetchException}이고, 컬렉션 타입을 바꾸는 것은 이 경로만의 문제가 아니다.
     */
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

    @Column(nullable = false)
    private boolean active;

    public static Place create(
            String name,
            String introduction,
            String address,
            Double latitude,
            Double longitude,
            String contactNumber,
            String openingHours,
            Map<SnsPlatform, String> snsLinks,
            List<String> imageFileKeys,
            List<String> checkpoints,
            Town town,
            User createdBy,
			Boolean active,
            Tag mainTag,
            List<Tag> option1Tags,
            List<Tag> option2Tags
    ) {
        Place p = new Place();
        p.name = name;
        p.introduction = introduction;
        p.address = address;
        p.latitude = latitude;
        p.longitude = longitude;
        p.contactNumber = contactNumber;
        p.openingHours = openingHours;
        p.town = town;
        p.createdBy = createdBy;
		p.active = active;

        p.applySnsLinks(snsLinks);
        p.replaceImagesByKeys(imageFileKeys);
        p.replaceCheckpoints(checkpoints);
        p.replaceTags(mainTag, option1Tags, option2Tags);

        return p;
    }

    public void update(
            String name,
            String introduction,
            String address,
            Double latitude,
            Double longitude,
            String contactNumber,
            String openingHours,
            Town town,
            Map<SnsPlatform, String> snsLinks,
            List<String> imageFileKeys,
            List<String> checkpoints,
            Tag mainTag,
            List<Tag> option1Tags,
            List<Tag> option2Tags
    ) {
        this.name = name;
        this.introduction = introduction;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.contactNumber = contactNumber;
        this.openingHours = openingHours;
        this.town = town;

        applySnsLinks(snsLinks);
        replaceImagesByKeys(imageFileKeys);
        replaceCheckpoints(checkpoints);
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

    public void clearTags() {
        this.placeTags.clear();
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

    public List<Tag> getTags() {
        return placeTags.stream()
                .map(PlaceTag::getTag)
                .toList();
    }

    public Optional<Tag> getMainTag() {
        return placeTags.stream()
                .map(PlaceTag::getTag)
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

    private void replaceCheckpoints(List<String> checkpoints) {
        this.checkpoints.clear();
        if (checkpoints == null || checkpoints.isEmpty()) return;

        // 중복 제거(순서 유지) + null/blank 제거
        for (String cp : new LinkedHashSet<>(checkpoints)) {
            if (cp == null) continue;
            String trimmed = cp.trim();
            if (trimmed.isBlank()) continue;
            this.checkpoints.add(trimmed);
        }
    }

}