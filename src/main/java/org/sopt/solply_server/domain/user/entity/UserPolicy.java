package org.sopt.solply_server.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Table(name = "user_policies")
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserPolicy {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false, name = "policy_type")
    @Enumerated(value = EnumType.STRING)
    private PolicyType policyType;

    private String version;

    @Column(nullable = false)
    private String title;

    @Lob
    private String content;

    private boolean required; // 필수/선택

    private boolean active;   // 현재 유효한 약관인지
}