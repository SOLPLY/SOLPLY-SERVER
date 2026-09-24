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

    /** 범위 표현이 검증 대상이 아닌 테스트가 쓰는 값. 동네 경로의 표현 형태다 */
    private static final String VERSION = "T10@7";

    private static PlaceListCursor cursor(double sortKey, long placeId) {
        return new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(sortKey), placeId, FILTER_PRINT, VERSION);
    }

    @Test
    void 인코딩_후_디코딩하면_원본과_같다() {
        PlaceListCursor cursor = cursor(1234L, 56L);
        assertThat(PlaceListCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    /**
     * 다섯 필드가 <b>따로</b> 왕복하는지 본다. record 전체 비교만 하면 정렬키와 id를 뒤바꾸거나
     * 지문을 다른 필드로 덮는 회귀가 통과할 수 있어(전부 원본에서 왔으므로) 값으로 하나씩 문다.
     */
    @Test
    void 정렬축_정렬키_id_지문_회차가_각각_왕복한다() {
        PlaceListCursor decoded = PlaceListCursor.decode(cursor(9.5, 3L).encode());

        assertThat(decoded.sort()).isEqualTo(PlaceSortType.POPULAR);
        assertThat(decoded.key(0)).isEqualTo(9.5);
        assertThat(decoded.placeId()).isEqualTo(3L);
        assertThat(decoded.filterPrint()).isEqualTo(FILTER_PRINT);
        assertThat(decoded.scope()).isEqualTo(VERSION);
    }

    /**
     * <b>회차 버전은 정렬 키가 아니라 별도 필드다 (v6).</b> 두 자리가 섞이면 커서가 다른 회차의
     * 스냅샷을 가리키거나 정렬 키를 회차로 읽어, 다음 페이지가 조용히 다른 좌표계에서 재개된다.
     *
     * <p>버전 하나만 다른 두 커서가 <b>서로 다른 토큰</b>이라는 것까지 못 박는다 — 토큰 조립에서
     * 버전을 빠뜨리면 두 회차의 커서가 같은 문자열이 되어 회차 고정 장치가 통째로 무력해진다.
     */
    @Test
    void 회차_버전만_다른_커서는_다른_토큰이고_각각_그_회차로_왕복한다() {
        PlaceListCursor first = new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(9.5), 3L, FILTER_PRINT, "T10@100");
        PlaceListCursor second = new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(9.5), 3L, FILTER_PRINT, "T10@200");

        assertThat(first.encode()).isNotEqualTo(second.encode());
        assertThat(PlaceListCursor.decode(first.encode()).scope()).isEqualTo("T10@100");
        assertThat(PlaceListCursor.decode(second.encode()).scope()).isEqualTo("T10@200");
    }

    /**
     * <b>범위가 달라도 다른 토큰이다.</b> 번호만 실었다면 "동네 10 하나"와 "동네 10·11"이 같은
     * 문자열이 되어, 어드민이 하위 동네를 켠 뒤에도 옛 커서가 통과했을 것이다 — 탐색 대상이
     * 조용히 넓어지는데 응답은 200이라 클라이언트가 알 방법이 없다.
     */
    @Test
    void 번호가_같아도_동네_집합이_다르면_다른_토큰이다() {
        PlaceListCursor narrow = new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(9.5), 3L, FILTER_PRINT, "T10@7");
        PlaceListCursor wide = new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(9.5), 3L, FILTER_PRINT, "T10@7,11@7");

        assertThat(narrow.encode()).isNotEqualTo(wide.encode());
    }

    /**
     * 거리순·전역 경로의 표현과 동네 경로의 표현은 <b>우연히 같아질 수 없다</b> — 머리글자가
     * 다르다. 같아지면 서로의 커서가 만료 판정을 통과해 다른 좌표계에서 해석된다.
     */
    @Test
    void 전역_표현과_동네_표현은_섞이지_않는다() {
        PlaceListCursor global = new PlaceListCursor(
                PlaceSortType.DISTANCE, List.of(37.5, 127.0, 10.0), 3L, FILTER_PRINT,
                PlaceListCursor.globalScope(41L));

        assertThat(global.scope()).isEqualTo("G41");
        assertThat(global.globalVersionOrElse(-1L)).isEqualTo(41L);
        assertThat(new PlaceListCursor(
                PlaceSortType.POPULAR, List.of(9.5), 3L, FILTER_PRINT, "T41@1")
                .globalVersionOrElse(-1L)).isEqualTo(-1L);
    }

    /** 범위 표현이 빈 토큰은 만료 판정의 근거가 없다 — 잘못된 커서로 끊는다. */
    @Test
    void 범위_표현이_비면_잘못된_커서다() {
        String raw = "v8:POPULAR:9.5:3:" + FILTER_PRINT + ":";
        String token = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(token))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * <b>v7 토큰은 계속 통한다.</b> 동네 경로에 새 포맷이 필요해졌다는 이유로 진행 중인 거리순
     * 스크롤을 일괄 만료시키지 않는다 — 거리순은 이번 변경의 범위 밖이다.
     *
     * <p>전역 경로가 발급하는 토큰이 <b>바이트 단위로</b> 옛 것과 같다는 것까지 함께 못 박는다.
     * 왕복만 보면 포맷을 v8로 올려 놓고도 그린이 되는데, 그러면 배포 순간 옛 토큰이 전부 끊긴다.
     */
    @Test
    void v7_전역_토큰은_그대로_통한다() {
        String raw = "v7:POPULAR:9.5:3:" + FILTER_PRINT + ":41";
        String token = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        PlaceListCursor decoded = PlaceListCursor.decode(token);

        assertThat(decoded.globalVersionOrElse(-1L)).isEqualTo(41L);
        assertThat(decoded.encode()).isEqualTo(token);
    }

    /** 동네 표현을 v7 칸에 실은 토큰은 해석할 규칙이 없다 — 형식 단계에서 끊는다. */
    @Test
    void 포맷과_범위_표현이_어긋나면_거부한다() {
        assertThatThrownBy(() -> PlaceListCursor.decode(
                token("v7:POPULAR:9.5:3:" + FILTER_PRINT + ":T10@5")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PlaceListCursor.decode(
                token("v8:POPULAR:9.5:3:" + FILTER_PRINT + ":41")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PlaceListCursor.decode(
                token("v9:POPULAR:9.5:3:" + FILTER_PRINT + ":T10@5")))
                .isInstanceOf(BusinessException.class);
    }

    private static String token(String raw) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void 형식이_잘못된_토큰은_예외를_던진다() {
        assertThatThrownBy(() -> PlaceListCursor.decode("not-a-cursor"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void base64이지만_필드가_모자란_토큰은_예외를_던진다() {
        // 버전 문자열은 맞고 회차 필드만 빠진 형태 — 필드 수 검사가 유일한 방벽인 자리다
        String bogus = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v6:POPULAR:123:4:10|20|1,2|3".getBytes(StandardCharsets.UTF_8));
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

    /**
     * <b>v5는 회차 버전 한 칸만 모자라다.</b> 필드 수가 하나 적어 형식 검사에 걸리지만, 만약
     * 그 칸을 <em>지어내서</em> 받아 준다면 지어낸 회차는 캐시가 든 회차와 달라 전부 만료가 되거나,
     * 우연히 맞아 엉뚱한 좌표계에서 재개돼(항목 누락·중복) 어느 쪽이든 조용한 오답이다. 운영 전이라 하위호환이 필요 없는
     * 만큼 코덱에서 끊는다.
     */
    @Test
    void v5_토큰은_거부한다() {
        String v5Token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v5:POPULAR:100.0:5:10|20|1,2|3".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(v5Token))
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
                PlaceSortType.RATING, List.of(4.5, 12.0), 7L, FILTER_PRINT, VERSION);

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
                PlaceSortType.DISTANCE, List.of(37.5665, -126.978, 1234.5), 9L, FILTER_PRINT,
                VERSION);

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
        String tooMany = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v6:POPULAR:1.0,2.0:5:10|20|1,2|3:100".getBytes(StandardCharsets.UTF_8));
        String tooFew = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v6:DISTANCE:37.5,127.0:5:10|20|1,2|3:100".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PlaceListCursor.decode(tooMany))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PlaceListCursor.decode(tooFew))
                .isInstanceOf(BusinessException.class);
    }

    /** 발급부의 실수는 오류로 드러나야 한다 — 조용히 잘라 담으면 커서가 다른 위치를 가리킨다 */
    @Test
    void 정렬과_키_개수가_어긋나면_커서를_만들_수_없다() {
        assertThatThrownBy(() -> new PlaceListCursor(
                PlaceSortType.RATING, List.of(4.5), 1L, FILTER_PRINT, VERSION))
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
                new PlaceListCursor(PlaceSortType.LATEST, List.of(100.0), 5L, print, VERSION);

        assertThat(PlaceListCursor.decode(cursor.encode())).isEqualTo(cursor);
    }
}
