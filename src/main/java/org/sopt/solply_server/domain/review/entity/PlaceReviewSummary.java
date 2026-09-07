package org.sopt.solply_server.domain.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "place_review_summaries")
public class PlaceReviewSummary extends BaseTimeEntity {

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

    public static PlaceReviewSummary create(Place place, String summaryContent, int reviewCountAtTime) {
        PlaceReviewSummary summary = new PlaceReviewSummary();
        summary.place = place;
        summary.summaryContent = summaryContent;
        summary.reviewCountAtTime = reviewCountAtTime;
        return summary;
    }

    public void update(String summaryContent, int reviewCountAtTime) {
        this.summaryContent = summaryContent;
        this.reviewCountAtTime = reviewCountAtTime;
    }
}
