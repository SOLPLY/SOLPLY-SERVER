package org.sopt.solply_server.domain.place.util;

import java.util.Collection;

/**
 * 태그 id 집합 ↔ {@code place_stats.tag_bitmask}의 변환. <b>비트 자리 = tag id</b>다.
 *
 * <p><b>의미론은 요청 하나가 마스크 하나다.</b> 목록 필터는 "설정한 태그를 <em>전부</em> 가진
 * 장소"(AND-all)이므로 메인·서브A·서브B를 구분하지 않고 한 마스크로 합치고, 술어는
 * {@code (tag_bitmask & :mask) = :mask} 하나다. 그룹을 나눌 이유가 없는 것은 그룹 안도 AND이기
 * 때문이다 — {@link #required}가 그 합치기를 한 자리에서 한다.
 *
 * <p><b>⚠️ 쓸 수 있는 비트 자리는 0..62다.</b> {@code tag_bitmask}가 부호 있는 BIGINT라 63번
 * 자리는 부호 비트이고, 자바에서도 {@code 1L << 63}이 {@code Long.MIN_VALUE}다. 그 위는 자바의
 * 시프트가 64로 나눈 나머지를 쓰기 때문에({@code 1L << 64 == 1L}) <b>조용히 다른 태그의 자리를
 * 가리킨다</b> — 틀린 결과가 예외 없이 나가는 유일한 경로라 여기서 끊는다.
 *
 * <p>상한을 넘기지 않는 책임은 태그를 <em>만드는</em> 쪽에 있다
 * ({@code AdminTagService#createTag}가 그 자리에서 거부한다). 여기서 예외가 뜬다는 것은 그 가드를
 * 지나친 태그가 이미 저장돼 있다는 뜻이므로, 잡아서 넘기지 말고 그대로 터뜨릴 것.
 */
public final class TagBitmask {

    /** 마지막으로 쓸 수 있는 비트 자리 = 허용되는 최대 tag id. */
    public static final long MAX_TAG_ID = 62L;

    private TagBitmask() {}

    /** 태그 하나의 마스크. */
    public static long of(long tagId) {
        return 1L << checked(tagId);
    }

    /**
     * 요청 하나가 요구하는 태그를 전부 담은 마스크. 술어 {@code (tag_bitmask & mask) = mask}와
     * 짝이라 <b>합집합이 곧 "이걸 전부"</b>가 된다.
     *
     * <p>메인 태그가 없으면 서브 태그 조건은 통째로 버린다 — 목록 경로와 북마크 검색
     * ({@code PlaceTagMatcher})이 공유하는 규칙이고, 여기서 갈리면 같은 요청이 경로마다 다른 답을 낸다.
     *
     * @return 조건이 없으면 0 — 호출부가 술어를 붙이지 않는 신호로 쓴다
     */
    public static long required(
            Long mainTagId, Collection<Long> subTagAIds, Collection<Long> subTagBIds) {
        if (mainTagId == null) {
            return 0L;
        }
        return of(mainTagId) | ofAll(subTagAIds) | ofAll(subTagBIds);
    }

    /**
     * 태그 여러 개의 마스크 = 비트 합집합. 계산은 예전({@code ofAny})과 같지만 <b>읽는 술어가
     * 달라져 뜻이 뒤집혔다</b> — {@code != 0}이면 "하나라도", {@code = mask}면 "전부"다.
     *
     * @return 비어 있거나 null이면 0
     */
    public static long ofAll(Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return 0L;
        }
        long mask = 0L;
        for (Long tagId : tagIds) {
            mask |= of(tagId);
        }
        return mask;
    }

    private static long checked(long tagId) {
        if (tagId < 0 || tagId > MAX_TAG_ID) {
            throw new IllegalArgumentException(
                    "태그 id가 비트마스크 상한을 벗어났다 - tagId=" + tagId + ", 상한=" + MAX_TAG_ID);
        }
        return tagId;
    }
}
