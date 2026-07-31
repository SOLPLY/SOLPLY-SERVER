package org.sopt.solply_server.domain.place.repository.querydsl;

import static org.sopt.solply_server.domain.place.entity.QPlace.place;
import static org.sopt.solply_server.domain.town.entity.QTown.town;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.QPlace;
import org.sopt.solply_server.domain.place.entity.QPlaceTag;
import org.sopt.solply_server.domain.tag.entity.QTag;
import org.sopt.solply_server.domain.town.entity.QTown;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class PlaceRepositoryImpl implements PlaceRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public List<Place> findPlacesWithTownByKeyword(final String keyword) {
        QPlace qPlace = place;
        QTown qTown = town;

        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return List.of();

        int length = kw.codePointCount(0, kw.length());
        boolean useFullText = length >= 2;

        BooleanBuilder where = new BooleanBuilder()
                .and(qPlace.active.isTrue());

        if (useFullText) {
            String booleanQuery = Arrays.stream(kw.split("\\s+"))
                    .filter(s -> !s.isBlank())
                    .map(tok -> sanitizeForBooleanMode(tok) + "*")
                    .collect(Collectors.joining(" "));

            var score = Expressions.numberTemplate(
                    Double.class,
                    "match_against({0}, {1})",
                    qPlace.name, booleanQuery
            );

            where.and(score.gt(0));

            return queryFactory
                    .selectFrom(qPlace)
                    .join(qPlace.town, qTown).fetchJoin()
                    .where(where)
                    .orderBy(
                            score.desc(),
                            qPlace.name.asc(),
                            qPlace.id.asc()
                    )
                    .limit(10)
                    .fetch();
        } else {
            // 1글자 → LIKE fallback
            String escaped = kw.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            String pattern = "%" + escaped + "%";

            var pos = Expressions.numberTemplate(
                    Integer.class,
                    "LOCATE({0}, {1})",
                    kw, qPlace.name
            );

            where.and(
                    Expressions.booleanTemplate(
                            "{0} LIKE {1} ESCAPE '\\\\'",
                            qPlace.name, pattern
                    )
            );

            return queryFactory
                    .selectFrom(qPlace)
                    .join(qPlace.town, qTown).fetchJoin()
                    .where(where)
                    .orderBy(
                            pos.asc().nullsLast(),
                            qPlace.name.asc(),
                            qPlace.id.asc()
                    )
                    .limit(10)
                    .fetch();
        }
    }

    @Override
    public Page<Place> findByUserIdWithTown(Long userId, Pageable pageable) {
        List<Place> results = queryFactory
                .selectFrom(place)
                .join(place.town, town).fetchJoin()
                .where(
                        place.createdBy.id.eq(userId),
                        place.active.isTrue()
                )
                .orderBy(place.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(place.count())
                .from(place)
                .where(
                        place.createdBy.id.eq(userId),
                        place.active.isTrue()
                );

        return PageableExecutionUtils.getPage(results, pageable, countQuery::fetchOne);
    }

    @Override
    public List<Place> findActivePlacesWithTagsByTownId(Long townId) {
        QPlaceTag placeTag = QPlaceTag.placeTag;
        QTag tag = QTag.tag;

        return queryFactory
                .selectDistinct(place)
                .from(place)
                .leftJoin(place.placeTags, placeTag).fetchJoin()
                .leftJoin(placeTag.tag, tag).fetchJoin()
                .where(place.active.isTrue()
                        .and(place.town.id.eq(townId)))
                .orderBy(place.createdAt.desc(), place.id.desc())
                .fetch();
    }

    /**
     * db 모드({@code popular-read-mode=db})의 페이지 채우기 전용 — 호출자는 {@code PlaceService.listFromDb}
     * 하나다. 정렬 쿼리가 확정한 페이지 id(최대 50건)만 받아 응답 조립에 필요한 연관을 한 번에 끌어온다.
     *
     * <p><b>town을 페치 조인하는 이유.</b> 응답 DTO가 {@code place.getTown().getId()}를 쓰는데,
     * {@code Place.town}은 {@code LAZY} + 필드 접근이라 하이버네이트가 식별자 getter를 프록시에서
     * 가로채지 못한다 — {@code getId()} 한 번이 프록시를 통째로 초기화해 <b>SELECT 1회</b>를 낸다.
     * 페이지에 서로 다른 동네가 N개면 N회다(시 단위 조회는 leaf 18개까지 벌어진다).
     *
     * <p>이것이 기능 버그는 아니지만 <b>측정을 왜곡한다.</b> 캐시 경로는 같은 값을 스냅샷
     * ({@code CachedPlace.townId})에서 읽어 추가 조회가 0인데, db 경로만 동네 수에 비례해 쿼리가
     * 붙으면 A/B의 "요청당 statements" 차이가 구조가 아니라 <b>페치 전략</b>에서 나온다.
     * 플랜 C Q1의 성공 조건이 "요청당 statements가 A+3 이내"이므로, 그 지표가 비교하려는 것을
     * 정확히 비교하게 하려면 여기서 함께 끌어와야 한다.
     *
     * <p>{@code town}은 {@code nullable = false}라 inner join으로 충분하며, ToOne 페치 조인이라
     * {@code placeTags} 컬렉션 페치와 겹쳐도 곱집합이 늘지 않는다.
     */
    @Override
    public List<Place> findPlacesWithTagsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        QPlaceTag placeTag = QPlaceTag.placeTag;
        QTag tag = QTag.tag;

        return queryFactory
                .selectDistinct(place)
                .from(place)
                .join(place.town).fetchJoin()
                .leftJoin(place.placeTags, placeTag).fetchJoin()
                .leftJoin(placeTag.tag, tag).fetchJoin()
                .where(place.id.in(ids))
                .fetch();
    }

    private String sanitizeForBooleanMode(final String token) {
        // BOOLEAN MODE에서 의미 있는 특수문자 제거/공백 치환
        // (+ - @ ~ < > ( ) " * 등의 혼선을 방지)
        return token.replaceAll("[+\\-@~<>\\(\\)\"*]", " ");
    }
}