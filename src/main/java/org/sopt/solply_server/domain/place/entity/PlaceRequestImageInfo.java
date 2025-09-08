package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
public class PlaceRequestImageInfo {

    @Column(name = "image_file_key", columnDefinition = "TEXT", nullable = false)
    private String imageFileKey;

    @Column(name = "display_order")
    private Integer displayOrder;
}