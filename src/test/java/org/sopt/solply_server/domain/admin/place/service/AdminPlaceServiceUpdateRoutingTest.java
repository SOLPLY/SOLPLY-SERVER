package org.sopt.solply_server.domain.admin.place.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.place.cache.SnapshotRefresher;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
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
 * 장소 수정이 캐시로 가는 길 — <b>갈래가 없다</b>는 것이 이 파일이 지키는 전부다. 어떤 칸을
 * 고쳤든 place_stats 행을 다시 짓고 그 장소 id를 캐시로 넘긴다({@code syncPlaceStats}).
 *
 * <p><b>이 파일은 원래 갈래를 지키던 자리였다.</b> 서비스가 수정 전후의 동네·좌표·태그 집합을
 * 견주어 배열에 닿는 수정만 골라내던 시절, 그 판정이 어느 쪽으로 새도 조용했기 때문에 다섯 갈림길을
 * 값으로 세워 뒀다. 판정을 걷어낸 지금 그 다섯은 전부 <b>같은 답</b>을 내야 한다 — 그래서 남은
 * 테스트는 갈래를 구분하지 않고, 오히려 <b>구분이 되살아나면 빨개진다</b>.
 *
 * <p><b>판정을 걷어낸 이유는 주인이 하나여야 해서다.</b> 배열에 닿는 값이 무엇인지는 정렬 배열
 * ({@code SortedPlaces#patch})이 최신 행과 이 회차 엔트리를 견주며 이미 판정한다. 서비스가 같은
 * 판정을 한 벌 더 들면 배열이 읽는 값이 하나 늘 때 두 곳을 함께 고쳐야 하고, 한쪽을 빠뜨리면
 * <b>결과 집합이 조용히 틀린다</b> — 뗀 태그로 계속 검색되고, 옮기기 전 동네 목록에 낀다.
 *
 * <p><b>표시값만 고치는 경로는 여기 없다.</b> {@code patchPlaceViewAfterCommit}은 이미지 파일 키의
 * 비동기 후처리 하나가 쓰는 문이고({@code PlaceImageFieldUpdater}), 어드민 수정이 그리로 새면
 * 파생 컬럼이 낡은 채 목록에 남는다. 그래서 부르지 <b>않는다</b>는 것까지 함께 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
class AdminPlaceServiceUpdateRoutingTest {

    @Mock private AdminPlaceRepository adminPlaceRepository;
    @Mock private PlaceStatsRepository placeStatsRepository;
    @Mock private SnapshotRefresher snapshotRefresher;
    @Mock private SnapshotRebuildRequestRepository rebuildRequestRepository;
    @Mock private EntityManager entityManager;
    @Mock private ImageFileKeyValidator imageFileKeyValidator;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private ImageUrlProvider imageUrlProvider;
    @Mock private AdminTagValidator adminTagValidator;
    @Mock private AdminEntityLoader adminEntityLoader;

    @InjectMocks private AdminPlaceService adminPlaceService;

    /**
     * 재빌드 요청 번호는 쓰기 경로 어디서나 매겨진다 — 이 파일이 보는 것은 라우팅이지 번호가
     * 아니므로 느슨하게 깔아 두고, 번호가 훅까지 흘러가는지는 {@code assertForwardsPlaceId}가 본다.
     */
    @BeforeEach
    void givenRequestSeq() {
        lenient().when(rebuildRequestRepository.request()).thenReturn(MY_SEQ);
    }

    private static final long PLACE_ID = 100L;
    /** 이 트랜잭션이 자기 요청에 받은 번호 */
    private static final long MY_SEQ = 21L;
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

    /**
     * <b>표시값만 바뀐 수정도 같은 길로 간다.</b> 이름이 place_stats의 칸이 된 뒤로(V40) "표시값만
     * 고친 수정은 place_stats가 그대로"라는 전제 자체가 없다. 여기가 옛 표시 전용 경로로 다시
     * 갈라지면 배열은 바뀐 이름을 모른 채 남는다.
     */
    @Test
    void 이름만_바꿔도_장소_id를_캐시로_넘긴다() {
        updateWith(withName(UNCHANGED, "바뀐이름"));

        assertForwardsPlaceId();
    }

    /**
     * <b>동네 이동도 같은 길이다.</b> 옛 구조에서 이 칸은 "배열에 닿는다"를 뜻해 전량 재빌드로
     * 갈라지던 자리였다 — 지금은 id 하나를 넘기고, 배열에 닿는지는 패치가 판정한다.
     */
    @Test
    void 동네가_바뀌어도_같은_길로_간다() {
        AdminPlaceUpsertRequest req = new AdminPlaceUpsertRequest(
                UNCHANGED.name(), UNCHANGED.introduction(), UNCHANGED.address(),
                UNCHANGED.latitude(), UNCHANGED.longitude(), OTHER_TOWN_ID,
                UNCHANGED.mainTagId(), UNCHANGED.option1TagIds(), UNCHANGED.option2TagIds(),
                UNCHANGED.imageFileKeys(), UNCHANGED.contactNumber(), UNCHANGED.openingHours(),
                UNCHANGED.snsLinks(), UNCHANGED.placeCheckpoints());

        updateWith(req);

        assertForwardsPlaceId();
    }

    /**
     * <b>태그 집합이 바뀌어도 같은 길이다.</b> 태그는 {@code clearTags} → flush → 다시 달기라
     * 다른 칸과 코드 경로가 갈리는데, 그 경로에서도 넘기는 것은 id 하나다.
     */
    @Test
    void 옵션_태그가_하나_늘어도_같은_길로_간다() {
        AdminPlaceUpsertRequest req = new AdminPlaceUpsertRequest(
                UNCHANGED.name(), UNCHANGED.introduction(), UNCHANGED.address(),
                UNCHANGED.latitude(), UNCHANGED.longitude(), UNCHANGED.townId(),
                UNCHANGED.mainTagId(), UNCHANGED.option1TagIds(), List.of(OPTION2_TAG_ID),
                UNCHANGED.imageFileKeys(), UNCHANGED.contactNumber(), UNCHANGED.openingHours(),
                UNCHANGED.snsLinks(), UNCHANGED.placeCheckpoints());

        updateWith(req);

        assertForwardsPlaceId();
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
     * 수정 한 번이 남기는 것 — place_stats 행 다시 짓기와 그 id의 캐시 갱신, 둘이 한 몸이다
     * ({@code AdminPlaceService#syncPlaceStats}).
     *
     * <p>표시 전용 경로를 부르지 않는다는 것까지 함께 본다 — 갈래가 되살아나는 방향이 그쪽이다.
     */
    private void assertForwardsPlaceId() {
        verify(placeStatsRepository).upsertRowsForActivePlaces(List.of(PLACE_ID));
        verify(snapshotRefresher).refreshPlacesAfterCommit(List.of(PLACE_ID), MY_SEQ);
        verify(snapshotRefresher, never()).patchPlaceViewAfterCommit(anyLong(), anyLong());
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
