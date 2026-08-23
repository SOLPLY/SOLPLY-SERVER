package org.sopt.solply_server.domain.place.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 무한 스크롤 커서. 정렬 키 복합값을 불투명 토큰으로 인코딩한다.
 *
 * <p><b>v5에서 정렬 키를 튜플로 일반화했다 (2026-08-16).</b> v4까지는 "sortKey 하나 + placeId"였고,
 * 그것으로 충분했던 이유는 정렬이 둘뿐이라 정렬 축이 언제나 한 컬럼이었기 때문이다. 정렬을 여섯으로
 * 늘리면서 그 전제가 깨졌다 — 평점순은 <b>(평점, 리뷰 수)</b> 2단으로 seek해야 동점 구간에서
 * 항목을 흘리지 않고, 거리순은 정렬 축(거리)만으로는 다음 페이지를 재현할 수 없어 <b>기준 좌표</b>
 * 자체를 커서가 들고 다녀야 한다. 그래서 sortKey 한 칸을 <b>정렬마다 길이가 다른 double 튜플</b>로
 * 넓혔고, 길이는 {@link PlaceSortType#keyArity()}가 정한다.
 *
 * <p>정렬별 튜플:
 * <ul>
 *   <li>POPULAR — (인기 점수)
 *   <li>LATEST — (생성일 epoch 초, UTC)
 *   <li>RATING — (평점, 리뷰 수)
 *   <li>REVIEW_COUNT — (리뷰 수) / BOOKMARK_COUNT — (북마크 수)
 *   <li>DISTANCE — (기준 위도, 기준 경도, 거리 m)
 * </ul>
 *
 * <p><b>거리순의 기준 좌표는 커서에 박제된 것이 항상 이긴다.</b> 걸으면서 페이지를 넘기는 것이
 * 정상 시나리오라 2페이지 요청의 좌표 파라미터는 1페이지와 다른 것이 당연하고, 그때마다 기준점이
 * 바뀌면 같은 장소가 페이지마다 다른 거리를 가져 중복·누락이 난다. 그래서 파라미터 불일치는
 * 오류가 아니라 <b>무시</b>다 ({@code PlaceService#listPlaces}).
 *
 * <p><b>세대는 v4에서 걷어냈다 (2026-08-07).</b> v3의 세대 필드는 "스크롤 도중 배치가 돌면
 * {@code popular_score}가 통째로 갈려 좌표계가 바뀐다"를 막으려고 있었다. 그 창이 <b>매시간</b>에
 * 걸쳐 있을 때는 값어치가 있었지만, 인기 점수 배치를 새벽 01:00 1회로 내리면서 창이 트래픽 최저
 * 시각의 수 초로 줄었다. 그 순간 스크롤 중이던 소수 사용자가 겪는 항목 흘림·중복은 수용하고,
 * 대신 세대 행 2벌·메타 레지스터·current/prev 판정·만료 오류를 통째로 걷어냈다.
 * <b>배치 주기를 다시 당긴다면 이 결정부터 되짚을 것</b> — 세대 제거의 근거가 곧 주기다.
 *
 * <p><b>필터 지문은 그대로 남는다.</b> 세대와 달리 이 구멍은 배치 주기와 무관하다 — 동네 A의
 * 커서를 동네 B 요청에 그대로 쓰면 서버는 아무 불평 없이 "동네 B에서 점수 X 아래"를 돌려주고,
 * 요청한 적 없는 페이지가 정상 응답으로 나간다.
 *
 * <p><b>v4 이하 토큰은 거부한다.</b> 받아들이면 정렬 키 자리의 해석이 달라져 어차피 오답이 되고,
 * 어느 쪽이든 오류라면 코덱 수준에서 명확히 끊는 편이 낫다. 운영 전이라 하위호환이 필요 없다.
 *
 * <p><b>포맷:</b> {@code v5:SORT:k1,k2,...:placeId:filterPrint}를 URL-safe base64로 감싼다.
 * 구분자 ':'와 충돌하는 필드가 없다 — {@code Double.toString}은 ':'를 만들지 않고(지수 표기
 * {@code 1.0E10}도 마찬가지), 지문은 숫자와 {@code '|'}·{@code ','}로만 이뤄진다.
 * 키 구분자 {@code ','}가 지문 안에서도 쓰이지만 <b>둘은 다른 필드</b>라 섞이지 않는다.
 * 다만 지문은 <b>맨 뒤에서 비어 끝날 수 있어</b>({@code "1|||"}) 디코딩의 split이 후행 빈 조각을
 * 버리면 필드 수가 모자라 보인다 — {@code split(":", -1)}의 {@code -1}이 그것을 막는다.
 *
 * <p><b>정밀도:</b> 유효자릿수 약 15자리는 double 고유의 한계이고, popular_score는
 * DECIMAL(18,6) — 즉 18자리라 double보다 넓다. 그래서 좁은 쪽에 담는 셈이지만 문제되지 않는다.
 * 여기서 "정확하다"고 말할 수 있는 것은 값의 표현이 아니라 <b>왕복과 순서</b>뿐이다
 * ({@code 1234.567891}부터가 이미 이진 double로 정확히 표현되지 않는다). 코드가 의존하는 것도
 * 그 둘뿐이다. 서로 다른 두 DECIMAL 값이 같은 double로 뭉개지더라도 id 타이브레이크가
 * 전순서를 유지하므로 페이징은 깨지지 않는다 — 뭉개진 두 장소의 상대 순위만 id 순으로 정해진다.
 * 카운트 축(리뷰 수·북마크 수)은 INT라 double에 <b>정확히</b> 담긴다.
 *
 * <p>NaN/Infinity에 별도 방어를 두지 않는 근거는 <b>타입 수준의 불가능성</b>이다. 점수 산출
 * SQL의 {@code SUM(POW(0.5, ...))}은 MySQL에서 DOUBLE로 계산되므로 "집계라서 유한하다"는 보장이
 * 되지 못한다. 막아주는 것은 {@code popular_score DECIMAL(18,6) NOT NULL} 컬럼과, 그것을 받는
 * BigDecimal이 NaN/Infinity를 표현조차 못 한다는 사실이다. 설령 들어와도 코덱 왕복 자체는
 * 성립하고, 정렬·커서 비교가 무너지는 것은 코덱이 아니라 점수 산출 쪽 버그이므로 여기서 삼키면
 * 오히려 은폐가 된다.
 *
 * @param sortKeys    정렬 축의 값들. 길이는 반드시 {@link PlaceSortType#keyArity()}와 같다
 * @param filterPrint 요청 필터의 정규형. {@link #filterPrintOf}가 만든 것이어야 한다
 */
public record PlaceListCursor(
        PlaceSortType sort, List<Double> sortKeys, long placeId, String filterPrint) {

    private static final String VERSION = "v5";

    /** 토큰의 필드 수. 버전·정렬·정렬키 튜플·id·지문 */
    private static final int FIELD_COUNT = 5;

    private static final String FIELD_DELIMITER = ":";

    /** 정렬 키 튜플 안에서 키를 가르는 구분자 */
    private static final String KEY_DELIMITER = ",";

    /** 지문 안에서 축을 가르는 구분자 */
    private static final String AXIS_DELIMITER = "|";

    /** 지문 안에서 한 축의 여러 id를 가르는 구분자 */
    private static final String ID_DELIMITER = ",";

    /**
     * <b>튜플 길이는 정렬이 정한다.</b> 어긋난 커서를 만들면 그 자리에서 터진다 — 발급부의 실수는
     * 500으로 드러나고, 클라이언트가 보낸 토큰의 어긋남은 {@link #decode}가 이 예외를 받아
     * {@code INVALID_PLACE_CURSOR}로 바꾼다.
     */
    public PlaceListCursor {
        if (sortKeys == null || sortKeys.size() != sort.keyArity()) {
            throw new IllegalArgumentException(
                    "정렬 " + sort + "의 커서 키는 " + sort.keyArity() + "개여야 한다: " + sortKeys);
        }
        sortKeys = List.copyOf(sortKeys);
    }

    /** 정렬 키 튜플의 {@code index}번째 값. 범위는 생성자가 이미 보장한다 */
    public double key(int index) {
        return sortKeys.get(index);
    }

    /**
     * 요청 필터의 <b>정규형</b>을 만든다. 커서에 실려 다음 페이지 요청과 대조되며,
     * 다르면 {@code INVALID_PLACE_CURSOR}다.
     *
     * <p><b>거리순의 기준 좌표는 지문에 넣지 않는다.</b> 지문이 묻는 것은 "같은 결과 집합인가"인데
     * 좌표는 집합이 아니라 그 집합의 <em>순서</em>만 정하고, 페이지를 넘기는 동안 사용자가 움직이는
     * 것이 정상이라 좌표를 지문에 넣으면 정상 스크롤이 통째로 오류가 된다.
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
                sortKeys.stream().map(String::valueOf).collect(Collectors.joining(KEY_DELIMITER)),
                Long.toString(placeId),
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
            // 키 개수가 정렬과 어긋나면 생성자가 IllegalArgumentException을 던지고 아래가 받는다.
            // 정렬 이름이 낯설 때(valueOf)도 같은 예외 계열이라 한 자리에서 끊긴다.
            return new PlaceListCursor(
                    PlaceSortType.valueOf(parts[1]),
                    parseKeys(parts[2]),
                    Long.parseLong(parts[3]),
                    parts[4]
            );
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PLACE_CURSOR);
        }
    }

    private static List<Double> parseKeys(String field) {
        String[] raw = field.split(KEY_DELIMITER, -1);
        List<Double> keys = new ArrayList<>(raw.length);
        for (String value : raw) {
            keys.add(Double.parseDouble(value));
        }
        return keys;
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
