package org.sopt.solply_server.domain.user.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserPersona {
    REST("조용한 공간에 오래 머물고 싶어요"),
    EXPLORER("이곳저곳 가볍게 둘러보고 싶어요"),
    MOODING("내 취향에 맞는 공간을 찾고싶어요"),
    NATURAL("풍경을 감상하며 쉬고 싶어요");

    private final String description;
}
