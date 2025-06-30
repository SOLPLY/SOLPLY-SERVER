package org.sopt.solply_server.global.exception;

public class UnauthorizedException extends BusinessException {
    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }

}