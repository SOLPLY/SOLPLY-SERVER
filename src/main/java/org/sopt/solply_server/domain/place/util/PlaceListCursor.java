package org.sopt.solply_server.domain.place.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 무한 스크롤 커서. 정렬 키 복합값을 불투명 토큰으로 인코딩한다.
 * POPULAR: sortKey = 복합 점수(place_stats.popular_score), LATEST: sortKey = createdAt epochSecond(UTC).
 *
 * <p><b>v3로 올린 이유 — 커서가 좌표만으로는 부족하다는 것이 드러났다.</b> v2까지 커서는
 * "정렬 키 X, id Y 다음"이라는 <em>좌표</em>였는데, 좌표는 그것이 어느 <b>좌표계</b>에서
 * 찍힌 것인지를 말하지 않는다. 그래서 두 종류의 조용한 오답이 있었다:
 * <ol>
 *   <li><b>세대</b> — 스크롤 도중 배치가 돌면 {@code popular_score}가 통째로 갈려 좌표계가 바뀐다.
 *       다음 페이지가 이미 본 장소를 다시 주거나(중복) 안 본 장소를 건너뛴다(누락).
 *       매시 배치로 바꾸면서 이 창이 새벽 한 번에서 매시간으로 퍼졌다.</li>
 *   <li><b>필터</b> — 동네 A의 커서를 동네 B 요청에 그대로 쓰면 서버는 아무 불평 없이
 *       "동네 B에서 점수 X 아래"를 돌려준다. 요청한 적 없는 페이지가 정상 응답으로 나간다.</li>
 * </ol>
 * v3는 좌표에 <b>세대 식별자</b>와 <b>필터 지문</b>을 함께 실어 두 구멍을 막는다.
 *
 * <p><b>v2 토큰은 거부한다.</b> 받아들이면 없는 두 필드를 지어내야 하는데, 지어낸 필터 지문은
 * 어떤 요청과도 맞거나 어떤 요청과도 안 맞고 둘 다 조용한 오답이다. 운영 전이라 하위호환이
 * 필요 없으므로 v2가 v1에 했던 것과 같이 {@code INVALID_PLACE_CURSOR}로 끊는다.
 *
 * <p><b>포맷:</b> {@code v3:SORT:sortKey:placeId:generation:filterPrint}를 URL-safe base64로 감싼다.
 * 구분자 ':'와 충돌하는 필드가 없다 — {@code Double.toString}은 ':'를 만들지 않고(지수 표기
 * {@code 1.0E10}도 마찬가지), 지문은 숫자와 {@code '|'}·{@code ','}로만 이뤄진다.
 * 다만 지문은 <b>맨 뒤에서 비어 끝날 수 있어</b>({@code "1|||"}) 디코딩의 split이 후행 빈 조각을
 * 버리면 필드 수가 모자라 보인다 — {@code split(":", -1)}의 {@code -1}이 그것을 막는다.
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
 * @param sortKey     정렬 축의 값. POPULAR은 세대에 맞는 점수 컬럼의 값, LATEST는 epoch 초
 * @param generation  커서를 발급한 랭킹 세대(배치 {@code calculatedAt}의 epoch 초).
 *                    LATEST는 항상 0이다 — {@code created_at}은 배치가 만지지 않는 불변 축이라
 *                    좌표계가 갈릴 일이 없다. 값의 뜻과 강등 규칙은
 *                    {@code PlaceStatsMetaRepository} 참조
 * @param filterPrint 요청 필터의 정규형. {@link #filterPrintOf}가 만든 것이어야 한다
 */
public record PlaceListCursor(
        PlaceSortType sort, double sortKey, long placeId, long generation, String filterPrint) {

    private static final String VERSION = "v3";

    /** 토큰의 필드 수. 버전·정렬·정렬키·id·세대·지문 */
    private static final int FIELD_COUNT = 6;

    private static final String FIELD_DELIMITER = ":";

    /** 지문 안에서 축을 가르는 구분자 */
    private static final String AXIS_DELIMITER = "|";

    /** 지문 안에서 한 축의 여러 id를 가르는 구분자 */
    private static final String ID_DELIMITER = ",";

    /**
     * 요청 필터의 <b>정규형</b>을 만든다. 커서에 실려 다음 페이지 요청과 대조되며,
     * 다르면 {@code INVALID_PLACE_CURSOR}다.
     *
     * <p><b>해시가 아니라 원값인 이유:</b> 토큰이 수십 바이트 늘 뿐이고, 대신 장애 때 커서를
     * base64 디코딩하는 것만으로 "어떤 필터로 발급된 커서인가"가 그대로 읽힌다. 해시는 그 자리에서
     * 아무것도 말해주지 않는다.
     *
     * <p><b>정렬해서 잇는 이유:</b> 클라이언트가 서브 태그를 다른 순서로 보내는 것은 흔한 일인데
     * (체크박스 선택 순서 등) 지문이 갈리면 정상 스크롤이 오류로 죽는다. 같은 필터의 지문은 하나여야 한다.
     *
     * <p><b>leaf 확장 <em>전</em>의 원본 파라미터를 쓴다.</b> 확장 결과({@code leafTownIds})는
     * 동네 트리가 바뀌면 같은 요청에서도 달라지고, 사용자가 실제로 고른 것은 확장 전 값이다.
     * 지문이 물어야 하는 것은 "같은 요청인가"이지 "같은 실행 계획인가"가 아니다.
     *
     * <p>{@code null}과 빈 리스트는 같은 뜻(그 축으로 거르지 않음)이라 같은 지문을 낸다.
     */
    public static String filterPrintOf(
            Long townId, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds) {
        return String.join(AXIS_DELIMITER,
                nullToEmpty(townId),
                nullToEmpty(mainTagId),
                sortedIds(subTagAIds),
                sortedIds(subTagBIds));
    }

    public String encode() {
        String raw = String.join(FIELD_DELIMITER,
                VERSION,
                sort.name(),
                Double.toString(sortKey),
                Long.toString(placeId),
                Long.toString(generation),
                filterPrint);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static PlaceListCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            // -1: 지문이 빈 축으로 끝나면("1|||") 마지막 조각이 사라져 필드 수가 모자라 보인다
            String[] parts = raw.split(FIELD_DELIMITER, -1);
            if (parts.length != FIELD_COUNT || !VERSION.equals(parts[0])) {
                throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
            }
            return new PlaceListCursor(
                    PlaceSortType.valueOf(parts[1]),
                    Double.parseDouble(parts[2]),
                    Long.parseLong(parts[3]),
                    Long.parseLong(parts[4]),
                    parts[5]
            );
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
        }
    }

    private static String nullToEmpty(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    private static String sortedIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(ID_DELIMITER));
    }
}
