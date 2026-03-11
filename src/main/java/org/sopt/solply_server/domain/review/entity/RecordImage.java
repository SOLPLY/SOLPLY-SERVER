package org.sopt.solply_server.domain.review.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "record_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecordImage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "record_image_id")
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "record_id", nullable = false)
  private Record record;

  @Column(name = "image_url", nullable = false, length = 500)
  private String imageUrl;

  @Builder
  private RecordImage(Record record, String imageUrl) {
    this.record = record;
    this.imageUrl = imageUrl;
  }

  public static RecordImage create(Record record, String imageUrl) {
    return RecordImage.builder()
        .record(record)
        .imageUrl(imageUrl)
        .build();
  }
}