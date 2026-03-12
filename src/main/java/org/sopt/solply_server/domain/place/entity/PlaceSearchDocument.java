package org.sopt.solply_server.domain.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.sopt.solply_server.global.ai.FloatArrayConverter;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "place_search_documents")
public class PlaceSearchDocument {

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

    private LocalDateTime generatedAt;

    public static PlaceSearchDocument create(Place place, String retrievalText, float[] embedding, String embeddingModel) {
        PlaceSearchDocument doc = new PlaceSearchDocument();
        doc.place = place;
        doc.retrievalText = retrievalText;
        doc.embedding = embedding;
        doc.embeddingModel = embeddingModel;
        doc.generatedAt = LocalDateTime.now();
        return doc;
    }

    public void updateEmbedding(String retrievalText, float[] embedding, String embeddingModel) {
        this.retrievalText = retrievalText;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
        this.generatedAt = LocalDateTime.now();
    }
}
