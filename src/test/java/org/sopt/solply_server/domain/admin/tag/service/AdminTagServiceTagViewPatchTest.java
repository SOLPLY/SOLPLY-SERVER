package org.sopt.solply_server.domain.admin.tag.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagActivationRequest;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.repository.AdminTagRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.place.cache.SnapshotRefresher;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.AdminEntityLoader;

/**
 * 태그 어드민 셋(생성·수정·활성 토글)이 <b>예외 없이</b> 커밋 뒤 태그 맵을 다시 읽게 한다는 것.
 *
 * <p>규칙을 하나로 두는 값어치가 여기서 드러난다 — "이 경로는 이름을 안 바꾸니 빼도 된다"를
 * 한 번 허용하면, 빠뜨린 경로의 태그는 다음 타이머 회차(≤10분)까지 <b>옛 이름·옛 활성</b>으로
 * 나가고 그 사이 아무 오류도 나지 않는다.
 *
 * <p><b>어느 태그가 바뀌었는지는 넘기지 않는다.</b> 태그는 수십 행이라 훅이 맵을 통째로 다시
 * 읽으므로({@code SnapshotRefresher#refreshTagViewsAfterCommit}), 이 파일이 지키는 것은
 * <b>훅이 걸렸는가</b> 하나다 — 캐스케이드로 여러 태그가 함께 내려가도 마찬가지다.
 */
@ExtendWith(MockitoExtension.class)
class AdminTagServiceTagViewPatchTest {

    @Mock private AdminTagRepository adminTagRepository;
    @Mock private AdminEntityLoader adminEntityLoader;
    @Mock private AdminTagValidator adminTagValidator;
    @Mock private EntityManager entityManager;
    @Mock private SnapshotRefresher snapshotRefresher;

    @InjectMocks private AdminTagService adminTagService;

    private static final long TAG_ID = 7L;

    @Test
    void 태그를_만들면_그_자리에서_태그_맵을_다시_읽게_한다() {
        AdminTagUpsertRequest req = upsertRequest("새태그", true);
        given(adminTagRepository.save(any(Tag.class))).willReturn(tag("새태그", true));

        adminTagService.createTag(req);

        verify(snapshotRefresher).refreshTagViewsAfterCommit();
    }

    @Test
    void 태그를_수정하면_태그_맵을_다시_읽게_한다() {
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(tag("옛이름", true));

        adminTagService.updateTag(TAG_ID, upsertRequest("새이름", true));

        verify(snapshotRefresher).refreshTagViewsAfterCommit();
    }

    /**
     * <b>타입 변경은 거부한다.</b> MAIN↔OPTION이 갈리면 그 태그를 대표로 쓰던 장소의
     * {@code mainTagId}가 낡는데, 그 값은 표시 맵이 아니라 스냅샷과 함께 지어진다 — 맵만 고치면
     * 다음 전량 재빌드(≤10분)까지 대표 태그가 틀린 채 아무 오류도 나지 않는다. 막아 두면 스냅샷을
     * 다시 지을 이유 자체가 없어져, 태그 쓰기에는 전량 재빌드 경로가 남지 않는다.
     */
    @Test
    void 태그_타입을_바꾸려_하면_거부하고_스냅샷도_다시_짓지_않는다() {
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(tag("이름", true));
        AdminTagUpsertRequest typeChanged = new AdminTagUpsertRequest(
                TagType.OPTION1, null, "이름", true, null, TagUsage.PLACE);

        assertThatThrownBy(() -> adminTagService.updateTag(TAG_ID, typeChanged))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TAG_TYPE_IMMUTABLE);

        verify(snapshotRefresher, never()).refreshAfterCommit();
    }

    /** 표시값만 바뀐 수정에 전량이 돌면 안 된다 — 분리한 값이 사라진다 */
    @Test
    void 태그를_수정해도_전량_재빌드를_걸지_않는다() {
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(tag("옛이름", true));

        adminTagService.updateTag(TAG_ID, upsertRequest("새이름", true));

        verify(snapshotRefresher, never()).refreshAfterCommit();
    }

    /**
     * 내리는 쪽이 특히 중요하다 — 비활성 태그는 목록에서 대표 태그 이름을 <b>비워야</b> 하고,
     * 그 판정이 맵의 {@code active}로 이뤄진다.
     */
    @Test
    void 태그를_내려도_같은_훅이_돈다() {
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(tag("그대로인이름", true));
        given(adminTagRepository.findChildren(TAG_ID)).willReturn(List.of());

        adminTagService.toggleActive(TAG_ID, new AdminTagActivationRequest(false));

        verify(snapshotRefresher).refreshTagViewsAfterCommit();
    }

    /**
     * <b>캐스케이드로 자식이 몇 개 내려가든 훅은 한 번이다.</b> 맵을 통째로 다시 읽으므로 자식을
     * 따로 셀 것이 없다 — 세는 구조였을 때 하나를 빠뜨리면 그 자식이 대표인 장소는 다음 타이머
     * 회차까지 내려간 태그의 이름을 계속 달고 나갔다.
     */
    @Test
    void 부모를_내려_자식까지_내려가도_훅은_한_번이다() {
        given(adminEntityLoader.getTag(TAG_ID)).willReturn(tag(TAG_ID, "부모", true));
        given(adminTagRepository.findChildren(TAG_ID))
                .willReturn(List.of(tag(TAG_ID + 1, "자식", true)));
        given(adminTagRepository.findChildren(TAG_ID + 1))
                .willReturn(List.of(tag(TAG_ID + 2, "손자", true)));
        given(adminTagRepository.findChildren(TAG_ID + 2)).willReturn(List.of());

        adminTagService.toggleActive(TAG_ID, new AdminTagActivationRequest(false));

        verify(snapshotRefresher, times(1)).refreshTagViewsAfterCommit();
    }

    // === helpers ===

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
