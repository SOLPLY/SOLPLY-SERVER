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
 * 구분자와 충돌하지 않는다(지수 표기 {@code 1.0E10}도 마찬가지).
 *
 * <p><b>정밀도:</b> 유효자릿수 약 15자리는 double 고유의 한계이고, popular_score는
 * DECIMAL(18,6) — 즉 18자리라 double보다 넓다. 그래서 좁은 쪽에 담는 셈이지만 문제되지 않는다.
 * 여기서 "정확하다"고 말할 수 있는 것은 값의 표현이 아니라 <b>왕복과 순서</b>뿐이다
 * ({@code 1234.567891}부터가 이미 이진 double로 정확히 표현되지 않는다). 코드가 의존하는 것도
 * 그 둘뿐이다. 서로 다른 두 DECIMAL 값이 같은 double로 뭉개지더라도 id 타이브레이크가
 * 전순서를 유지하므로 페이징은 깨지지 않는다 — 뭉개진 두 장소의 상대 순위만 id 순으로 정해진다.
 *
 * <p>NaN/Infinity에 별도 방어를 두지 않는 근거는 <b>타입 수준의 불가능성</b>이다. 점수 산출
 * SQL의 {@code SUM(POW(0.5, ...))}은 MySQL에서 DOUBLE로 계산되므로 "집계라서 유한하다"는 보장이
 * 되지 못한다. 막아주는 것은 {@code popular_score DECIMAL(18,6) NOT NULL} 컬럼과, 그것을 받는
 * BigDecimal이 NaN/Infinity를 표현조차 못 한다는 사실이다. 설령 들어와도 코덱 왕복 자체는
 * 성립하고, 정렬·커서 비교가 무너지는 것은 코덱이 아니라 점수 산출 쪽 버그이므로 여기서 삼키면
 * 오히려 은폐가 된다.
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
