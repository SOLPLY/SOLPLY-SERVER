package org.sopt.solply_server.global.exception;

public class EntityNotFoundException extends BusinessException {
    public EntityNotFoundException(ErrorCode errorCode) {super(errorCode);}
}