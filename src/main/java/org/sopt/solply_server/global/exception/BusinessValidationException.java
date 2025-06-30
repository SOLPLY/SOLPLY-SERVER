package org.sopt.solply_server.global.exception;

public class BusinessValidationException extends BusinessException {
    public BusinessValidationException(ErrorCode errorCode) {
        super(errorCode);
    }
}
