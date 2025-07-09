package org.sopt.solply_server.global.exception;

public class InvalidFileKeyException extends BusinessException {
    public InvalidFileKeyException(ErrorCode errorCode) {
        super(errorCode);
    }
}