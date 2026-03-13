package org.sopt.solply_server.domain.tag.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.user.entity.UserPersona;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(
        name = "tags",
        indexes = {
                @Index(name = "idx_tag_parent_id", columnList = "parent_id"),
                @Index(name = "idx_tag_id_type", columnList = "id, type"),
                @Index(name = "idx_tag_type_parent", columnList = "type, parent_id")
        }
)
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String meaning;

    @Enumerated(EnumType.STRING)
    private TagType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Tag parent;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT true")
    private boolean active;

    @OneToMany(mappedBy = "tag", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TagPersonaMapping> personaMappings = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TagUsage tagUsage;

    public static Tag create(String name, TagType type, Tag parent, Boolean active, TagUsage usage) {
        return Tag.builder()
                .name(name)
                .type(type)
                .parent(parent)
                .active(active)
                .tagUsage(usage)
                .build();
    }

    public void updateBasic(TagType type, Tag parent, String name, boolean active, TagUsage usage) {
        this.type = type;
        this.parent = parent;
        this.name = name;
        this.active = active;
        this.tagUsage = usage;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void replacePersonaMappings(List<UserPersona> personas, int weight) {
        this.personaMappings.clear();
        if (personas == null || personas.isEmpty()) return;

        personas.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(p -> this.personaMappings.add(TagPersonaMapping.of(this, p, weight)));
    }

}