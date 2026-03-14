package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.sopt.solply_server.global.ai.FloatArrayConverter;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "place_search_documents")
public class PlaceSearchDocument extends BaseTimeEntity {

    @Id
    private Long placeId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id")
    private Place place;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String retrievalText;

    @Convert(converter = FloatArrayConverter.class)
    @Column(columnDefinition = "MEDIUMBLOB")
    private float[] embedding;

    @Column(length = 100)
    private String embeddingModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmbeddingStatus status;

    public static PlaceSearchDocument init(Place place) {
        PlaceSearchDocument doc = new PlaceSearchDocument();
        doc.place = place;
        doc.retrievalText = "";
        doc.status = EmbeddingStatus.INIT;
        return doc;
    }

    /**
     * 임베딩 결과를 반영합니다.
     * startedAt 이후에 문서가 수정되었다면(updatedAt > startedAt) 임베딩 중 변경이 발생한 것이므로
     * READY 대신 DIRTY 상태를 유지하여 다음 배치 사이클에 재임베딩되도록 합니다.
     */
    public void updateEmbedding(String retrievalText, float[] embedding, String embeddingModel, LocalDateTime startedAt) {
        this.retrievalText = retrievalText;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
        this.status = (this.updatedAt != null && this.updatedAt.isAfter(startedAt))
                ? EmbeddingStatus.DIRTY
                : EmbeddingStatus.READY;
    }

    public void markFailed() {
        this.status = EmbeddingStatus.FAILED;
    }

    public void markDirty() {
        if (this.status != EmbeddingStatus.INIT) {
            this.status = EmbeddingStatus.DIRTY;
        }
    }
}
