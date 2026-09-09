package org.sopt.solply_server.domain.admin.place.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.place.cache.SnapshotRefresher;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import org.sopt.solply_server.global.util.s3.ImageFileKeyValidator;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 장소 수정이 <b>어느 갈래로 가는지</b>만 겨눈다 — 목록 배열에 닿는 수정은 전량 재빌드로,
 * 표시값만 바뀐 수정은 그 장소 하나의 표시값 패치로. place_stats 재생성은 갈래를 가리지 않고
 * 언제나 돈다(V40 — 이름이 그 테이블의 칸이다).
 *
 * <p><b>틀리는 방향이 둘 다 조용하다</b>는 것이 이 테스트의 이유다. 표시 수정이 재빌드로 새면
 * 화면은 멀쩡한 채 어드민 한 번의 비용이 장소 수에 비례하고 스냅샷 보존 창이 그만큼 빨리 밀린다.
 * 반대로 동네·좌표·태그 수정이 표시 패치로 새면 파생 컬럼이 낡아 <b>결과 집합이 틀린다</b> —
 * 뗀 태그로 계속 검색되고 옮기기 전 동네 목록에 낀다.
 *
 * <p>겨누는 갈림길은 열쇠의 넷({@code PlaceListMembershipKey})이다: 동네 · 위도 · 태그 집합, 그리고
 * <b>순서만 바뀌고 집합은 같은 태그</b> — 마지막 하나가 집합 비교를 리스트 비교로 되돌리지 못하게 막는다.
 */
@ExtendWith(MockitoExtension.class)
class AdminPlaceServiceUpdateRoutingTest {

    @Mock private AdminPlaceRepository adminPlaceRepository;
    @Mock private PlaceStatsRepository placeStatsRepository;
    @Mock private SnapshotRefresher snapshotRefresher;
    @Mock private EntityManager entityManager;
    @Mock private ImageFileKeyValidator imageFileKeyValidator;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private ImageUrlProvider imageUrlProvider;
    @Mock private AdminTagValidator adminTagValidator;
    @Mock private AdminEntityLoader adminEntityLoader;

    @InjectMocks private AdminPlaceService adminPlaceService;

    private static final long PLACE_ID = 100L;
    private static final long TOWN_ID = 10L;
    private static final long OTHER_TOWN_ID = 11L;
    private static final long MAIN_TAG_ID = 1L;
    private static final long OPTION1_TAG_ID = 2L;
    private static final long OPTION2_TAG_ID = 3L;
    private static final double LATITUDE = 37.5;
    private static final double LONGITUDE = 127.0;

    /** 수정 전 상태와 <b>모든 값이 같은</b> 요청 — 각 테스트는 여기서 한 칸만 비튼다 */
    private static final AdminPlaceUpsertRequest UNCHANGED = new AdminPlaceUpsertRequest(
            "원래이름", "소개", "주소", LATITUDE, LONGITUDE, TOWN_ID,
            MAIN_TAG_ID, List.of(OPTION1_TAG_ID), List.of(), List.of(),
            "02-000-0000", "매일 09:00-18:00", Map.of(), List.of());

    @Test
    void 이름만_바꾸면_표시값만_고치고_스냅샷은_그대로다() {
        AdminPlaceUpsertRequest req = withName(UNCHANGED, "바뀐이름");

        updateWith(req);

        assertDisplayPatchOnly();
    }

    @Test
    void 동네가_바뀌면_스냅샷을_다시_짓는다() {
        AdminPlaceUpsertRequest req = new AdminPlaceUpsertRequest(
                UNCHANGED.name(), UNCHANGED.introduction(), UNCHANGED.address(),
                UNCHANGED.latitude(), UNCHANGED.longitude(), OTHER_TOWN_ID,
                UNCHANGED.mainTagId(), UNCHANGED.option1TagIds(), UNCHANGED.option2TagIds(),
                UNCHANGED.imageFileKeys(), UNCHANGED.contactNumber(), UNCHANGED.openingHours(),
                UNCHANGED.snsLinks(), UNCHANGED.placeCheckpoints());

        updateWith(req);

        assertFullRebuild();
    }

    @Test
    void 위도가_바뀌면_스냅샷을_다시_짓는다() {
        AdminPlaceUpsertRequest req = new AdminPlaceUpsertRequest(
                UNCHANGED.name(), UNCHANGED.introduction(), UNCHANGED.address(),
                LATITUDE + 0.001, UNCHANGED.longitude(), UNCHANGED.townId(),
                UNCHANGED.mainTagId(), UNCHANGED.option1TagIds(), UNCHANGED.option2TagIds(),
                UNCHANGED.imageFileKeys(), UNCHANGED.contactNumber(), UNCHANGED.openingHours(),
                UNCHANGED.snsLinks(), UNCHANGED.placeCheckpoints());

        updateWith(req);

        assertFullRebuild();
    }

    @Test
    void 옵션_태그가_하나_늘면_스냅샷을_다시_짓는다() {
        AdminPlaceUpsertRequest req = new AdminPlaceUpsertRequest(
                UNCHANGED.name(), UNCHANGED.introduction(), UNCHANGED.address(),
                UNCHANGED.latitude(), UNCHANGED.longitude(), UNCHANGED.townId(),
                UNCHANGED.mainTagId(), UNCHANGED.option1TagIds(), List.of(OPTION2_TAG_ID),
                UNCHANGED.imageFileKeys(), UNCHANGED.contactNumber(), UNCHANGED.openingHours(),
                UNCHANGED.snsLinks(), UNCHANGED.placeCheckpoints());

        updateWith(req);

        assertFullRebuild();
    }

    /**
     * 메인과 옵션이 자리를 맞바꾸면 대표 태그 <b>이름</b>은 바뀌지만 비트마스크는 그대로다 —
     * 필터 결과가 같으니 배열을 다시 지을 이유가 없고, 바뀐 이름은 표시값 패치가 옮긴다.
     */
    @Test
    void 태그_순서만_바뀌고_집합이_같으면_표시_경로로_간다() {
        AdminPlaceUpsertRequest req = new AdminPlaceUpsertRequest(
                UNCHANGED.name(), UNCHANGED.introduction(), UNCHANGED.address(),
                UNCHANGED.latitude(), UNCHANGED.longitude(), UNCHANGED.townId(),
                OPTION1_TAG_ID, List.of(MAIN_TAG_ID), UNCHANGED.option2TagIds(),
                UNCHANGED.imageFileKeys(), UNCHANGED.contactNumber(), UNCHANGED.openingHours(),
                UNCHANGED.snsLinks(), UNCHANGED.placeCheckpoints());

        updateWith(req);

        assertDisplayPatchOnly();
    }

    // === helpers ===

    /** 요청이 실제로 읽는 것만 스텁한다 — 남는 스텁은 strict stubs가 실패로 잡는다 */
    private void updateWith(AdminPlaceUpsertRequest req) {
        given(adminEntityLoader.getPlaceWithTown(PLACE_ID)).willReturn(place());
        given(adminEntityLoader.getTown(req.townId())).willReturn(town(req.townId()));
        given(adminEntityLoader.getTag(req.mainTagId())).willReturn(tag(req.mainTagId()));
        for (Long tagId : req.option1TagIds()) {
            given(adminEntityLoader.getTag(tagId)).willReturn(tag(tagId));
        }
        for (Long tagId : req.option2TagIds()) {
            given(adminEntityLoader.getTag(tagId)).willReturn(tag(tagId));
        }

        adminPlaceService.updatePlace(PLACE_ID, req);
    }

    /** upsert는 갈래를 가리지 않는다 — 이름이 place_stats의 칸이라 표시 수정도 행을 다시 짓는다 */
    private void assertDisplayPatchOnly() {
        verify(placeStatsRepository).upsertRowsForActivePlaces(List.of(PLACE_ID));
        verify(snapshotRefresher).patchPlaceViewAfterCommit(PLACE_ID);
        verify(snapshotRefresher, never()).refreshAfterCommit();
    }

    private void assertFullRebuild() {
        verify(placeStatsRepository).upsertRowsForActivePlaces(List.of(PLACE_ID));
        verify(snapshotRefresher).refreshAfterCommit();
        verify(snapshotRefresher, never()).patchPlaceViewAfterCommit(anyLong());
    }

    private static AdminPlaceUpsertRequest withName(AdminPlaceUpsertRequest req, String name) {
        return new AdminPlaceUpsertRequest(
                name, req.introduction(), req.address(), req.latitude(), req.longitude(),
                req.townId(), req.mainTagId(), req.option1TagIds(), req.option2TagIds(),
                req.imageFileKeys(), req.contactNumber(), req.openingHours(),
                req.snsLinks(), req.placeCheckpoints());
    }

    /** 동네 {@code TOWN_ID}에 메인 1 · 옵션1 2를 단 장소 */
    private static Place place() {
        Place place = Place.create(
                "원래이름", "소개", "주소", LATITUDE, LONGITUDE, "02-000-0000",
                "매일 09:00-18:00", Map.of(), List.of(), List.of(),
                town(TOWN_ID), User.builder().id(1L).build(), true,
                tag(MAIN_TAG_ID), List.of(tag(OPTION1_TAG_ID)), List.of());
        setId(place, PLACE_ID);
        return place;
    }

    private static Town town(long id) {
        return Town.builder().id(id).name("동네" + id).active(true).build();
    }

    private static Tag tag(long id) {
        return Tag.builder()
                .id(id)
                .name("태그" + id)
                .type(id == MAIN_TAG_ID ? TagType.MAIN : TagType.OPTION1)
                .active(true)
                .tagUsage(TagUsage.PLACE)
                .build();
    }

    /** id는 DB가 정하므로 빌더가 없는 {@code Place}에는 리플렉션 말고 심을 자리가 없다 */
    private static void setId(Place place, long id) {
        try {
            var field = Place.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(place, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
