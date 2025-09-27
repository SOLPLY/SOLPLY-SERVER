package org.sopt.solply_server.domain.user.entity;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WithdrawReason {
    NOT_USE( "자주 사용하지 않아서"),
    DEFICIENT_INFO("원하는 지역과 장소가 부족해서"),
    INCONVENIENT("앱 기능이 불편해서"),
    HATE_RECOMMEND("추천 콘텐츠가 나와 맞지 않아서"),
    USE_OTHER_SERVICE("다른 서비스를 사용하고 있습니다."),
    OTHERS("기타");

    private final String description;

}