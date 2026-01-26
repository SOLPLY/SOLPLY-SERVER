package org.sopt.solply_server.domain.town.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(
        name = "towns",
        indexes = {
                @Index(name = "idx_town_parent_id", columnList = "parent_id")
        }
)
public class Town {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Town parent;

    @Column(nullable = false)
    private Boolean active;

    public static Town create(
        String name,
        Town parent
    ){
        Town t = new Town();
        t.name = name;
        t.parent = parent;

        return t;
    }

    public void update(
        String name,
        Town parent
    ){
        this.name = name;
        this.parent = parent;
    }

    public void updateActivation(
        boolean active
    ) {
        this.active = active;
    }
}