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

    private String sanitizeForBooleanMode(final String token) {
        // BOOLEAN MODE에서 의미 있는 특수문자 제거/공백 치환
        // (+ - @ ~ < > ( ) " * 등의 혼선을 방지)
        return token.replaceAll("[+\\-@~<>\\(\\)\"*]", " ");
    }
}