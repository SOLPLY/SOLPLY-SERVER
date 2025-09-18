package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PlaceRequestImageInfo {

    @Column(name = "image_file_key", nullable = false, columnDefinition = "TEXT")
    private String imageFileKey;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;
}