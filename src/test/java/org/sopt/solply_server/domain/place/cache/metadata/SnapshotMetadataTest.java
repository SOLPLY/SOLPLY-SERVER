package org.sopt.solply_server.domain.place.cache.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * <b>번호 쌍의 전순서</b>. 캐시 구조에서 "새것인가"를 묻는 모든 자리(설치 가드·폴러·패치 보관)가
 * 이 메서드 하나를 부르므로, 여기가 틀리면 그 전부가 같은 방향으로 틀린다.
 *
 * <p>함정은 {@code revision}이 회차가 오를 때 0으로 리셋된다는 것이다. 그래서 revision을 그대로
 * 대소 비교하면 집계 회차 직후의 스냅샷이 언제나 "낡았다"로 읽힌다 — 아래 {@code (13,0) > (12,57)}이
 * 그 회귀를 잡는 단언이다.
 */
class SnapshotMetadataTest {

    /** 같은 회차 안에서는 "몇 번째 변경인가"로 갈린다 */
    @Test
    void 같은_회차면_revision이_큰_쪽이_새것이다() {
        SnapshotMetadata later = new SnapshotMetadata(5L, 12L);
        SnapshotMetadata earlier = new SnapshotMetadata(4L, 12L);

        assertThat(later.isNewerThan(earlier)).isTrue();
        assertThat(earlier.isNewerThan(later)).isFalse();
    }

    /**
     * 회차가 다르면 revision은 보지 않는다. 회차를 건너 revision을 비교하는 것은 "13회차의 3번째"와
     * "12회차의 57번째"에서 3과 57을 재는 짓이라 뜻이 없다.
     */
    @Test
    void 회차가_다르면_revision은_보지_않는다() {
        SnapshotMetadata newerRound = new SnapshotMetadata(0L, 13L);
        SnapshotMetadata olderRound = new SnapshotMetadata(57L, 12L);

        assertThat(newerRound.isNewerThan(olderRound))
                .as("집계 회차가 리셋한 (13,0)이 (12,57)보다 새것이다").isTrue();
        assertThat(olderRound.isNewerThan(newerRound)).isFalse();
    }

    /** 같은 쌍은 이미 들고 있는 시점이다 — 다시 설치할 것이 없다 */
    @Test
    void 같은_쌍이면_새것이_아니다() {
        SnapshotMetadata metadata = new SnapshotMetadata(7L, 12L);

        assertThat(metadata.isNewerThan(new SnapshotMetadata(7L, 12L))).isFalse();
    }

    /**
     * {@link SnapshotMetadata#NOT_INSTALLED}는 어떤 실제 번호보다도 낡았다. 이것이 깨지면 아직
     * 아무것도 못 지은 인스턴스가 첫 스냅샷을 "낡았다"고 버리고 빈 목록으로 뜬다.
     */
    @Test
    void 아무것도_설치하지_않은_자리보다는_첫_번호가_새것이다() {
        SnapshotMetadata first = new SnapshotMetadata(0L, 0L);

        assertThat(first.isNewerThan(SnapshotMetadata.NOT_INSTALLED)).isTrue();
        assertThat(SnapshotMetadata.NOT_INSTALLED.isNewerThan(first)).isFalse();
    }
}
