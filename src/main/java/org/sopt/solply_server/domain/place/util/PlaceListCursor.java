package org.sopt.solply_server.domain.place.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 무한 스크롤 커서. 정렬 키 복합값을 불투명 토큰으로 인코딩한다.
 * POPULAR: sortKey = 복합 점수(place_stats.popular_score), LATEST: sortKey = createdAt epochSecond(UTC).
 *
 * <p><b>v2로 올린 이유:</b> 인기순 정렬 키가 북마크 수(정수)에서 복합 점수(실수)로 바뀌었다.
 * 운영 전이라 하위호환이 필요 없으므로 v1 토큰은 INVALID_PLACE_CURSOR로 거부한다.
 *
 * <p>{@code Double.toString}/{@code Double.parseDouble}은 왕복이 보장되며 ':'를 만들지 않아
 * 구분자와 충돌하지 않는다(지수 표기 {@code 1.0E10}도 마찬가지). DECIMAL(18,6)을 double에 담으므로
 * 유효자릿수는 약 15자리까지다 — 현실 점수 범위(최대 수만 점)에서는 정확하고, 커서 비교의 동점
 * 판정도 왕복한 같은 값끼리 비교하므로 성립한다.
 *
 * <p>NaN/Infinity는 점수 계산(유한한 DECIMAL 집계)에서 나올 수 없어 별도 방어를 두지 않는다.
 * 설령 들어와도 {@code Double.toString}/{@code parseDouble} 왕복 자체는 성립하고, 정렬·커서
 * 비교가 무너지는 것은 코덱이 아니라 점수 산출 쪽 버그이므로 여기서 삼키면 오히려 은폐가 된다.
 *
 * <p>정렬 기준이 파생값이라 스냅샷 갱신 시 페이지 간 소량 누락이 가능하며, 이는 수용된 트레이드오프다.
 */
public record PlaceListCursor(PlaceSortType sort, double sortKey, long placeId) {

    private static final String VERSION = "v2";

    public String encode() {
        String raw = String.join(":",
                VERSION, sort.name(), Double.toString(sortKey), Long.toString(placeId));
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
                    Double.parseDouble(parts[2]),
                    Long.parseLong(parts[3])
            );
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
        }
    }
}
