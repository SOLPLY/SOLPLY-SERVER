package org.sopt.solply_server.domain.place.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.global.exception.BusinessException;

class PlaceListCursorTest {

    /** 필터 지문이 검증 대상이 아닌 테스트가 쓰는 값. 네 축이 전부 채워진 형태다. */
    private static final String FILTER_PRINT = "10|20|1,2|3";

    private static PlaceListCursor cursor(double sortKey, long placeId) {
        return new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(sortKey), placeId, FILTER_PRINT);
    }

    @Test
    void 인코딩_후_디코딩하면_원본과_같다() {
        PlaceListCursor cursor = cursor(1234L, 56L);
        assertThat(PlaceListCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    /**
     * 네 필드가 <b>따로</b> 왕복하는지 본다. record 전체 비교만 하면 정렬키와 id를 뒤바꾸거나
     * 지문을 다른 필드로 덮는 회귀가 통과할 수 있어(전부 원본에서 왔으므로) 값으로 하나씩 문다.
     */
    @Test
    void 정렬축_정렬키_id_지문이_각각_왕복한다() {
        PlaceListCursor decoded = PlaceListCursor.decode(cursor(9.5, 3L).encode());

        assertThat(decoded.sort()).isEqualTo(PlaceSortType.POPULAR);
        assertThat(decoded.key(0)).isEqualTo(9.5);
        assertThat(decoded.placeId()).isEqualTo(3L);
        assertThat(decoded.filterPrint()).isEqualTo(FILTER_PRINT);
    }

    @Test
    void 형식이_잘못된_토큰은_예외를_던진다() {
        assertThatThrownBy(() -> PlaceListCursor.decode("not-a-cursor"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void base64이지만_필드가_모자란_토큰은_예외를_던진다() {
        String bogus = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v5:POPULAR:123:4".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PlaceListCursor.decode(bogus))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 실수_점수를_왕복해도_값이_보존된다() {
        PlaceListCursor cursor = cursor(1234.567891, 56L);

        PlaceListCursor decoded = PlaceListCursor.decode(cursor.encode());

        assertThat(decoded.key(0)).isEqualTo(1234.567891);
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void 음수_점수도_왕복한다() {
        PlaceListCursor cursor = cursor(-42.5, 7L);

        assertThat(PlaceListCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void 지수_표기가_나오는_값도_비트까지_왕복한다() {
        // Double.toString은 1e7 이상/1e-3 미만에서 지수 표기(1.0E10)를 낸다 — ':'가 없어 구분자와 무충돌
        double[] values = {1.0E10, 1.0E-9, 0.0, -0.0, Double.MAX_VALUE, Double.MIN_VALUE};

        for (double value : values) {
            PlaceListCursor cursor = cursor(value, 1L);
            PlaceListCursor decoded = PlaceListCursor.decode(cursor.encode());

            // isEqualTo는 == 의미라 -0.0 == 0.0이 참이다. 그래서 부호를 죽이는 변이를 심어도
            // 값 비교로는 통과해버린다. 코덱이 실제로 약속하는 계약은 "toString 왕복은 비트 보존"이니
            // 테스트도 비트로 말한다 — 나머지 값은 ==가 이미 비트 정확이라, 이 단언이 더 세지는
            // 지점은 정확히 ±0 케이스다.
            assertThat(Double.doubleToRawLongBits(decoded.key(0)))
                    .isEqualTo(Double.doubleToRawLongBits(value));
        }
    }

    @Test
    void v1_토큰은_거부한다() {
        String v1Token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1:POPULAR:100:5".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(v1Token))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * v2는 필드가 4개뿐이라 필터 지문이 없다. 받아들이면 지문을 <b>지어내야</b> 하는데, 지어낸
     * 지문은 어떤 요청과도 맞거나 어떤 요청과도 안 맞고 둘 다 조용한 오답이다.
     */
    @Test
    void v2_토큰은_거부한다() {
        String v2Token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v2:POPULAR:100.0:5".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(v2Token))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * <b>v3는 필드 수가 v4와 하나 차이라 가장 위험하다.</b> 세대(5번째)가 지문 자리로 밀려
     * {@code "1754000000"}이 지문으로 읽히는데, 버전 문자열 검사가 없으면 그것이 <em>정상 커서로
     * 디코딩</em>돼 상위의 지문 대조까지 내려간다. 거기서 어차피 오류가 나지만, 그때는
     * "필터가 다르다"는 엉뚱한 진단이 붙는다 — 코덱에서 끊어야 원인이 남는다.
     */
    @Test
    void v3_토큰은_거부한다() {
        String v3Token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v3:POPULAR:100.0:5:1754000000:10|20|1,2|3"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(v3Token))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * <b>v4는 필드 수가 v5와 같아 가장 위험하다.</b> 정렬 키 자리가 "값 하나"에서 "튜플"로 바뀌었을
     * 뿐이라 v4 토큰은 키가 하나인 정렬(POPULAR·LATEST 등)에서 <em>형식상 멀쩡히</em> 디코딩된다.
     * 버전 문자열 검사가 유일한 방벽이고, 그것이 없으면 옛 클라이언트의 커서가 조용히 통과한다.
     */
    @Test
    void v4_토큰은_거부한다() {
        String v4Token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v4:POPULAR:100.0:5:10|20|1,2|3".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(v4Token))
                .isInstanceOf(BusinessException.class);
    }

    // === 정렬 키 튜플 ===

    /**
     * 평점순 커서는 (평점, 리뷰 수) 두 칸이다. 두 값이 <b>순서까지</b> 왕복해야 seek이 동점 구간
     * 한가운데서 재개된다 — 자리가 뒤바뀌면 리뷰 수를 평점으로 읽어 페이지가 통째로 어긋난다.
     */
    @Test
    void 평점순_커서는_두_키를_순서대로_왕복한다() {
        PlaceListCursor cursor = new PlaceListCursor(
                PlaceSortType.RATING, List.of(4.5, 12.0), 7L, FILTER_PRINT);

        PlaceListCursor decoded = PlaceListCursor.decode(cursor.encode());

        assertThat(decoded.key(0)).isEqualTo(4.5);
        assertThat(decoded.key(1)).isEqualTo(12.0);
        assertThat(decoded).isEqualTo(cursor);
    }

    /**
     * 거리순 커서는 기준 좌표까지 싣는다 — 그것이 다음 페이지의 좌표계다. 음수 경도(서반구)를
     * 세우는 것은 부호가 살아 돌아오는지 함께 보기 위해서다.
     */
    @Test
    void 거리순_커서는_기준_좌표와_거리를_왕복한다() {
        PlaceListCursor cursor = new PlaceListCursor(
                PlaceSortType.DISTANCE, List.of(37.5665, -126.978, 1234.5), 9L, FILTER_PRINT);

        PlaceListCursor decoded = PlaceListCursor.decode(cursor.encode());

        assertThat(decoded.sortKeys()).containsExactly(37.5665, -126.978, 1234.5);
        assertThat(decoded.placeId()).isEqualTo(9L);
    }

    /**
     * <b>키 개수는 정렬이 정한다.</b> 손으로 지어낸 토큰이 키를 더하거나 빼면 그 정렬의 seek 조건에
     * 넣을 값이 모자라거나 남는다 — 어느 쪽이든 조용히 진행할 수 없으므로 코덱에서 끊는다.
     */
    @Test
    void 정렬과_키_개수가_어긋난_토큰은_거부한다() {
        String tooMany = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v5:POPULAR:1.0,2.0:5:10|20|1,2|3".getBytes(StandardCharsets.UTF_8));
        String tooFew = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v5:DISTANCE:37.5,127.0:5:10|20|1,2|3".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(tooMany))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PlaceListCursor.decode(tooFew))
                .isInstanceOf(BusinessException.class);
    }

    /** 발급부의 실수는 오류로 드러나야 한다 — 조용히 잘라 담으면 커서가 다른 위치를 가리킨다 */
    @Test
    void 정렬과_키_개수가_어긋나면_커서를_만들_수_없다() {
        assertThatThrownBy(() -> new PlaceListCursor(
                PlaceSortType.RATING, List.of(4.5), 1L, FILTER_PRINT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // === 필터 지문 ===

    /**
     * <b>같은 필터의 지문은 하나여야 한다.</b> 클라이언트가 서브 태그를 다른 순서로 보내는 것은
     * 흔한 일인데(체크박스 선택 순서 등), 지문이 갈리면 정상 스크롤이 INVALID_PLACE_CURSOR로 죽는다.
     * 정렬해서 지문을 만드는 이유가 이것이다.
     */
    @Test
    void 서브_태그_순서가_달라도_같은_지문이다() {
        String print1 = PlaceListCursor.filterPrintOf(
                1L, 2L, List.of(30L, 10L, 20L), List.of(5L, 4L));
        String print2 = PlaceListCursor.filterPrintOf(
                1L, 2L, List.of(10L, 20L, 30L), List.of(4L, 5L));

        assertThat(print1).isEqualTo(print2);
    }

    /**
     * 필터 축이 <b>하나라도 다르면</b> 지문이 달라야 한다. 네 축을 한 번에 세우지 않고 축마다
     * 하나씩 흔드는 이유는, 어떤 축이 지문에서 통째로 빠져도 "일부는 다르니까" 통과하는 픽스처를
     * 피하기 위해서다.
     */
    @Test
    void 필터_축이_하나라도_다르면_지문이_다르다() {
        String base = PlaceListCursor.filterPrintOf(1L, 2L, List.of(10L), List.of(20L));

        assertThat(PlaceListCursor.filterPrintOf(9L, 2L, List.of(10L), List.of(20L)))
                .isNotEqualTo(base);
        assertThat(PlaceListCursor.filterPrintOf(1L, 9L, List.of(10L), List.of(20L)))
                .isNotEqualTo(base);
        assertThat(PlaceListCursor.filterPrintOf(1L, 2L, List.of(99L), List.of(20L)))
                .isNotEqualTo(base);
        assertThat(PlaceListCursor.filterPrintOf(1L, 2L, List.of(10L), List.of(99L)))
                .isNotEqualTo(base);
    }

    /**
     * 없는 축은 빈 문자열이고, {@code null}과 빈 리스트는 <b>같은 뜻</b>이다 — 둘 다 "이 축으로
     * 거르지 않는다"이므로 지문이 갈리면 안 된다. 스프링이 쿼리 파라미터 부재를 null로도 빈
     * 리스트로도 넘길 수 있어 실제로 밟는 경로다.
     */
    @Test
    void 없는_축은_null이든_빈_리스트든_같은_지문이다() {
        String withNulls = PlaceListCursor.filterPrintOf(1L, null, null, null);
        String withEmpty = PlaceListCursor.filterPrintOf(1L, null, List.of(), List.of());

        assertThat(withNulls).isEqualTo(withEmpty);
        assertThat(withNulls).isEqualTo("1|||");
    }

    /**
     * 지문은 커서 토큰의 <b>마지막</b> 필드다. 축이 전부 비면 {@code "1|||"}처럼 끝이 구분자로
     * 끝나는데, {@code String.split}이 기본으로 <b>후행 빈 문자열을 버리는</b> 성질과 겹치면
     * 필드 수가 모자라 보여 정상 커서가 거부된다. 실제로 밟는 경로라 왕복으로 못 박는다.
     */
    @Test
    void 지문의_끝이_비어_있어도_왕복한다() {
        String print = PlaceListCursor.filterPrintOf(1L, null, null, null);
        PlaceListCursor cursor =
                new PlaceListCursor(PlaceSortType.LATEST, List.of(100.0), 5L, print);

        assertThat(PlaceListCursor.decode(cursor.encode())).isEqualTo(cursor);
    }
}
