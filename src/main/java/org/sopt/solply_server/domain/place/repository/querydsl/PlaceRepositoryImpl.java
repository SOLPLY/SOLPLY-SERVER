package org.sopt.solply_server.domain.place.repository.querydsl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.PlaceSearchConditionDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.QPlace;
import org.sopt.solply_server.domain.place.entity.QPlaceTag;
import org.sopt.solply_server.domain.tag.entity.QTag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class PlaceRepositoryImpl implements PlaceRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public List<Place> findPlacesByConditions(PlaceSearchConditionDto condition) {
        QPlace place = QPlace.place;
        BooleanBuilder whereCondition = createBasicConditions(place, condition);

        if (!condition.hasMainTag()) {
            return findPlacesWithoutTags(place, whereCondition);
        }

        if (!condition.hasSubTagA() && !condition.hasSubTagB()) {
            return findPlacesWithMainTag(place, whereCondition, condition);
        }

        return findPlacesWithTags(place, whereCondition, condition);
    }

    public List<Place> findPlacesByKeyword(String keyword) {
        QPlace p = QPlace.place;
        String kw = keyword.trim();
        int length = kw.codePointCount(0, kw.length());

        if (length >= 3) {
            // 3글자 이상: pg_trgm 유사도 검색
            var sim   = Expressions.numberTemplate(Double.class, "similarity({0}, {1})", p.name, kw);
            var match = Expressions.booleanTemplate("{0} % {1}", p.name, kw);

            return queryFactory.selectFrom(p)
                    .where(match)
                    .orderBy(sim.desc(), p.name.asc(), p.id.asc())
                    .limit(3)
                    .fetch();
        } else {
            // 2글자: ILIKE + strpos
            String pattern = "%" + kw + "%";
            var pos = Expressions.numberTemplate(Integer.class, "strpos(lower({0}), lower({1}))", p.name, kw);
            var sim = Expressions.numberTemplate(Double.class, "similarity({0}, {1})", p.name, kw);

            return queryFactory.selectFrom(p)
                    .where(p.name.likeIgnoreCase(pattern))
                    .orderBy(
                            pos.asc().nullsLast(),
                            sim.desc(),
                            p.name.asc(),
                            p.id.asc()
                    )
                    .limit(3)
                    .fetch();
        }
    }

    // 전체 조회
    private List<Place> findPlacesWithoutTags(QPlace place, BooleanBuilder whereCondition) {
        List<Place> places = queryFactory
                .selectFrom(place)
                .where(whereCondition)
                .orderBy(place.createdAt.desc())
                .fetch();

        loadPlaceTagsAndTags(places);

        return places;
    }

    // 메인 태그만 있는 경우
    private List<Place> findPlacesWithMainTag(QPlace place, BooleanBuilder whereCondition,
            PlaceSearchConditionDto condition) {
        QPlaceTag placeTag = QPlaceTag.placeTag;
        QTag tag = QTag.tag;

        whereCondition.and(tag.id.eq(condition.mainTagId()))
                .and(tag.type.eq(TagType.MAIN));

        return queryFactory
                .selectDistinct(place)
                .from(place)
                .leftJoin(place.placeTags, placeTag).fetchJoin()  // PlaceTag fetch join
                .leftJoin(placeTag.tag, tag).fetchJoin()          // Tag fetch join
                .where(whereCondition)
                .orderBy(place.createdAt.desc())
                .fetch();
    }

    // 메인 태그와 서브 태그가 모두 있는 경우
    private List<Place> findPlacesWithTags(QPlace place, BooleanBuilder whereCondition,
            PlaceSearchConditionDto condition) {
        // 메인 태그 EXISTS 조건
        whereCondition.and(createMainTagExistsCondition(place, condition.mainTagId()));

        // 옵션1 태그 EXISTS 조건
        if (condition.hasSubTagA()) {
            whereCondition.and(createSubTagExistsCondition(place, condition.subTagOptionAIds(), TagType.OPTION1));
        }

        // 옵션2 태그 EXISTS 조건
        if (condition.hasSubTagB()) {
            whereCondition.and(createSubTagExistsCondition(place, condition.subTagOptionBIds(), TagType.OPTION2));
        }

        return queryFactory
                .selectDistinct(place)
                .from(place)
                .where(whereCondition)
                .orderBy(place.createdAt.desc())
                .fetch();
    }

    // 영속성 컨텍스트에 미리 로딩
    private void loadPlaceTagsAndTags(List<Place> places) {
        if (places.isEmpty()) {
            return;
        }

        List<Long> placeIds = places.stream()
                .map(Place::getId)
                .collect(Collectors.toList());

        // List<PlaceTag> 로딩
        queryFactory
                .selectFrom(QPlaceTag.placeTag)
                .leftJoin(QPlaceTag.placeTag.tag).fetchJoin()
                .where(QPlaceTag.placeTag.place.id.in(placeIds))
                .fetch();
    }

    // 기본 조건(동네, 북마크) 추가 메서드
    private BooleanBuilder createBasicConditions(QPlace place, PlaceSearchConditionDto condition) {
        BooleanBuilder basicCondition = new BooleanBuilder();
        // Town 조건
        if (condition.townId() != null) {
            basicCondition.and(place.town.id.eq(condition.townId()));
        }

        // 북마크 조건 - 올바른 로직
        if (condition.isBookmarkSearch()) {
            if (condition.hasBookmarkedPlaces()) {
                // 북마크된 장소가 있으면 해당 장소들만 조회
                basicCondition.and(place.id.in(condition.bookmarkedPlaceIds()));
            } else {
                // 북마크된 장소가 없으면 빈 결과 반환
                basicCondition.and(place.id.isNull());
            }
        }
        // 홈 화면 전체 장소 조회 -> 북마크 여부 상관 없이 모든 장소 대상으로 필터링

        return basicCondition;
    }

    private BooleanExpression createMainTagExistsCondition(QPlace place, Long mainTagId) {
        QPlaceTag mainPlaceTag = new QPlaceTag("mainPlaceTag"); // 이름을 다르게 지정하여 충돌 방지
        QTag mainTag = new QTag("mainTag");

        return JPAExpressions
                .selectOne()
                .from(mainPlaceTag)
                .join(mainPlaceTag.tag, mainTag)
                .where(mainPlaceTag.place.eq(place)
                        .and(mainTag.id.eq(mainTagId))
                        .and(mainTag.type.eq(TagType.MAIN)))
                .exists();
    }


    private BooleanExpression createSubTagExistsCondition(QPlace place, List<Long> tagIds, TagType tagType) {
        QPlaceTag subPlaceTag = new QPlaceTag("subPlaceTag" + tagType.name()); // 이름을 다르게 지정하여 충돌 방지
        QTag subTag = new QTag("subTag" + tagType.name());

        return JPAExpressions
                .selectOne()
                .from(subPlaceTag)
                .join(subPlaceTag.tag, subTag)
                .where(subPlaceTag.place.eq(place)
                        .and(subTag.id.in(tagIds))
                        .and(subTag.type.eq(tagType)))
                .exists();
    }
}