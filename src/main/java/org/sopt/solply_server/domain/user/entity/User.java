package org.sopt.solply_server.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 30)
    private String nickname;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private boolean isNewUser;

    @Enumerated(EnumType.STRING)
    private UserPersona persona;

    public static User create(String email) {
        return User.builder()
                .email(email)
                .isNewUser(true)
                .persona(UserPersona.ANYTHING)
                .build();
    }

    public void updateOnboardingInfo(UserPersona persona, String nickname) {
        this.persona = persona;
        this.nickname = nickname;
        this.isNewUser = false; // 온보딩 완료
    }

}