package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceImageInfo {

    @Column(name = "image_file_key", columnDefinition = "TEXT", nullable = false)
    private String imageFileKey;

    @Column(name = "display_order")
    private Integer displayOrder;  // 1번째 사진이 썸네일 사진

    public PlaceImageInfo(String imageFileKey, Integer displayOrder) {
        this.imageFileKey = imageFileKey;
        this.displayOrder = displayOrder;
    }

    public static PlaceImageInfo of(String imageFileKey, Integer displayOrder) {
        return new PlaceImageInfo(imageFileKey, displayOrder);
    }
}