package org.sopt.solply_server.domain.admin.place.repository.querydsl;

import static org.sopt.solply_server.domain.place.entity.QPlace.place;
import static org.sopt.solply_server.domain.town.entity.QTown.town;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.QPlace;
import org.sopt.solply_server.domain.town.entity.QTown;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminPlaceRepositoryImpl implements AdminPlaceRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Place> findPlacesWithTownByKeyword(final String keyword) {
        QPlace qPlace = place;
        QTown qTown = town;

        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return List.of();

        int length = kw.codePointCount(0, kw.length());
        boolean useFullText = length >= 2;

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

            return queryFactory
                    .selectFrom(qPlace)
                    .join(qPlace.town, qTown).fetchJoin()
                    .where(score.gt(0))
                    .orderBy(
                            score.desc(),
                            qPlace.name.asc(),
                            qPlace.id.asc()
                    )
                    .limit(10)
                    .fetch();
        } else {
            // 1글자 → LIKE fallback
            String escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + escaped + "%";

            var pos = Expressions.numberTemplate(
                    Integer.class,
                    "LOCATE({0}, {1})",
                    kw, qPlace.name
            );

            return queryFactory
                    .selectFrom(qPlace)
                    .join(qPlace.town, qTown).fetchJoin()
                    .where(Expressions.booleanTemplate("{0} LIKE {1} ESCAPE '\\\\'", qPlace.name, pattern))
                    .orderBy(
                            pos.asc().nullsLast(),
                            qPlace.name.asc(),
                            qPlace.id.asc()
                    )
                    .limit(10)
                    .fetch();
        }
    }

    private String sanitizeForBooleanMode(final String token) {
        return token.replaceAll("[+\\-@~<>\\(\\)\"*]", " ");
    }
}