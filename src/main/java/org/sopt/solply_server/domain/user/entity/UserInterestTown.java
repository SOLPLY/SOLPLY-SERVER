package org.sopt.solply_server.domain.user.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;
import org.sopt.solply_server.domain.town.entity.Town;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "user_town",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "town_id"}) // 한 유저가 같은 동네를 여러 번 관심 등록하지 X
        },
        indexes = {
            @Index(name = "idx_user_interest_town_user", columnList = "user_id"),
        }
)
public class UserInterestTown {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "town_id", nullable = false)
    private Town town;

    public static UserInterestTown create(User user, Town town) {
        return UserInterestTown.builder()
                .user(user)
                .town(town)
                .build();
    }

}