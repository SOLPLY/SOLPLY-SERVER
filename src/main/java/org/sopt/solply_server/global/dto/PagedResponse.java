package org.sopt.solply_server.global.dto;

import java.util.List;

public record PagedResponse<T> (
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
}