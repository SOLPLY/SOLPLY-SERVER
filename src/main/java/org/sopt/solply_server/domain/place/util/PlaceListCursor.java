package org.sopt.solply_server.domain.place.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 무한 스크롤 커서. 정렬 키 복합값을 불투명 토큰으로 인코딩한다.
 * POPULAR: sortKey = bookmarkCount, LATEST: sortKey = createdAt epochSecond(UTC).
 * 정렬 기준이 파생값(북마크 수)이라 스냅샷 갱신 시 페이지 간 소량 누락이 가능하며, 이는 수용된 트레이드오프다.
 */
public record PlaceListCursor(PlaceSortType sort, long sortKey, long placeId) {

    private static final String VERSION = "v1";

    public String encode() {
        String raw = String.join(":", VERSION, sort.name(), Long.toString(sortKey), Long.toString(placeId));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static PlaceListCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
            }
            return new PlaceListCursor(
                    PlaceSortType.valueOf(parts[1]),
                    Long.parseLong(parts[2]),
                    Long.parseLong(parts[3])
            );
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
        }
    }
}
