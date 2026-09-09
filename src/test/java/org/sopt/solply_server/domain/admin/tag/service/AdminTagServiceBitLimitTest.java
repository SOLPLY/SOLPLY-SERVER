package org.sopt.solply_server.domain.admin.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.repository.AdminTagRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.place.cache.SnapshotRefresher;
import org.sopt.solply_server.domain.place.util.TagBitmask;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.AdminEntityLoader;

/**
 * 태그 생성의 <b>비트 자리 상한 가드</b>.
 *
 * <p>V34부터 태그 id가 곧 {@code place_stats.tag_bitmask}의 비트 자리라, 62를 넘는 id가 생기면
 * 목록 조회의 태그 필터가 성립하지 않는다. 그때 <b>조용히 틀린 결과가 나가면 안 된다</b>는 것이
 * 이 가드의 존재 이유이므로, 여기서 검증하는 것은 "거부한다"는 사실 자체다.
 *
 * <p>실제 id는 auto-increment가 정하므로 <b>저장한 뒤</b>에야 판정할 수 있다. 그 형태를 그대로
 * 밟기 위해 저장 결과의 id만 갈아 끼운다.
 */
@ExtendWith(MockitoExtension.class)
class AdminTagServiceBitLimitTest {

    @Mock private AdminTagRepository adminTagRepository;
    @Mock private AdminEntityLoader adminEntityLoader;
    @Mock private AdminTagValidator adminTagValidator;
    @Mock private EntityManager entityManager;
    @Mock private SnapshotRefresher snapshotRefresher;

    @InjectMocks private AdminTagService adminTagService;

    private static final AdminTagUpsertRequest REQUEST = new AdminTagUpsertRequest(
            TagType.MAIN, null, "새메인태그", true, null, TagUsage.PLACE);

    @Test
    void 상한_안의_id를_받으면_그대로_통과한다() {
        given(adminTagRepository.save(any(Tag.class)))
                .willReturn(savedTagWithId(TagBitmask.MAX_TAG_ID));

        assertThatCode(() -> adminTagService.createTag(REQUEST)).doesNotThrowAnyException();
    }

    /**
     * 여기서 거부하지 않으면 다음 사고는 <b>이 태그가 붙은 장소가 다른 태그로 검색되는 것</b>이고,
     * 그 시점에는 원인이 태그 생성이라는 사실이 남아 있지 않다.
     */
    @Test
    void 상한을_넘는_id를_받으면_거부한다() {
        given(adminTagRepository.save(any(Tag.class)))
                .willReturn(savedTagWithId(TagBitmask.MAX_TAG_ID + 1));

        assertThatThrownBy(() -> adminTagService.createTag(REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TAG_ID_BIT_LIMIT_EXCEEDED);
    }

    /** id는 DB가 정하는 값이라 리플렉션 말고는 심을 자리가 없다 — 그 사실 자체가 가드의 전제다. */
    private Tag savedTagWithId(long id) {
        Tag tag = Tag.create("새메인태그", TagType.MAIN, null, true, TagUsage.PLACE);
        try {
            var field = Tag.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tag, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        assertThat(tag.getId()).isEqualTo(id);
        return tag;
    }
}
