package org.sopt.solply_server.domain.admin.place.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.admin.place.dto.AdminPlaceSummaryDto;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceDetailsGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceListResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceUpsertResponse;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import org.sopt.solply_server.domain.place.service.event.PlaceCreatedEvent;
import org.sopt.solply_server.global.util.s3.FileTransferMode;
import org.sopt.solply_server.global.util.s3.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.domain.place.dto.PlaceImageInfoDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshotRefresher;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageFileKeyValidator;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.sopt.solply_server.global.util.s3.TargetDir;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPlaceService {

    private final AdminPlaceRepository adminPlaceRepository;
    private final PlaceStatsRepository placeStatsRepository;
    /** 목록 사진을 <b>커밋 뒤에</b> 다시 찍게 한다 — 시점의 근거는 리프레셔 javadoc */
    private final PlaceListSnapshotRefresher placeListSnapshotRefresher;
    private final EntityManager entityManager;

    private final ImageFileKeyValidator imageFileKeyValidator;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ImageUrlProvider imageUrlProvider;
    private final AdminTagValidator adminTagValidator;
    private final AdminEntityLoader adminEntityLoader;

    /**
     * <b>새 장소는 같은 트랜잭션에서 place_stats 행까지 만든다.</b> 목록 조회 두 정렬의 기준
     * 테이블이 place_stats라, 행이 없으면 방금 등록한 장소가 <em>최신순 맨 앞</em>에서 다음 카운트
     * 배치(≤1h)까지 보이지 않는다 (V34).
     *
     * <p>동네가 비활성이면 {@code Place.create}가 장소를 비활성으로 만들고, 그 경우
     * {@code upsertRowsForActivePlaces}의 {@code WHERE p.active = 1}이 행 생성을 막는다 —
     * 여기서 따로 분기하지 않는 이유다.
     */
    @Transactional
    public AdminPlaceUpsertResponse createPlace(final Long adminUserId, final AdminPlaceUpsertRequest req) {
        User admin = adminEntityLoader.getUser(adminUserId);
        Town town = adminEntityLoader.getTown(req.townId());

        // 태그 검증(타입 + 관계)
        adminTagValidator.validatePlaceTagConditions(req.mainTagId(), req.option1TagIds(), req.option2TagIds());

        Tag mainTag = adminEntityLoader.getTag(req.mainTagId());
        List<Tag> opt1 = loadTags(req.option1TagIds());
        List<Tag> opt2 = req.option2TagIds() == null ? List.of() : loadTags(req.option2TagIds());

        // 이미지 키 검증
        List<String> imageKeys = normalizeKeys(req.imageFileKeys());
        imageFileKeyValidator.validateFileKeys(imageKeys);

        Place place = Place.create(
                req.name(),
                req.introduction(),
                req.address(),
                req.latitude(),
                req.longitude(),
                req.contactNumber(),
                req.openingHours(),
                req.snsLinks(),
                imageKeys,
                req.placeCheckpoints(),
                town,
                admin,
                town.getActive(),
                mainTag,
                opt1,
                opt2
        );

        Place saved = adminPlaceRepository.save(place);
        syncPlaceStats(saved.getId());

        publishImageMoveEvent(admin.getId(), saved.getId(), imageKeys);
        applicationEventPublisher.publishEvent(new PlaceCreatedEvent(saved.getId()));

        log.info("어드민 장소 생성 - adminId: {}, placeId: {}", adminUserId, saved.getId());
        return AdminPlaceUpsertResponse.of(saved.getId());
    }

    /**
     * <b>수정은 두 갈래다 — 기준은 "이 수정이 목록 배열에 닿는가"이고, 판정은
     * {@link PlaceListMembershipKey}의 수정 전후 비교다.</b>
     *
     * <ul>
     *   <li><b>닿는다</b>(동네·좌표·태그 집합 중 하나라도 바뀜) — place_stats의 파생 컬럼을 같은
     *       트랜잭션에서 다시 짓고 사진을 통째로 다시 찍는다. 파생 컬럼이 낡으면 순위가 아니라
     *       <em>결과 집합</em>이 틀린다: 뗀 태그({@code tag_bitmask})로 계속 검색되고, 옮긴 동네가
     *       아니라 이전 동네({@code town_id}) 목록에 낀다.</li>
     *   <li><b>안 닿는다</b>(이름·소개·주소·연락처·영업시간·SNS·이미지·체크포인트) — 파생 컬럼이
     *       그대로이므로 place_stats upsert를 건너뛰고, 그 장소의 표시값만 갈아 끼운다.</li>
     * </ul>
     *
     * <p><b>수정 전 열쇠는 {@code clearTags} 전에 잡아야 한다</b> — 비운 뒤에 읽으면 태그 집합이
     * 언제나 빈 것으로 보여 모든 수정이 재빌드로 샌다.
     */
    @Transactional
    public AdminPlaceUpsertResponse updatePlace(final Long placeId, final AdminPlaceUpsertRequest req) {
        Place place = adminEntityLoader.getPlaceWithTown(placeId);
        PlaceListMembershipKey membershipBefore = PlaceListMembershipKey.from(place);
        Town updatedTown = adminEntityLoader.getTown(req.townId());

        // 태그 검증(타입 + 관계)
        adminTagValidator.validatePlaceTagConditions(req.mainTagId(), req.option1TagIds(), req.option2TagIds());

        Tag mainTag = adminEntityLoader.getTag(req.mainTagId());
        List<Tag> opt1 = loadTags(req.option1TagIds());
        List<Tag> opt2 = req.option2TagIds() == null ? List.of() : loadTags(req.option2TagIds());

        // 이미지 키 검증
        List<String> imageKeys = normalizeKeys(req.imageFileKeys());
        imageFileKeyValidator.validateFileKeys(imageKeys);

        place.clearTags();
        entityManager.flush();

        place.update(
                req.name(),
                req.introduction(),
                req.address(),
                req.latitude(),
                req.longitude(),
                req.contactNumber(),
                req.openingHours(),
                updatedTown,
                req.snsLinks(),
                imageKeys,
                req.placeCheckpoints(),
                mainTag,
                opt1,
                opt2
        );

        publishImageMoveEvent(place.getCreatedBy().getId(), place.getId(), imageKeys);

        if (PlaceListMembershipKey.from(place).equals(membershipBefore)) {
            placeListSnapshotRefresher.patchPlaceViewAfterCommit(place.getId());
        } else {
            syncPlaceStats(place.getId());
        }

        log.info("어드민 장소 수정 - placeId: {}", placeId);

        return AdminPlaceUpsertResponse.of(place.getId());
    }

    public AdminPlaceDetailsGetResponse getPlaceDetails(final Long placeId) {
        Place place = adminEntityLoader.getPlaceWithTownAndCheckpoints(placeId);

        Town town = place.getTown();

        // 이미지 URL 변환
        List<PlaceImageInfoDto> imageInfos = place.getPlaceImageInfos().stream()
                .map(info -> PlaceImageInfoDto.of(
                        info.getDisplayOrder(),
                        imageUrlProvider.getImageUrl(info.getImageFileKey())
                ))
                .toList();

        // 태그 id 추출
        Long mainTagId = place.getMainTag().map(Tag::getId).orElse(null);

        List<Long> option1TagIds = place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(t -> t.getType() == TagType.OPTION1)
                .map(Tag::getId)
                .toList();

        List<Long> option2TagIds = place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(t -> t.getType() == TagType.OPTION2)
                .map(Tag::getId)
                .toList();

        return AdminPlaceDetailsGetResponse.of(
                place.getId(),
                place.getName(),
                place.getIntroduction(),
                place.getAddress(),
                place.getLatitude(),
                place.getLongitude(),
                place.getContactNumber(),
                place.getOpeningHours(),
                town.getId(),
                town.getName(),
                mainTagId,
                option1TagIds,
                option2TagIds,
                place.getSnsLinks(),
                place.getCheckpoints(),
                imageInfos
        );
    }

    public AdminPlaceListResponse searchPlaces(final String keyword) {
        if (keyword == null || keyword.trim().length() < 2) {
            throw new BusinessException(ErrorCode.INVALID_KEYWORD);
        }

        List<Place> base = adminPlaceRepository.findPlacesWithTownByKeyword(keyword);
        if (base.isEmpty()) return AdminPlaceListResponse.of(List.of());

        // tags 로딩(N+1 방지)
        List<Long> ids = base.stream().map(Place::getId).toList();
        adminPlaceRepository.findByIdInWithTags(ids);

        List<AdminPlaceSummaryDto> result = base.stream()
                .map(p -> AdminPlaceSummaryDto.of(
                        p.getId(),
                        p.getName(),
                        p.getTown().getName(),
                        p.getMainTag().map(Tag::getName).orElse(null)
                ))
                .toList();

        return AdminPlaceListResponse.of(result);
    }

    public AdminPlaceListResponse getPlacesByTown(final Long townId) {
        Town town = adminEntityLoader.getTown(townId); // 존재 검증
        List<Place> places = adminPlaceRepository.findAdminPlacesWithTagsByTownId(town.getId());

        List<AdminPlaceSummaryDto> result = places.stream()
                .map(p -> AdminPlaceSummaryDto.of(
                        p.getId(),
                        p.getName(),
                        p.getTown().getName(),
                        p.getMainTag().map(Tag::getName).orElse(null)
                ))
                .toList();

        return AdminPlaceListResponse.of(result);
    }

    /**
     * 장소를 지우면 <b>목록에서도 그 자리에서 빠져야 한다.</b> 두 정렬 모두 place_stats가 기준
     * 테이블이라 행이 남아 있는 동안 노출되고, <b>행을 지우는 경로는 이것 하나뿐이다</b> —
     * 카운트 배치가 잔행을 청소하던 시절은 지났다 ({@code PlaceStatsRepository#deleteByPlaceIds}).
     *
     * <p><b>FK의 {@code ON DELETE CASCADE}가 있는데도 명시적으로 지우는 이유.</b>
     * {@code fk_place_stats_place}가 같은 행을 지우는 것은 맞다. 다만 그 보장은 place_stats를
     * 통째로 재생성한 마이그레이션마다(V29·V32) 다시 써야 하는 DDL 한 줄에 걸려 있어, 한 번
     * 빠뜨리면 노출 창이 조용히 되돌아온다 — 조회 계약을 지키는 책임은 그것을 결정한 층에 둔다.
     *
     * <p><b>⚠️ 두 테이블을 반드시 places → place_stats 순으로 잠글 것.</b> 나머지 어드민 경로
     * (생성·수정·재활성)는 places를 먼저 갱신하고 place_stats를 뒤에 짓는다. 여기만 순서가
     * 뒤집히면 같은 장소를 걸친 두 어드민 요청이 서로의 락을 마주 보고 {@code ERROR 1213}으로
     * 죽는다 — 특히 {@link #activatePlacesByTownIds}는 동네 장소를 통째로 잠그므로 겹칠 자리가
     * 넓다. 아래 {@code lock}이 그 순서를 맞추는 장치다: 이 줄이 없으면 JPA가 places DELETE를
     * 커밋 flush까지 미뤄, 실제 잠금 순서가 place_stats → places가 된다.
     *
     * <p>{@code delete}를 먼저 부르는 것으로는 대체할 수 없다 — 그러면 place_stats 행이 FK
     * CASCADE로 지워져 위에서 밝힌 "CASCADE에 기대지 않는다"는 계약이 무너진다.
     */
    @Transactional
    public void deletePlace(final Long placeId) {
        Place place = adminEntityLoader.getPlace(placeId);
        entityManager.lock(place, LockModeType.PESSIMISTIC_WRITE);

        placeStatsRepository.deleteByPlaceIds(List.of(placeId));
        adminPlaceRepository.delete(place);
        placeListSnapshotRefresher.refreshAfterCommit();

        log.info("어드민 장소 삭제 - placeId: {}", placeId);
    }

    /**
     * 동네를 되살리면 그 동네 장소들의 place_stats 행도 <b>그 자리에서</b> 만든다.
     *
     * <p>내리는 쪽({@link #deletePlace})만 즉시로 당기고 되살리는 쪽은 배치에 맡기던 옛 비대칭은
     * 인기순만 place_stats를 기준으로 삼던 시절의 것이다. 최신순까지 같은 기준이 된 지금(V34) 행을
     * 안 만들면 되살린 장소가 <b>최신순에서도</b> 다음 카운트 배치(≤1h)까지 사라진다 — 그것은
     * 대가가 아니라 버그다.
     *
     * <p>비대칭은 이제 남지 않는다. 새로 만든 행은 아직 채점 전이지만 인기순도 점수 값 그대로
     * 정렬하므로 0점 자리에 즉시 선다 ({@code PlaceListDbQueryRepository#findPopularRows}).
     *
     * <p>{@code updateActiveByTownId}가 {@code clearAutomatically}라 갱신 결과를 엔티티로 다시 읽지
     * 않고 id만 모아 넘긴다. 활성 여부 판정은 넘긴 뒤 SQL이 원본에서 다시 한다.
     */
    @Transactional
    public void activatePlacesByTownIds(final List<Long> townIds) {
        adminPlaceRepository.updateActiveByTownId(townIds, true);
        syncPlaceStats(adminPlaceRepository.findIdsByTownIds(townIds));
    }

    /** 장소 하나. 태그·동네가 이미 flush된 뒤에 부를 것 — 문장이 원본을 다시 읽는다. */
    private void syncPlaceStats(final Long placeId) {
        syncPlaceStats(List.of(placeId));
    }

    /**
     * place_stats 행을 원본(places · place_tag)에서 다시 짓는다. 비활성 장소는 문장이 걸러내므로
     * 여기서 활성 여부를 묻지 않는다 ({@code PlaceStatsRepository#upsertRowsForActivePlaces}).
     *
     * <p><b>파생 컬럼을 다시 짓는 것과 사진을 다시 찍는 것은 한 몸이라 여기 묶어 둔다.</b> 사진의
     * 원천이 {@code place_stats ⋈ places}이므로, 이 문장이 도는 곳은 곧 사진이 낡는 곳이다.
     * 호출부마다 훅을 흩으면 나중에 경로가 하나 늘 때 조용히 빠진다. 실제 재빌드는 커밋 뒤로
     * 미뤄진다 — 커밋 전에 지으면 로더의 새 커넥션이 <b>옛 데이터</b>를 읽어 낡은 사진으로 덮는다
     * ({@code PlaceListSnapshotRefresher} javadoc).
     *
     * <p><b>어드민 쓰기가 전부 여기로 오지는 않는다.</b> 사진을 통째로 다시 찍는 경로는 생성 ·
     * 재활성 · {@link #deletePlace} · 그리고 목록 배열에 닿는 수정
     * ({@link PlaceListMembershipKey})뿐이다. 표시값만 바뀐 수정은 파생 컬럼이 그대로라 이 문장을
     * 부르지 않고 표시값 패치로 빠진다 — <b>배열에 닿는 값을 새로 추가한다면 그 열쇠와 이 문장을
     * 함께 짚을 것.</b>
     */
    private void syncPlaceStats(final List<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return;
        }
        placeStatsRepository.upsertRowsForActivePlaces(placeIds);
        placeListSnapshotRefresher.refreshAfterCommit();
    }


    private List<String> normalizeKeys(List<String> keys) {
        return keys == null ? List.of() : keys;
    }

    private void publishImageMoveEvent(Long userId, Long placeId, List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) return;

        ImageFileKeyUpdateEvent event = ImageFileKeyUpdateEvent.of(
                userId,
                placeId,
                TargetDir.PLACE,
                imageKeys,
                FileTransferMode.MOVE
        );
        applicationEventPublisher.publishEvent(event);
    }

    private List<Tag> loadTags(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return List.of();
        List<Tag> tags = new ArrayList<>(tagIds.size());
        for (Long id : tagIds) {
            tags.add(adminEntityLoader.getTag(id));
        }
        return tags;
    }
}