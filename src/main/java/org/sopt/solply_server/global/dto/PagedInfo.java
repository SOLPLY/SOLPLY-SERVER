package org.sopt.solply_server.global.dto;


public record PagedInfo (
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
}