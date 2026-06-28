package org.sopt.solply_server.domain.recommend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(
        name = "recommend_example_phrases",
        indexes = {
                @Index(
                        name = "idx_phrase_town_type_active",
                        columnList = "town_id, target_type, active"
                )
        }
)
public class RecommendExamplePhrase extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어떤 동네의 예시 문구인지 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "town_id", nullable = false)
    private Town town;

    /** 장소/코스 구분 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private RecommendTargetType targetType;

    /** 예시 문구 내용 */
    @Column(name = "content", nullable = false, length = 255)
    private String content;

    /** 고정 정렬 키 */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 활성 여부 */
    @Column(name = "active", nullable = false)
    private boolean active;
}
