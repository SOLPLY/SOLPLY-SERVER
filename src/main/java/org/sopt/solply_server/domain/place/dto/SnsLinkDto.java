package org.sopt.solply_server.domain.place.dto;

import java.util.List;
import java.util.Map;
import org.sopt.solply_server.domain.place.entity.SnsPlatform;

public record SnsLinkDto(
        SnsPlatform snsPlatform,
        String url
) {

    public static List<SnsLinkDto> toList(Map<SnsPlatform, String> snsLinks) {
        if (snsLinks == null || snsLinks.isEmpty()) {
            return List.of();
        }

        return snsLinks.entrySet().stream()
                .map(entry -> new SnsLinkDto(entry.getKey(), entry.getValue()))
                .toList();
    }
}
