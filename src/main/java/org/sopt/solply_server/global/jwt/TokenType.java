package org.sopt.solply_server.global.jwt;

import java.util.Locale;

/**
 * {@code type} 클레임의 값. 서명 키가 access/refresh로 갈려 있어도 이 클레임을 검증한다 —
 * 키가 하나로 합쳐지는 날 refresh를 access로 들이미는 경로가 조용히 열리지 않게 하는 자물쇠다.
 */
public enum TokenType {

    ACCESS,
    REFRESH;

    /** 토큰에 실리는 문자열. 대소문자까지 계약이다 — 검증이 정확히 이 값을 요구한다. */
    public String claimValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
