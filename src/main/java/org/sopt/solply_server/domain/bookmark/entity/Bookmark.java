package org.sopt.solply_server.domain.bookmark.entity;

import jakarta.persistence.*;
import lombok.*;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(
        name = "bookmarks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_bookmark_user_target",
                        columnNames = {"user_id", "target_type", "target_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_bookmark_target",
                        columnList = "target_type, target_id"
                ),
                @Index(
                        name = "idx_bookmark_user_type_created",
                        columnList = "user_id, target_type, created_at DESC"
                )
        }
)
public class Bookmark extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 누가 북마크했는지 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 어떤 도메인인지 (COURSE, PLACE, …) */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private BookmarkTargetType targetType;

    /** 대상 엔티티의 ID */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    public static Bookmark create(User user, BookmarkTargetType targetType, Long targetId) {
        return Bookmark.builder()
                .user(user)
                .targetType(targetType)
                .targetId(targetId)
                .build();
    }
}