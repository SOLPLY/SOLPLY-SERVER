package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
public class PlaceImageInfo {

    @Column(name = "image_file_key", columnDefinition = "TEXT", nullable = false)
    private String imageFileKey;

    @Column(name = "display_order")
    private Integer displayOrder;  // 1번째 사진이 썸네일 사진
}