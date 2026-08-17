package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor.DeltaResult;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 북마크 카운트 아웃박스의 <b>소비 측</b> 계약을 실제 MySQL로 못 박는다 — 접기·바닥·멱등성,
 * 그리고 전량 재계산 회차와의 관계.
 *
 * <p>발행 규칙("행이 실제로 바뀐 만큼만 전표가 남는가")은 {@code BookmarkCountEventPublishIT}의
 * 몫이라 여기서는 전표를 SQL로 직접 심는다. 그래야 배치가 실재하지 않는 조합(합이 0, PLACE가
 * 아닌 전표, 행 없는 장소)까지 견디는지 볼 수 있다.
 *
 * <p><b>기준 카운트를 {@link PlaceStatsBatchProcessor#rebuildRowsFromSource}로 세우고 전표는 그
 * 뒤에 심는 순서가 픽스처의 계약이다.</b> 그 문장도 아웃박스를 비우므로, 뒤집으면 심어 둔 전표가
 * 조용히 사라져 테스트가 아무것도 보지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, BookmarkCountDeltaProcessor.class, PlaceStatsBatchProcessor.class,
        PlaceStatsProperties.class})
class BookmarkCountDeltaProcessorIT extends MySqlContainerSupport {

    /** 안전망 회차의 기준 시각. 픽스처 북마크의 created_at을 이 시각보다 앞에 둔다. */
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 8, 17, 1, 45, 0);

    @DynamicPropertySource
    static void deltaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired BookmarkCountDeltaProcessor deltaProcessor;
    @Autowired PlaceStatsBatchProcessor batchProcessor;
    @Autowired PlaceStatsRepository placeStatsRepository;
    @Autowired EntityManager em;

    private int userSeq;
    private long placeA;
    private long placeB;

    @BeforeEach
    void setUp() {
        List<?> placeIds = em.createNativeQuery(
                "SELECT p.id FROM places p WHERE p.active = true ORDER BY p.id LIMIT 2")
                .getResultList();
        placeA = ((Number) placeIds.get(0)).longValue();
        placeB = ((Number) placeIds.get(1)).longValue();
        em.createNativeQuery("DELETE FROM place_stats").executeUpdate();
        em.createNativeQuery("DELETE FROM bookmark_count_events").executeUpdate();
    }

    // === 접기와 적용 ===

    /**
     * 회차의 뼈대 — <b>대상별 합으로 접어 한 번에 더하고, 읽은 전표를 지운다.</b>
     * 전표 3장이 UPDATE 한 번으로 접히는지는 값으로만 드러나므로, 장소를 둘로 갈라
     * 서로의 합에 섞이지 않는 것까지 함께 본다.
     */
    @Test
    void 전표를_장소별_합으로_접어_더하고_읽은_전표를_지운다() {
        givenStatsRows();
        setBookmarkCount(placeA, 10);
        setBookmarkCount(placeB, 3);
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);
        insertEvent(BookmarkTargetType.PLACE, placeA, -1);
        insertEvent(BookmarkTargetType.PLACE, placeB, -1);

        DeltaResult result = deltaProcessor.consumeAndApply();

        assertThat(result.consumedEvents()).isEqualTo(4);
        assertThat(result.updatedPlaces()).isEqualTo(2);
        assertThat(bookmarkCountOf(placeA)).isEqualTo(11);
        assertThat(bookmarkCountOf(placeB)).isEqualTo(2);
        assertThat(remainingEvents()).isZero();
    }

    /**
     * <b>소비는 멱등이 아니라 일회다 — 그것이 요점이다.</b> 전표를 지우므로 같은 회차를 다시 돌리면
     * 더할 것이 없다. 삭제가 빠지면 두 번째 호출이 같은 델타를 또 더해 여기서 깨진다.
     */
    @Test
    void 같은_전표를_두_번_소비하지_않는다() {
        givenStatsRows();
        setBookmarkCount(placeA, 5);
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);
        deltaProcessor.consumeAndApply();

        DeltaResult second = deltaProcessor.consumeAndApply();

        assertThat(second.consumedEvents()).isZero();
        assertThat(second.updatedPlaces()).isZero();
        assertThat(bookmarkCountOf(placeA)).isEqualTo(6);
    }

    /**
     * <b>합이 0인 장소는 건드리지 않는다.</b> 등록·해제를 왕복한 사용자가 그 경우다.
     * 전표는 소비되지만 UPDATE는 나가지 않는다 — {@code updatedPlaces}가 그것을 드러낸다.
     */
    @Test
    void 합이_0인_장소는_갱신하지_않고_전표만_지운다() {
        givenStatsRows();
        setBookmarkCount(placeA, 7);
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);
        insertEvent(BookmarkTargetType.PLACE, placeA, -1);

        DeltaResult result = deltaProcessor.consumeAndApply();

        assertThat(result.consumedEvents()).isEqualTo(2);
        assertThat(result.updatedPlaces()).isZero();
        assertThat(bookmarkCountOf(placeA)).isEqualTo(7);
        assertThat(remainingEvents()).isZero();
    }

    /** 아웃박스가 비면 회차 전체가 no-op이다 — 토글이 없던 한 시간이 정확히 이 상태다. */
    @Test
    void 빈_아웃박스는_아무_일도_하지_않는다() {
        givenStatsRows();
        setBookmarkCount(placeA, 4);

        DeltaResult result = deltaProcessor.consumeAndApply();

        assertThat(result.consumedEvents()).isZero();
        assertThat(result.updatedPlaces()).isZero();
        assertThat(bookmarkCountOf(placeA)).isEqualTo(4);
    }

    /**
     * <b>{@code GREATEST(0, …)}가 표류의 바닥을 잡는다.</b> 합이 현재 카운트보다 크게 빠져도
     * 목록에 음수가 찍히지 않는다. 방어선을 지우면 −2가 되어 여기서 깨진다.
     */
    @Test
    void 빼는_합이_현재_카운트보다_커도_0_아래로_내려가지_않는다() {
        givenStatsRows();
        setBookmarkCount(placeA, 1);
        insertEvent(BookmarkTargetType.PLACE, placeA, -1);
        insertEvent(BookmarkTargetType.PLACE, placeA, -1);
        insertEvent(BookmarkTargetType.PLACE, placeA, -1);

        deltaProcessor.consumeAndApply();

        assertThat(bookmarkCountOf(placeA)).isZero();
    }

    /**
     * <b>PLACE가 아닌 전표는 합산에서 빠지되 삭제 목록에는 남는다.</b> 발행 측이 PLACE만 내므로
     * 지금은 실재하지 않지만, 생기더라도 매 회차 읽히기만 하며 영영 남는 쓰레기가 되면 안 된다.
     *
     * <p>대상 id를 장소 id와 같은 값으로 주는 것이 이 테스트의 요점이다 — 종류를 안 보고 접으면
     * 코스 전표가 같은 id의 장소 카운트를 부풀린다.
     */
    @Test
    void 코스_전표는_카운트에_섞이지_않고_함께_지워진다() {
        givenStatsRows();
        setBookmarkCount(placeA, 5);
        insertEvent(BookmarkTargetType.COURSE, placeA, 1);

        DeltaResult result = deltaProcessor.consumeAndApply();

        assertThat(result.consumedEvents()).isEqualTo(1);
        assertThat(result.updatedPlaces()).isZero();
        assertThat(bookmarkCountOf(placeA)).isEqualTo(5);
        assertThat(remainingEvents()).isZero();
    }

    /**
     * 행이 없는 장소(비활성·삭제)의 전표는 적용될 곳 없이 버려진다. 0행 갱신이 정상이라는 계약을
     * 못 박는다 — 예외로 터지면 그 장소 하나 때문에 회차 전체가 롤백된다.
     */
    @Test
    void 행이_없는_장소의_전표는_버려지고_회차는_계속된다() {
        givenStatsRows();
        setBookmarkCount(placeA, 2);
        deleteStatsRow(placeB);
        insertEvent(BookmarkTargetType.PLACE, placeB, 1);
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);

        DeltaResult result = deltaProcessor.consumeAndApply();

        assertThat(result.consumedEvents()).isEqualTo(2);
        assertThat(result.updatedPlaces()).isEqualTo(1);
        assertThat(bookmarkCountOf(placeA)).isEqualTo(3);
        assertThat(remainingEvents()).isZero();
    }

    // === 안전망 회차와의 관계 ===

    /**
     * <b>안전망은 원본 기준으로 되맞추고, 자기가 읽은 전표를 같은 트랜잭션에서 비운다.</b>
     * 비우지 않으면 이미 셈에 들어간 토글을 뒤이은 델타 회차가 또 더한다 — 그 이중 반영이 없다는
     * 것까지 한 무대에서 봐야 계약이 성립한다.
     *
     * <p>표류를 일부러 심어 두는 것이 픽스처의 요점이다: 원본에는 북마크 1건뿐인데 카운트를 99로
     * 어긋나게 해 두고, 미소비 전표도 남겨 둔다. 재계산이 돌면 99는 1이 되어야 한다.
     */
    @Test
    void 안전망_회차는_원본으로_되맞추고_아웃박스를_비운다() {
        givenStatsRows();
        insertBookmark(placeA);
        setBookmarkCount(placeA, 99);
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);

        batchProcessor.recalculateCountsAndClearOutbox(CALCULATED_AT);

        assertThat(bookmarkCountOf(placeA)).isEqualTo(1);
        assertThat(remainingEvents()).isZero();

        // 뒤이은 델타 회차가 이미 반영된 토글을 또 더하지 않는다
        DeltaResult after = deltaProcessor.consumeAndApply();
        assertThat(after.consumedEvents()).isZero();
        assertThat(bookmarkCountOf(placeA)).isEqualTo(1);
    }

    /**
     * 기동 백필·운영 복구도 같은 규칙을 진다 — 원본에서 카운트를 다시 짓는 트랜잭션은 예외 없이
     * 아웃박스를 비운다. 이 진입점이 규칙에서 빠지면 부팅 직후 첫 델타 회차가 이중 반영을 낸다.
     */
    @Test
    void 원본_재구축도_아웃박스를_비운다() {
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);

        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);

        assertThat(remainingEvents()).isZero();
    }

    /** 위 테스트의 짝 — 가드를 앞세운 최초 적재 진입점도 같은 규칙을 진다 (자기 호출 우회 때문에 코드가 갈려 있다) */
    @Test
    void 최초_적재_진입점도_아웃박스를_비운다() {
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);

        batchProcessor.recalculateCountsIfEmpty(CALCULATED_AT);

        assertThat(remainingEvents()).isZero();
    }

    /**
     * 짝이 되는 반대 방향 — <b>재계산을 건너뛴 회차는 전표도 건드리지 않는다.</b> 가드에 걸려
     * 아무것도 안 셌는데 전표를 비우면 그 델타가 통째로 유실된다.
     */
    @Test
    void 최초_적재를_건너뛰면_전표를_지우지_않는다() {
        givenStatsRows();
        insertEvent(BookmarkTargetType.PLACE, placeA, 1);

        batchProcessor.recalculateCountsIfEmpty(CALCULATED_AT);

        assertThat(remainingEvents()).isEqualTo(1);
    }

    // === 리뷰 축의 소유권 ===

    /**
     * <b>매시 회차의 리뷰 축은 {@code bookmark_count}를 건드리지 않는다.</b> 북마크 축의 주인이
     * 델타 소비로 넘어갔으므로, 리뷰 문장이 그 칸을 함께 세면 회차마다 델타가 방금 더한 값을
     * 전량 스캔 결과로 되돌린다.
     *
     * <p>원본 북마크와 어긋난 값을 일부러 심어 두는 것이 요점이다 — 원본이 0건인데 카운트가 42면,
     * 리뷰 문장이 북마크 축을 건드리는 순간 0으로 무너진다.
     */
    @Test
    void 리뷰_축_재계산은_북마크_카운트를_덮지_않는다() {
        givenStatsRows();
        setBookmarkCount(placeA, 42);
        insertReview(placeA, 4);

        batchProcessor.recalculateReviewCounts(CALCULATED_AT);

        em.clear();
        PlaceStats stats = placeStatsRepository.findById(placeA).orElseThrow();
        assertThat(stats.getBookmarkCount()).isEqualTo(42);
        // 리뷰 축은 실제로 갱신됐다 — 아무것도 안 한 상태와 구분한다
        assertThat(stats.getReviewCount()).isEqualTo(1);
        assertThat(stats.getAvgRating().doubleValue()).isEqualTo(4.0);
    }

    // === 픽스처 ===

    /**
     * 활성 장소 전량에 카운트 0인 행을 세운다. 운영에서 행을 만드는 주체는 어드민 쓰기
     * 트랜잭션이고, 여기서는 그 대역인 원본 재구축 문장을 쓴다.
     *
     * <p><b>전표를 심기 전에 불러야 한다</b> — 이 문장도 아웃박스를 비운다.
     */
    private void givenStatsRows() {
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        em.clear();
    }

    /** 표류·기준값을 직접 심는다. 배치를 거치면 원본과 어긋난 상태를 만들 수 없다. */
    private void setBookmarkCount(long placeId, int count) {
        em.createNativeQuery(
                "UPDATE place_stats SET bookmark_count = :count WHERE place_id = :placeId")
                .setParameter("count", count)
                .setParameter("placeId", placeId)
                .executeUpdate();
        em.clear();
    }

    private void deleteStatsRow(long placeId) {
        em.createNativeQuery("DELETE FROM place_stats WHERE place_id = :placeId")
                .setParameter("placeId", placeId)
                .executeUpdate();
        em.clear();
    }

    private void insertEvent(BookmarkTargetType targetType, long targetId, int delta) {
        em.createNativeQuery("""
                INSERT INTO bookmark_count_events (target_type, target_id, delta, created_at)
                VALUES (:targetType, :targetId, :delta, :createdAt)
                """)
                .setParameter("targetType", targetType.name())
                .setParameter("targetId", targetId)
                .setParameter("delta", delta)
                .setParameter("createdAt", CALCULATED_AT.minusMinutes(10))
                .executeUpdate();
    }

    private int bookmarkCountOf(long placeId) {
        em.clear();
        return placeStatsRepository.findById(placeId).orElseThrow().getBookmarkCount();
    }

    private long remainingEvents() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM bookmark_count_events")
                .getSingleResult()).longValue();
    }

    /**
     * 원본 북마크 1건. {@code uk_bookmark_user_target} 때문에 호출마다 새 유저를 만든다
     * ({@code PlaceStatsBatchProcessorIT}의 같은 헬퍼와 같은 이유).
     */
    private void insertBookmark(long placeId) {
        em.createNativeQuery("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (:userId, 'PLACE', :placeId, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("createdAt", CALCULATED_AT.minusDays(1))
                .executeUpdate();
    }

    private void insertReview(long placeId, int rating) {
        em.createNativeQuery("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (:userId, :placeId, :visitedAt, 'EVENING',
                        '델타 소비 검증용 리뷰 본문입니다.', :rating, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("visitedAt", CALCULATED_AT.toLocalDate())
                .setParameter("rating", rating)
                .setParameter("createdAt", CALCULATED_AT.minusDays(1))
                .executeUpdate();
    }

    /** nickname이 UNIQUE라 순번을 붙인다. users는 role 말고 NOT NULL 기본값 없는 칸이 없다 (V1). */
    private long createUser() {
        String nickname = "델타IT유저" + (++userSeq);
        em.createNativeQuery("INSERT INTO users (role, nickname) VALUES ('USER', :nickname)")
                .setParameter("nickname", nickname)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = :nickname")
                .setParameter("nickname", nickname)
                .getSingleResult()).longValue();
    }
}
