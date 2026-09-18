package org.sopt.solply_server.domain.admin.place.service;

import static org.mockito.BDDMockito.given;
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
import org.sopt.solply_server.domain.place.cache.SnapshotViewPatcher;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataService;
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
 * 장소 수정이 캐시로 가는 길 — <b>갈래가 없다</b>는 것과 <b>커서를 끊을지는 어드민이 고른다</b>는
 * 것 둘을 지킨다.
 *
 * <p><b>갈래가 없다.</b> 어떤 칸을 고쳤든 place_stats 행을 다시 짓고 번호를 올린다
 * ({@code syncPlaceStats}). 이 파일은 원래 갈래를 지키던 자리였다 — 서비스가 수정 전후의 동네·
 * 좌표·태그 집합을 견주어 배열에 닿는 수정만 골라내던 시절, 그 판정이 어느 쪽으로 새도 조용했기
 * 때문에 갈림길을 값으로 세워 뒀다. 판정을 걷어낸 지금 그것들은 전부 <b>같은 답</b>을 내야 하고,
 * <b>구분이 되살아나면 빨개진다.</b>
 *
 * <p><b>판정을 걷어낸 이유는 주인이 하나여야 해서다.</b> 배열에 무엇이 실리는지는 리빌드가 원본을
 * 통째로 읽으며 정한다. 서비스가 같은 판정을 한 벌 더 들면 배열이 읽는 값이 하나 늘 때 두 곳을
 * 함께 고쳐야 하고, 한쪽을 빠뜨리면 <b>결과 집합이 조용히 틀린다</b> — 뗀 태그로 계속 검색되고,
 * 옮기기 전 동네 목록에 낀다.
 *
 * <p><b>커서 정책은 갈린다.</b> 번호 둘 중 revision은 언제나 오르고, cursorVersion은 요청이
 * {@code restartPlaceList}로 명시했을 때만 오른다.
 */
@ExtendWith(MockitoExtension.class)
class AdminPlaceServiceUpdateRoutingTest {

    @Mock private AdminPlaceRepository adminPlaceRepository;
    @Mock private PlaceStatsRepository placeStatsRepository;
    @Mock private SnapshotMetadataService snapshotMetadataService;
    @Mock private SnapshotViewPatcher snapshotViewPatcher;
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
            "02-000-0000", "매일 09:00-18:00", Map.of(), List.of(), null);

    /**
     * <b>표시값만 바뀐 수정도 같은 길로 간다.</b> 이름이 place_stats의 칸이 된 뒤로(V40) "표시값만
     * 고친 수정은 place_stats가 그대로"라는 전제 자체가 없다. 여기가 옛 표시 전용 경로로 다시
     * 갈라지면 배열은 바뀐 이름을 모른 채 남는다.
     */
    @Test
    void 이름만_바꿔도_번호를_올리고_표시값을_얹는다() {
        updateWith(withName(UNCHANGED, "바뀐이름"));

        assertMarksListChanged();
    }

    /**
     * <b>동네 이동도 같은 길이다.</b> 옛 구조에서 이 칸은 "배열에 닿는다"를 뜻해 전량 재빌드로
     * 갈라지던 자리였다 — 지금은 어느 칸이든 번호 하나를 올리고, 배열에 무엇이 닿는지는 리빌드가
     * 원본에서 정한다.
     */
    @Test
    void 동네가_바뀌어도_같은_길로_간다() {
        updateWith(withTown(UNCHANGED, OTHER_TOWN_ID));

        assertMarksListChanged();
    }

    /**
     * <b>태그 집합이 바뀌어도 같은 길이다.</b> 태그는 {@code clearTags} → flush → 다시 달기라
     * 다른 칸과 코드 경로가 갈리는데, 그 경로에서도 하는 일은 같다.
     */
    @Test
    void 옵션_태그가_하나_늘어도_같은_길로_간다() {
        updateWith(withOption2(UNCHANGED, List.of(OPTION2_TAG_ID)));

        assertMarksListChanged();
    }

    /**
     * <b>동네를 옮겨도 커서는 살아 있다 — 어드민이 그러라고 하지 않는 한.</b> 동네 이동은 목록의
     * 소속을 바꾸는 큰 변경이지만, 그렇다고 자동으로 스크롤을 끊지 않는다는 것이 계약이다
     * ({@code SnapshotCursorPolicy}).
     */
    @Test
    void 기본은_스크롤_유지다() {
        updateWith(withTown(UNCHANGED, OTHER_TOWN_ID));

        verify(snapshotMetadataService).markChanged(SnapshotCursorPolicy.PRESERVE);
    }

    /** 어드민이 명시로 고르면 그때 목록을 새로 시작시킨다. */
    @Test
    void 요청이_명시하면_새_목록으로_전환한다() {
        updateWith(withRestart(withName(UNCHANGED, "바뀐이름")));

        verify(snapshotMetadataService).markChanged(SnapshotCursorPolicy.ADVANCE);
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

    /**
     * 수정 한 번이 남기는 것 — place_stats 행 다시 짓기, 번호 올리기, 그리고 그 장소의 표시값을
     * 커밋 직후 메모리에 얹기 ({@code AdminPlaceService#syncPlaceStats}).
     */
    private void assertMarksListChanged() {
        verify(placeStatsRepository).upsertRowsForActivePlaces(List.of(PLACE_ID));
        verify(snapshotViewPatcher).patchPlacesAfterCommit(List.of(PLACE_ID));
    }

    private static AdminPlaceUpsertRequest withName(AdminPlaceUpsertRequest req, String name) {
        return copy(req, name, req.townId(), req.option2TagIds(), req.restartPlaceList());
    }

    private static AdminPlaceUpsertRequest withTown(AdminPlaceUpsertRequest req, long townId) {
        return copy(req, req.name(), townId, req.option2TagIds(), req.restartPlaceList());
    }

    private static AdminPlaceUpsertRequest withOption2(
            AdminPlaceUpsertRequest req, List<Long> option2TagIds) {
        return copy(req, req.name(), req.townId(), option2TagIds, req.restartPlaceList());
    }

    private static AdminPlaceUpsertRequest withRestart(AdminPlaceUpsertRequest req) {
        return copy(req, req.name(), req.townId(), req.option2TagIds(), true);
    }

    private static AdminPlaceUpsertRequest copy(AdminPlaceUpsertRequest req, String name,
            Long townId, List<Long> option2TagIds, Boolean restartPlaceList) {
        return new AdminPlaceUpsertRequest(
                name, req.introduction(), req.address(), req.latitude(), req.longitude(),
                townId, req.mainTagId(), req.option1TagIds(), option2TagIds,
                req.imageFileKeys(), req.contactNumber(), req.openingHours(),
                req.snsLinks(), req.placeCheckpoints(), restartPlaceList);
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
