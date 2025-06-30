package org.sopt.solply_server.global.exception;

public class EntityNotFoundException extends BusinessException {
    public EntityNotFoundException(String entityName, Object id) {
        super(ErrorCode.NOT_FOUND_ENTITY, entityName + " with ID " + id + " not found");
    }
}