package org.sopt.solply_server.domain.auth.service;

import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;

/**
 * 회전 트랜잭션의 결과.
 *
 * <p><b>재사용 감지가 예외가 아니라 값인 이유가 이 타입의 존재 이유다.</b> 재사용을 만나면
 * 그 사용자의 모든 refresh를 폐기해야 하는데, 그 폐기는 <b>실제로 커밋돼야</b> 의미가 있다.
 * 트랜잭션 안에서 예외를 던지면 폐기가 통째로 롤백되고 "감지했다"는 401만 남는다 — 즉
 * 탈취범의 토큰이 그대로 살아 있는 채로 경고만 나간다. 그래서 여기서는 값으로 돌려주고,
 * 커밋이 끝난 바깥 계층이 401로 바꾼다.
 */
public sealed interface RotationResult {

    /** 정상 회전 또는 유예 재구성. 어느 쪽이든 클라이언트가 받는 모양은 같다. */
    record Rotated(TokenCollectionDto tokens) implements RotationResult {
    }

    /** 재사용 판정. 폐기는 이 값이 돌아온 시점에 <b>이미 트랜잭션에 쌓여</b> 함께 커밋된다. */
    record ReuseDetected(int revokedCount) implements RotationResult {
    }
}
