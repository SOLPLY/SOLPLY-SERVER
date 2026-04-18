package org.sopt.solply_server.domain.course.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.sopt.solply_server.global.ai.FloatArrayConverter;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "course_search_documents")
public class CourseSearchDocument extends BaseTimeEntity {

    @Id
    private Long courseId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

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

    public static CourseSearchDocument init(Course course) {
        CourseSearchDocument doc = new CourseSearchDocument();
        doc.course = course;
        doc.retrievalText = "";
        doc.status = EmbeddingStatus.INIT;
        return doc;
    }

    public void updateEmbedding(String retrievalText, float[] embedding, String embeddingModel) {
        this.retrievalText = retrievalText;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
        this.status = EmbeddingStatus.READY;
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
