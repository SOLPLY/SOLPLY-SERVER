package org.sopt.solply_server.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Table(name = "social_user_info",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"social_code"})
        },
        indexes = {
                @Index(name = "idx_user_social_platform", columnList = "user_id, social_platform")
        }
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialUserInfo extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialPlatform socialPlatform;

    @Column(nullable = false, unique = true, length = 100)
    private String socialCode;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    // 정적 팩토리 메서드
    public static SocialUserInfo create(User user, SocialPlatform platform, String socialId) {
        return SocialUserInfo.builder()
                .user(user)
                .socialPlatform(platform)
                .socialCode(calculateSocialCode(platform, socialId))
                .build();
    }

    // 소셜 코드 생성 (플랫폼_소셜ID)
    protected static String calculateSocialCode(SocialPlatform socialPlatform, String socialId) {
        return String.format("%s_%s", socialPlatform.name(), socialId);
    }
}