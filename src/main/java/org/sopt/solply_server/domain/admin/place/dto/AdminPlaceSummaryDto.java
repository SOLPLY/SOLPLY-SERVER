package org.sopt.solply_server.domain.admin.place.dto;

public record AdminPlaceSummaryDto(
    Long id,
    String placeName,
    String townName,
    String mainTagName
) {
    public static AdminPlaceSummaryDto of(Long id, String placeName, String townName, String mainTagName) {
            return new AdminPlaceSummaryDto(id, placeName, townName, mainTagName);
    }
}
