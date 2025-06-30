package org.sopt.solply_server.global.exception;

public class JwtTokenException extends BusinessException {
    public JwtTokenException(ErrorCode errorCode) {
        super(errorCode);
    }
}