package org.sopt.solply_server.domain.place.util;

import java.util.List;

/**
 * 요청의 태그 조건을 그룹별 마스크 <b>셋</b>으로 옮긴 것. 0은 "이 그룹으로 거르지 않는다".
 *
 * <p>세 그룹은 AND로 엮이므로 <b>한 마스크로 합치면 안 된다</b> — 합치는 순간 OR가 되어 의미가
 * 뒤집힌다. 그룹 안의 OR만 마스크가 흡수한다 ({@link TagBitmask} 참조).
 *
 * <p>메인 태그가 없으면 서브 조건은 통째로 버린다. 북마크 검색의 {@code PlaceTagMatcher}가
 * {@code mainTagId == null}이면 원본을 그대로 돌려주는 것과 같은 규칙이고, 두 경로가 여기서
 * 갈리면 같은 요청이 경로마다 다른 답을 낸다.
 *
 * <p><b>SQL 경로와 메모리 경로가 이 한 벌을 공유한다.</b> 목록 정렬은 DB가 하는 판
 * ({@code PlaceListDbQueryRepository}의 {@code (tag_bitmask & :mask) != 0} 술어)과 메모리가 하는 판
 * ({@code PlaceSortIndex}의 {@link #matches(long)})이 나란히 있고, 두 판의 응답은 바이트째 같아야
 * 한다. 마스크 산출식을 복사해 두면 한쪽만 고치는 실수가 조용히 통과하므로 여기 하나로 묶는다.
 */
public record TagMasks(long main, long subA, long subB) {

    public static TagMasks of(Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds) {
        if (mainTagId == null) {
            return new TagMasks(0L, 0L, 0L);
        }
        return new TagMasks(
                TagBitmask.of(mainTagId),
                TagBitmask.ofAny(subTagAIds),
                TagBitmask.ofAny(subTagBIds));
    }

    /**
     * 장소 하나가 이 조건을 통과하는가 — SQL 술어 셋의 자바 판이다.
     *
     * <p>마스크가 0인 그룹은 술어를 붙이지 않는 것과 같아야 하므로 <b>무조건 통과</b>다.
     * {@code (bitmask & 0) != 0}은 언제나 거짓이라, 0을 그대로 대입하면 태그 조건이 없는 요청이
     * 통째로 빈 결과가 된다.
     */
    public boolean matches(long tagBitmask) {
        return (main == 0L || (tagBitmask & main) != 0L)
                && (subA == 0L || (tagBitmask & subA) != 0L)
                && (subB == 0L || (tagBitmask & subB) != 0L);
    }
}
