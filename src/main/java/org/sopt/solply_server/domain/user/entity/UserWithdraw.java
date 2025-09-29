package org.sopt.solply_server.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_withdraws")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class UserWithdraw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private WithdrawReason reason;

    @Column(name = "reason_text")
    private String reasonText;

    @Column(name = "withdrawn_at", nullable = false)
    private LocalDateTime withdrawnAt = LocalDateTime.now();

    public static UserWithdraw create(User user, WithdrawReason reason, String reasonText) {
        UserWithdraw userWithdraw = new UserWithdraw();
        userWithdraw.user = user;
        userWithdraw.reason = reason;
        userWithdraw.reasonText = reasonText;
        return userWithdraw;
    }

}