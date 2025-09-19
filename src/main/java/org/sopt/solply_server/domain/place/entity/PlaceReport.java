package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.*;
import lombok.*;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "place_reports",
        indexes = {
                @Index(name = "idx_place_report_place_id", columnList = "place_id"),
                @Index(name = "idx_place_report_status", columnList = "status"),
                @Index(name = "idx_place_report_created_at", columnList = "created_at")
        }
)
public class PlaceReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaceReportType reportType;

    @Column(columnDefinition = "TEXT")
    private String content;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "place_report_images", joinColumns = @JoinColumn(name = "place_report_id"))
    @Column(name = "image_key", columnDefinition = "TEXT")
    private List<String> imageKeys = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaceReportStatus status;

    public static PlaceReport create(Place place, User user, PlaceReportType reportType, String content, List<String> imageKeys) {
        return PlaceReport.builder()
                .place(place)
                .user(user)
                .reportType(reportType)
                .content(content)
                .imageKeys(imageKeys != null ? new ArrayList<>(imageKeys) : new ArrayList<>())
                .status(PlaceReportStatus.PENDING)
                .build();
    }

    public void updateStatus(PlaceReportStatus status) {
        this.status = status;
    }
}