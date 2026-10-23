package org.sopt.solply_server.domain.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Flyway DDL과 JPA 엔티티 매핑의 정합을 실제 MySQL로 검증한다.
 *
 * <p>다른 IT들(BookmarkRepositoryIT, TownPlacesSnapshotLoaderIT)은 ddl-auto: none으로 돌아
 * 엔티티↔스키마 불일치를 전혀 잡지 못한다. 이 IT만 의도적으로 validate를 쓴다 —
 * 운영의 ddl-auto: validate와 같은 조건이라, 부팅을 막는 타입 불일치를 빌드에서 걸러낸다.
 * ddl-auto 값을 none으로 바꾸면 이 클래스의 존재 이유가 사라진다.
 *
 * <p><b>실패를 만났다면:</b> validate는 place_stats만이 아니라 <em>전 엔티티 모델</em>을 검증한다.
 * 이 IT가 유일하게 validate로 도는 탓에, place_stats와 무관한 엔티티의 매핑 실수도 여기서 터진다.
 * 예외 메시지의 테이블·컬럼명을 먼저 확인할 것 — place_stats가 아닐 수 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueryDslConfig.class) // @DataJpaTest가 스캔하는 QueryDSL 커스텀 리포지토리 impl들이 JPAQueryFactory를 요구한다
class PlaceStatsRepositoryIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    PlaceStatsRepository placeStatsRepository;

    @Autowired
    EntityManager em;

    /** 배치가 places에서 비정규화해 오는 값. 기댓값을 INSERT와 같은 출처에서 얻으려고 함께 읽는다. */
    private record PlaceRow(long id, long townId) {
    }

    /** Flyway V2 시드에서 실제 존재하는 장소 하나를 빌려 쓴다 (BookmarkRepositoryIT와 같은 관례) */
    private PlaceRow anyPlace() {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT p.id, p.town_id FROM places p WHERE p.active = true ORDER BY p.id LIMIT 1")
                .getSingleResult();
        return new PlaceRow(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue());
    }

    private static final LocalDateTime SCORE_CALCULATED_AT =
            LocalDateTime.of(2026, 7, 30, 1, 0, 0);

    @Test
    void 네이티브로_삽입한_행을_엔티티로_읽을_수_있다() {
        PlaceRow place = anyPlace();
        long placeId = place.id();

        insertStats(placeId);
        em.clear();

        List<PlaceStats> found = placeStatsRepository.findAllById(List.of(placeId));

        assertThat(found).hasSize(1);
        PlaceStats stats = found.get(0);
        assertThat(stats.getPlaceId()).isEqualTo(placeId);
        // places에서 비정규화해 온 값. validate는 타입만 보고 값 왕복은 못 잡으므로 직접 대조한다.
        assertThat(stats.getTownId()).isEqualTo(place.townId());
        assertThat(stats.getPopularScore()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(stats.getBookmarkCount()).isEqualTo(7);
        assertThat(stats.getReviewCount()).isEqualTo(2);
        assertThat(stats.getAvgRating()).isEqualByComparingTo(new BigDecimal("4.50"));
        assertThat(stats.getScoreCalculatedAt()).isEqualTo(SCORE_CALCULATED_AT);
    }

    /**
     * <b>PK가 place_id 하나다 (V32).</b> 버전 행 시절에는 같은 장소가 회차마다 한 행씩 살았고
     * 그것이 커서 세대 고정의 토대였다. 지금은 장소당 최신 행 하나이므로 같은 id를 두 번 넣으면
     * 중복 키로 터진다 — 복합 PK가 되살아나면 이 단언이 깨진다.
     */
    @Test
    void 같은_장소에_행은_하나뿐이다() {
        long placeId = anyPlace().id();

        insertStats(placeId);
        em.clear();

        assertThat(placeStatsRepository.count()).isEqualTo(1);
        assertThat(placeStatsRepository.findAllById(List.of(placeId))).hasSize(1);
    }

    /**
     * 채점 전 행을 구분하는 근거가 {@code score_calculated_at}의 NULL이라는 것을 못 박는다.
     * 기동 시 최초 채점 판정이 이 컬럼 하나에 걸려 있다
     * ({@code PlaceStatsBatchProcessor#recalculateScoresIfNeverScored}).
     */
    @Test
    void 채점_전_행은_score_calculated_at이_NULL이다() {
        long placeId = anyPlace().id();

        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, name, bookmark_count, review_count, avg_rating)
                SELECT p.id, p.town_id, p.created_at, p.name, 0, 0, 0
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("placeId", placeId)
                .executeUpdate();
        em.clear();

        PlaceStats stats = placeStatsRepository.findById(placeId).orElseThrow();
        assertThat(stats.getScoreCalculatedAt()).isNull();
        // 컬럼 DEFAULT가 0이라 "아직 채점 안 됨"과 "0점"이 값으로는 같다 — 그래서 위 NULL이 필요하다
        assertThat(stats.getPopularScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(placeStatsRepository.existsByScoreCalculatedAtNotNull()).isFalse();
    }

    @Test
    void 통계가_없는_장소는_빈_결과를_반환한다() {
        assertThat(placeStatsRepository.findAllById(List.of(anyPlace().id()))).isEmpty();
    }

    /**
     * <b>어드민의 삭제가 목록에서 장소를 빼는 유일한 경로다.</b> 두 정렬 모두 place_stats가 기준
     * 테이블이라 행이 남아 있는 동안 노출되고, 뒤를 받쳐 주던 배치의 잔행 삭제는 이제 없다.
     *
     * <p>두 번 불러 0을 확인하는 것은 재호출이 무해해야 하기 때문이다 — 어드민 경로가 이미 없는
     * 행을 지우는 상황(배치가 아직 행을 만들지 않은 장소)이 정상 흐름에 있다.
     */
    @Test
    void 즉시_삭제는_행을_지우고_두_번_불러도_무해하다() {
        long placeId = anyPlace().id();
        insertStats(placeId);
        em.clear();

        assertThat(placeStatsRepository.deleteByPlaceIds(List.of(placeId))).isEqualTo(1);
        assertThat(placeStatsRepository.findById(placeId)).isEmpty();
        assertThat(placeStatsRepository.deleteByPlaceIds(List.of(placeId))).isZero();
    }

    /**
     * 어드민 쓰기 경로가 표시값 다섯 칸까지 원본에서 짓는다는 것 (V40). 이 칸들이 비면 스냅샷의
     * 이름·좌표·대표 태그·썸네일이 통째로 빈다 — 재빌드가 {@code place_stats} 하나만 읽기 때문이다.
     */
    @Test
    void upsert는_이름과_좌표와_대표_태그를_원본에서_채운다() {
        long tagId = createTag("MAIN", true);
        long placeId = createPlace("표시값IT장소", 37.5, 127.0);
        linkTag(placeId, tagId);

        placeStatsRepository.upsertRowsForActivePlaces(List.of(placeId));
        em.clear();

        PlaceStats stats = placeStatsRepository.findById(placeId).orElseThrow();
        assertThat(stats.getName()).isEqualTo("표시값IT장소");
        assertThat(stats.getLatitude()).isEqualTo(37.5);
        assertThat(stats.getLongitude()).isEqualTo(127.0);
        assertThat(stats.getMainTagId()).isEqualTo(tagId);
    }

    /**
     * <b>MAIN 태그가 둘이면 {@code place_tag.id}가 작은 쪽이 대표다.</b> 먼저 붙인 쪽의 태그 id를
     * 일부러 더 크게 잡아, 규칙이 {@code MIN(pt.id)}가 아니라 태그 id 순으로 갈리면 여기서 드러나게 한다
     * ({@code PlaceListSnapshotLoaderIT}과 같은 수법).
     */
    @Test
    void MAIN_태그가_둘이면_place_tag_id가_작은_쪽이_대표_태그다() {
        long smallerTagId = createTag("MAIN", true);
        long biggerTagId = createTag("MAIN", true);
        long placeId = createPlace("MAIN둘IT장소", 37.5, 127.0);
        linkTag(placeId, biggerTagId);      // 먼저 붙인 쪽 = place_tag.id가 작다
        linkTag(placeId, smallerTagId);

        placeStatsRepository.upsertRowsForActivePlaces(List.of(placeId));
        em.clear();

        assertThat(placeStatsRepository.findById(placeId).orElseThrow().getMainTagId())
                .isEqualTo(biggerTagId);
    }

    /**
     * 대표 태그가 NULL이 되는 이유는 <b>MAIN 태그가 없는 것 하나뿐</b>이다. 태그의 활성 여부로 거르면
     * 그 다음 MAIN 태그가 뽑혀 엔티티 경로({@code Place#getMainTag})와 값이 갈린다 —
     * 활성 판정은 이름을 실을지 정하는 응답 조립의 몫이다.
     */
    @Test
    void MAIN_태그가_없으면_대표_태그는_NULL이고_비활성_MAIN은_id가_담긴다() {
        long optionTagId = createTag("OPTION1", true);
        long inactiveMainTagId = createTag("MAIN", false);
        long placeWithoutMain = createPlace("MAIN없음IT장소", 37.5, 127.0);
        long placeWithInactiveMain = createPlace("비활성MAIN IT장소", 37.6, 127.1);
        linkTag(placeWithoutMain, optionTagId);
        linkTag(placeWithInactiveMain, inactiveMainTagId);

        placeStatsRepository.upsertRowsForActivePlaces(
                List.of(placeWithoutMain, placeWithInactiveMain));
        em.clear();

        assertThat(placeStatsRepository.findById(placeWithoutMain).orElseThrow().getMainTagId())
                .isNull();
        assertThat(placeStatsRepository.findById(placeWithInactiveMain).orElseThrow().getMainTagId())
                .isEqualTo(inactiveMainTagId);
    }

    /**
     * <b>썸네일은 {@code display_order}가 가장 앞선 이미지의 파일 키 원값이다.</b> 정렬이 곧 선택
     * 규칙이라, 문장에서 {@code ORDER BY}가 빠지면 삽입 순서(여기서는 2번 이미지)가 뽑힌다 —
     * 그래서 일부러 역순으로 넣는다.
     */
    @Test
    void upsert는_display_order가_가장_앞선_이미지의_키를_썸네일로_채운다() {
        long placeId = createPlace("썸네일IT장소", 37.5, 127.0);
        insertImage(placeId, "썸네일IT_2번이미지", 2);
        insertImage(placeId, "썸네일IT_1번이미지", 1);

        placeStatsRepository.upsertRowsForActivePlaces(List.of(placeId));
        em.clear();

        assertThat(placeStatsRepository.findById(placeId).orElseThrow().getThumbnailFileKey())
                .isEqualTo("썸네일IT_1번이미지");
    }

    /**
     * <b>{@code display_order}가 같으면 {@code image_file_key} 값이 작은 쪽이다.</b>
     * {@code place_images}에는 대리키가 없고 {@code display_order}는 중복도 NULL도 허용하는데,
     * MySQL의 filesort는 안정 정렬이 아니라 같은 키의 행 순서가 실행 계획을 따라 바뀔 수 있다.
     * 타이브레이커가 빠지면 재빌드와 어드민 쓰기가 서로 다른 이미지를 고를 여지가 생긴다.
     */
    @Test
    void display_order가_동률이면_파일_키가_작은_쪽이_썸네일이다() {
        long placeId = createPlace("동률썸네일IT장소", 37.5, 127.0);
        insertImage(placeId, "동률IT_이미지B", 1);
        insertImage(placeId, "동률IT_이미지A", 1);

        placeStatsRepository.upsertRowsForActivePlaces(List.of(placeId));
        em.clear();

        assertThat(placeStatsRepository.findById(placeId).orElseThrow().getThumbnailFileKey())
                .isEqualTo("동률IT_이미지A");
    }

    /**
     * <b>"이미지 없음"(NULL)과 "첫 이미지의 키가 빔"(빈 문자열)은 다른 상태다.</b> 빈 키를 "없음"으로
     * 접어 다음 이미지로 넘어가면 엔티티 경로({@code Place#getThumbnailFileKey})와 값이 갈린다 —
     * 그 경우 응답의 URL을 null로 만드는 것은 {@code ImageUrlProvider}의 몫이다.
     */
    @Test
    void 이미지가_없으면_썸네일은_NULL이고_빈_키는_빈_키_그대로다() {
        long placeWithoutImage = createPlace("이미지없음IT장소", 37.5, 127.0);
        long placeWithBlankKey = createPlace("빈키IT장소", 37.6, 127.1);
        insertImage(placeWithBlankKey, "", 1);
        insertImage(placeWithBlankKey, "빈키IT_2번이미지", 2);

        placeStatsRepository.upsertRowsForActivePlaces(
                List.of(placeWithoutImage, placeWithBlankKey));
        em.clear();

        assertThat(placeStatsRepository.findById(placeWithoutImage).orElseThrow()
                .getThumbnailFileKey()).isNull();
        assertThat(placeStatsRepository.findById(placeWithBlankKey).orElseThrow()
                .getThumbnailFileKey()).isEmpty();
    }

    /**
     * <b>대입 목록이 겹치지 않는다는 계약이 표시 컬럼이 다섯 늘어난 뒤에도 그대로인지 본다.</b>
     * 어드민 소유 칸은 갱신되고, 카운트 배치·점수 배치 소유의 칸은 이 문장이 건드리지 않는다 —
     * 이름을 고쳤다고 북마크 수가 0으로 돌아가면 안 된다.
     */
    @Test
    void upsert는_표시_컬럼을_갱신하고_카운트와_점수는_그대로_둔다() {
        long tagId = createTag("MAIN", true);
        long placeId = createPlace("새이름IT장소", 37.5, 127.0);
        linkTag(placeId, tagId);
        insertImage(placeId, "새이름IT_이미지", 1);
        insertStaleStats(placeId);

        placeStatsRepository.upsertRowsForActivePlaces(List.of(placeId));
        em.clear();

        PlaceStats stats = placeStatsRepository.findById(placeId).orElseThrow();
        assertThat(stats.getName()).isEqualTo("새이름IT장소");
        assertThat(stats.getLatitude()).isEqualTo(37.5);
        assertThat(stats.getLongitude()).isEqualTo(127.0);
        assertThat(stats.getMainTagId()).isEqualTo(tagId);
        assertThat(stats.getThumbnailFileKey()).isEqualTo("새이름IT_이미지");
        assertThat(stats.getBookmarkCount()).isEqualTo(7);
        assertThat(stats.getReviewCount()).isEqualTo(2);
        assertThat(stats.getAvgRating()).isEqualByComparingTo(new BigDecimal("4.50"));
        assertThat(stats.getPopularScore()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(stats.getScoreCalculatedAt()).isEqualTo(SCORE_CALCULATED_AT);
    }

    /** 표시 다섯 칸이 낡은 행. upsert가 그 다섯만 갱신하고 나머지를 남기는지 보는 출발점이다. */
    private void insertStaleStats(long placeId) {
        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, name, latitude, longitude, main_tag_id,
                     thumbnail_file_key, popular_score, bookmark_count, review_count, avg_rating,
                     score_calculated_at)
                SELECT p.id, p.town_id, p.created_at, '옛이름', NULL, NULL, NULL,
                       '옛이미지', 12.5, 7, 2, 4.50, :scoreAt
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("scoreAt", SCORE_CALCULATED_AT)
                .setParameter("placeId", placeId)
                .executeUpdate();
    }

    /** 이 클래스는 롤백하므로 뒷정리가 필요 없다 */
    private void insertImage(long placeId, String fileKey, int displayOrder) {
        em.createNativeQuery("""
                INSERT INTO place_images (place_id, image_file_key, display_order)
                VALUES (:placeId, :fileKey, :displayOrder)
                """)
                .setParameter("placeId", placeId)
                .setParameter("fileKey", fileKey)
                .setParameter("displayOrder", displayOrder)
                .executeUpdate();
    }

    /** 활성 장소 하나를 시드 동네에 심는다. 이 클래스는 롤백하므로 뒷정리가 필요 없다. */
    private long createPlace(String name, double latitude, double longitude) {
        em.createNativeQuery("""
                INSERT INTO places (name, introduction, town_id, active, latitude, longitude)
                VALUES (:name, '통계IT', :townId, true, :lat, :lng)
                """)
                .setParameter("name", name)
                .setParameter("townId", anyPlace().townId())
                .setParameter("lat", latitude)
                .setParameter("lng", longitude)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM places").getSingleResult())
                .longValue();
    }

    /**
     * <b>태그 id를 auto-increment에 맡기지 않는다.</b> tag id가 곧 {@code tag_bitmask}의 비트 자리라
     * 62를 넘으면 안 되는데({@code TagBitmask}), auto-increment 카운터는 롤백해도 되돌아가지 않아
     * 같은 싱글턴 컨테이너를 나눠 쓰는 IT가 늘수록 상한에 다가간다. {@code MAX(id) + 1}은 롤백을 따라
     * 되돌아간다 ({@code PlaceListSnapshotLoaderIT}과 같은 관례).
     */
    private long createTag(String type, boolean active) {
        long tagId = ((Number) em.createNativeQuery(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags").getSingleResult()).longValue();
        em.createNativeQuery("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (:id, :name, :type, NULL, :active, 'PLACE')
                """)
                .setParameter("id", tagId)
                .setParameter("name", "통계IT태그" + tagId)
                .setParameter("type", type)
                .setParameter("active", active)
                .executeUpdate();
        return tagId;
    }

    private void linkTag(long placeId, long tagId) {
        em.createNativeQuery(
                "INSERT INTO place_tag (place_id, tag_id) VALUES (:placeId, :tagId)")
                .setParameter("placeId", placeId)
                .setParameter("tagId", tagId)
                .executeUpdate();
    }

    private void insertStats(long placeId) {
        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, name, popular_score, bookmark_count,
                     review_count, avg_rating, score_calculated_at)
                SELECT p.id, p.town_id, p.created_at, p.name, 12.5, 7, 2, 4.50, :scoreAt
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("scoreAt", SCORE_CALCULATED_AT)
                .setParameter("placeId", placeId)
                .executeUpdate();
    }
}
