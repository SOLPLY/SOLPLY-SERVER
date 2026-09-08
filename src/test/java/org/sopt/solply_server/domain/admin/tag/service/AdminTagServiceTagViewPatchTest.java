package org.sopt.solply_server.domain.admin.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagActivationRequest;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.repository.AdminTagRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshotRefresher;
import org.sopt.solply_server.domain.place.cache.TagView;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.global.util.AdminEntityLoader;

/**
 * 태그 어드민 셋(생성·수정·활성 토글)이 <b>예외 없이</b> 태그 맵을 고친다는 것.
 *
 * <p>규칙을 하나로 두는 값어치가 여기서 드러난다 — "이 경로는 이름을 안 바꾸니 빼도 된다"를
 * 한 번 허용하면, 빠뜨린 경로의 태그는 다음 전량 재빌드(≤10분)까지 <b>옛 이름·옛 활성</b>으로
 * 나가고 그 사이 아무 오류도 나지 않는다.
 *
 * <p>맵에 실리는 값이 <b>저장된 값과 같은지</b>까지 본다. 훅을 부르기만 하고 엉뚱한 값을 넘기면
 * 화면은 조용히 갈린다.
 */
@ExtendWith(MockitoExtension.class)
class AdminTagServiceTagViewPatchTest {

    @Mock private AdminTagRepository adminTagRepository;
    @Mock private AdminEntityLoader adminEntityLoader;
    @Mock private AdminTagValidator adminTagValidator;
    @Mock private EntityManager entityManager;
    @Mock private PlaceListSnapshotRefresher placeListSnapshotRefresher;

    @InjectMocks private AdminTagService adminTagService;

    @Captor private ArgumentCaptor<TagView> tagViewCaptor;

    private static final long TAG_ID = 7L;

    @Test
    void 태그를_만들면_그_자리에서_맵에_넣는다() {
        AdminTagUpsertRequest req = upsertRequest("새태그", true);
        given(adminTagRepository.save(any(Tag.class))).willReturn(tag("새태그", true));

        adminTagService.createTag(req);

        assertThat(patchedView()).isEqualTo(new TagView(TAG_ID, "새태그", true));
    }

    @Test
    void 태그를_수정하면_바뀐_이름과_활성이_맵으로_간다() {
        Tag tag = tag("옛이름", true);
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(tag);

        adminTagService.updateTag(TAG_ID, upsertRequest("새이름", true));

        assertThat(patchedView()).isEqualTo(new TagView(TAG_ID, "새이름", true));
    }

    /**
     * 내리는 쪽이 특히 중요하다 — 비활성 태그는 목록에서 대표 태그 이름을 <b>비워야</b> 하고,
     * 그 판정이 맵의 {@code active}로 이뤄진다.
     */
    @Test
    void 태그를_내리면_이름은_그대로_활성만_내려간_값이_맵으로_간다() {
        Tag tag = tag("그대로인이름", true);
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(tag);
        given(adminTagRepository.findChildren(TAG_ID)).willReturn(List.of());

        adminTagService.toggleActive(TAG_ID, new AdminTagActivationRequest(false));

        assertThat(patchedView()).isEqualTo(new TagView(TAG_ID, "그대로인이름", false));
    }

    /**
     * 캐스케이드로 <b>함께 내려간 자식</b>도 맵에 들어가야 한다. 부모만 넣으면 그 자식이 대표
     * 태그인 장소는 다음 전량 재빌드까지 내려간 태그의 이름을 계속 달고 나간다 — 조용히 틀린다.
     */
    @Test
    void 부모를_내리면_함께_내려간_자식도_맵으로_간다() {
        Tag parent = tag(TAG_ID, "부모", true);
        Tag child = tag(TAG_ID + 1, "자식", true);
        Tag grandChild = tag(TAG_ID + 2, "손자", true);
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(parent);
        given(adminTagRepository.findChildren(TAG_ID)).willReturn(List.of(child));
        given(adminTagRepository.findChildren(TAG_ID + 1)).willReturn(List.of(grandChild));
        given(adminTagRepository.findChildren(TAG_ID + 2)).willReturn(List.of());

        adminTagService.toggleActive(TAG_ID, new AdminTagActivationRequest(false));

        assertThat(patchedViews()).containsExactlyInAnyOrder(
                new TagView(TAG_ID, "부모", false),
                new TagView(TAG_ID + 1, "자식", false),
                new TagView(TAG_ID + 2, "손자", false));
    }

    // === helpers ===

    private TagView patchedView() {
        verify(placeListSnapshotRefresher).patchTagViewAfterCommit(tagViewCaptor.capture());
        return tagViewCaptor.getValue();
    }

    private List<TagView> patchedViews() {
        verify(placeListSnapshotRefresher, atLeastOnce())
                .patchTagViewAfterCommit(tagViewCaptor.capture());
        return tagViewCaptor.getAllValues();
    }

    private static AdminTagUpsertRequest upsertRequest(String name, boolean active) {
        return new AdminTagUpsertRequest(TagType.MAIN, null, name, active, null, TagUsage.PLACE);
    }

    private static Tag tag(String name, boolean active) {
        return tag(TAG_ID, name, active);
    }

    /** id는 DB가 정하므로 심을 자리가 빌더뿐이다 */
    private static Tag tag(long id, String name, boolean active) {
        return Tag.builder()
                .id(id)
                .name(name)
                .type(TagType.MAIN)
                .active(active)
                .tagUsage(TagUsage.PLACE)
                .build();
    }
}
