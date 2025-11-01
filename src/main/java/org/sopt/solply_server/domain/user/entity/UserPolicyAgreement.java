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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@Table(name = "user_policy_agreements",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_policy", columnNames = {"user_id", "policy_id"})
        }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamicInsert
public class UserPolicyAgreement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_policy_agreement_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "policy_id", nullable = false)
    private UserPolicy policy;

    @Column(nullable = false)
    private Boolean isAgree;


    public static UserPolicyAgreement create(User newUser, UserPolicy policy) {
        return UserPolicyAgreement.builder()
                .user(newUser)
                .policy(policy)
                .isAgree(true)
                .build();
    }

    public void updateIsAgree(Boolean isAgree) {
        this.isAgree = isAgree;
    }
}