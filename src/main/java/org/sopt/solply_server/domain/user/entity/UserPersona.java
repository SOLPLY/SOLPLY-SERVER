package org.sopt.solply_server.domain.user.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserPersona {
    HEALING("REST", "조용한 공간에 오래 머물고 싶어요"),
    EXPLORER("EXPLORER", "이곳저곳 둘러보고 싶어요"),
    MOODING("MOODING", "취향이 담긴 곳을 찾고싶어요"),
    NATURAL("NATURAL", "자연을 감상하며 쉬고 싶어요");

    private final String code;
    private final String description;
}
