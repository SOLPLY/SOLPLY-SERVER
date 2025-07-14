package org.sopt.solply_server.domain.place.repository.querydsl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
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
        if (!condition.hasMainTag()) {
            return findPlacesWithoutTags(condition);
        }

        if (!condition.hasSubTagA() && !condition.hasSubTagB()) {
            return findPlacesWithMainTag(condition);
        }

        return findPlacesWithTags(condition);
    }

    // 전체 조회
    private List<Place> findPlacesWithoutTags(PlaceSearchConditionDto condition) {
        QPlace place = QPlace.place;

        BooleanBuilder whereCondition = createBasicConditions(place, condition);

        return queryFactory
                .selectFrom(place)
                .where(whereCondition)
                .orderBy(place.createdAt.desc())
                .fetch();
    }

    // 메인 태그만 있는 경우
    private List<Place> findPlacesWithMainTag(PlaceSearchConditionDto condition) {
        QPlace place = QPlace.place;
        QPlaceTag placeTag = QPlaceTag.placeTag;
        QTag tag = QTag.tag;

        BooleanBuilder whereCondition = createBasicConditions(place, condition);

        whereCondition.and(tag.id.eq(condition.mainTagId()))
                .and(tag.type.eq(TagType.MAIN));

        return queryFactory
                .selectDistinct(place)
                .from(place)
                .join(place.placeTags, placeTag)
                .join(placeTag.tag, tag)
                .where(whereCondition)
                .orderBy(place.createdAt.desc())
                .fetch();
    }

    // 메인 태그와 서브 태그가 모두 있는 경우
    private List<Place> findPlacesWithTags(PlaceSearchConditionDto condition) {
        QPlace place = QPlace.place;

        BooleanBuilder whereCondition = createBasicConditions(place, condition);

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

    // 기본 조건(동네, 북마크) 추가 메서드
    private BooleanBuilder createBasicConditions(QPlace place, PlaceSearchConditionDto condition) {
        BooleanBuilder basicCondition = new BooleanBuilder();

        // Town 조건
        if (condition.townId() != null) {
            basicCondition.and(place.town.id.eq(condition.townId()));
        }

        // 북마크 조건
        if (condition.isBookmarkSearch()) {
            if (condition.hasBookmarkedPlaces()) {
                basicCondition.and(place.id.in(condition.bookmarkedPlaceIds()));
            } else {
                basicCondition.and(place.id.isNull());
            }
        }

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

    //
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