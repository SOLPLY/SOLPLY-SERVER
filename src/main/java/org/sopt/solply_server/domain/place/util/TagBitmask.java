package org.sopt.solply_server.domain.place.util;

import java.util.Collection;

/**
 * 태그 id 집합 ↔ {@code place_stats.tag_bitmask}의 변환. <b>비트 자리 = tag id</b>다.
 *
 * <p><b>의미론은 그룹 하나가 마스크 하나다.</b> 목록 필터는 "메인 1개 AND (옵션A 중 하나) AND
 * (옵션B 중 하나)"이므로, 그룹별로 마스크를 만들고 각각 {@code (tag_bitmask & :mask) != 0}을 건다.
 * 그룹 <em>안</em>의 OR는 마스크 한 개가 흡수하고, 그룹 <em>사이</em>의 AND는 술어 세 개가 만든다.
 * 세 그룹을 한 마스크로 합치면 OR가 되어 의미가 뒤집힌다.
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
     * 한 그룹(옵션1 또는 옵션2)의 마스크. 그룹 안은 OR라 비트 합집합이 곧 "이 중 하나라도"다.
     *
     * @return 비어 있거나 null이면 0 — 호출부가 술어를 붙이지 않는 신호로 쓴다
     */
    public static long ofAny(Collection<Long> tagIds) {
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
