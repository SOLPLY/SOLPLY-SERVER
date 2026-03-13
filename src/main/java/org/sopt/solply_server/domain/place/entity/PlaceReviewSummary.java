package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "place_review_summaries")
public class PlaceReviewSummary {

    @Id
    private Long placeId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id")
    private Place place;

    @Column(columnDefinition = "TEXT")
    private String summaryContent;

    @Column(nullable = false)
    private int reviewCountAtTime;

    private LocalDateTime updatedAt;

    public static PlaceReviewSummary create(Place place, String summaryContent, int reviewCountAtTime) {
        PlaceReviewSummary summary = new PlaceReviewSummary();
        summary.place = place;
        summary.summaryContent = summaryContent;
        summary.reviewCountAtTime = reviewCountAtTime;
        summary.updatedAt = LocalDateTime.now();
        return summary;
    }

    public void update(String summaryContent, int reviewCountAtTime) {
        this.summaryContent = summaryContent;
        this.reviewCountAtTime = reviewCountAtTime;
        this.updatedAt = LocalDateTime.now();
    }
}
