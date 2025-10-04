package org.sopt.solply_server.domain.user.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE users SET is_deleted = true, deleted_at = NOW() WHERE id = ?")
@Where(clause = "is_deleted = false")
@Table(name = "users",
        uniqueConstraints = {
            @UniqueConstraint(name = "ux_users_nickname", columnNames = {"nickname"})
        }
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 30)
    private String nickname;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(length = 2048)
    private String profileImageFileKey;


    @Column(nullable = false)
    private boolean isNewUser;

    @Enumerated(EnumType.STRING)
    private UserPersona persona;

    @Column(nullable = false)
    private boolean isDeleted = false;

    private LocalDateTime deletedAt;

    private Long selectedTownId; // 사용자가 선택한 동네 ID

    public static User create(String email) {
        return User.builder()
                .email(email)
                .isNewUser(true)
                .persona(UserPersona.ANYTHING)
                .build();
    }

    public void updateuserInfo(UserPersona persona, String nickname, String profileImageFileKey) {
        this.persona = persona;
        this.nickname = nickname;
        this.profileImageFileKey = profileImageFileKey;
    }

    public void updateSelectedTown(Long selectedTownId) {
        this.selectedTownId = selectedTownId;
    }

    public void updateOnboardingInfo(UserPersona persona, String nickname, Long selectedTownId) {
        this.persona = persona;
        this.nickname = nickname;
        this.selectedTownId = selectedTownId;
        this.isNewUser = false;

    }
}