package org.sopt.solply_server.domain.tag.entity;

import jakarta.persistence.*;
import lombok.*;
import org.sopt.solply_server.domain.user.entity.UserPersona;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(
        name = "tag_persona_mappings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tag_persona", columnNames = {"tag_id", "persona"})
        },
        indexes = {
                @Index(name = "idx_tpm_persona", columnList = "persona"),
                @Index(name = "idx_tpm_tag", columnList = "tag_id"),
                @Index(name = "idx_tpm_persona_weight", columnList = "persona, weight")
        }
)
public class TagPersonaMapping {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserPersona persona;

    /**
     * 기존에 Arrays.asList에 중복으로 넣던 걸 "가중치"로 모델링.
     * (없으면 1로 두고, 더 밀어주고 싶으면 2,3…)
     */
    @Column(nullable = false)
    private int weight;

    public static TagPersonaMapping of(Tag tag, UserPersona persona, int weight) {
        return new TagPersonaMapping(null, tag, persona, weight);
    }
}