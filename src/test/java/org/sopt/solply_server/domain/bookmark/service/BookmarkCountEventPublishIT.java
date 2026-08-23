package org.sopt.solply_server.domain.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 북마크 카운트 아웃박스의 <b>발행 규칙</b> IT — "행이 실제로 바뀐 만큼만 전표가 남는가"만 문다.
 * 소비(배치가 접어서 더하고 지우는 쪽)는 이 파일의 관심사가 아니다.
 *
 * <p>서비스 층을 지나는 이유가 규칙 자체다. 발행 조건이 유니크 제약 위반과 DELETE 영향 행 수라
 * 리포지토리를 직접 부르면 그 두 조건이 검증에서 통째로 빠진다.
 *
 * <p><b>단언은 전표 행을 직접 읽어서 한다.</b> 발행은 JPA로 하고 검증은 SQL로 하므로, 매핑이
 * 어긋나거나 트랜잭션이 커밋되지 않으면 여기서 잡힌다.
 */
@SpringBootTest
class BookmarkCountEventPublishIT extends MySqlContainerSupport {

    /**
     * 메서드 이름은 베이스의 {@code datasource}와 반드시 달라야 한다
     * ({@code @DynamicPropertySource}는 static이라 동명이면 상위를 <em>숨긴다</em>).
     *
     * <p>{@code validate}는 덤이 아니라 이 파일이 겸하는 검증이다 — V38 DDL과
     * {@code BookmarkCountEvent} 매핑이 어긋나면 컨텍스트 기동에서 죽는다.
     * 배치 스케줄 셋은 다른 IT와 같은 이유로 끈다({@code "-"} = 등록하지 않음). 카운트 회차 둘은
     * 특히 이 파일의 단언을 직접 지운다 — 소비가 곧 전표 삭제다.
     */
    @DynamicPropertySource
    static void publishProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    /** users.nickname UNIQUE — 같은 싱글턴 컨테이너를 쓰는 다른 IT의 접두사와 겹치면 안 된다 */
    private static final String USER_NICKNAME_PREFIX = "아웃박스IT유저";

    @Autowired private BookmarkService bookmarkService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static int userSeq = 0;

    private long userId;
    private long placeId;
    private long courseId;

    /**
     * 대상은 V2 시드의 장소·코스를 그대로 빌린다 — 발행 규칙은 대상의 생김새를 보지 않는다.
     *
     * <p>전표를 회차마다 비우는 이유는 다른 IT도 {@code BookmarkService}를 지나며 전표를 남기기
     * 때문이다. 단언 쪽도 {@code (target_type, target_id)}로 좁혀 두 겹으로 막는다 — 시드 장소와
     * 시드 코스는 id가 겹칠 수 있어 종류까지 봐야 한다.
     */
    @BeforeEach
    void setUp() {
        placeId = jdbcTemplate.queryForObject(
                "SELECT id FROM places WHERE active = true ORDER BY id LIMIT 1", Long.class);
        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM courses WHERE active = true ORDER BY id LIMIT 1", Long.class);
        userId = createUser();
        jdbcTemplate.update("DELETE FROM bookmark_count_events WHERE target_id IN (?, ?)",
                placeId, courseId);
    }

    @Test
    void 장소_북마크_등록은_플러스1_전표_한_장을_남긴다() {
        bookmarkService.create(userId, BookmarkTargetType.PLACE, placeId);

        List<Map<String, Object>> rows = eventRows(BookmarkTargetType.PLACE, placeId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("target_type", "PLACE");
        assertThat(((Number) rows.get(0).get("target_id")).longValue()).isEqualTo(placeId);
        assertThat(((Number) rows.get(0).get("delta")).intValue()).isEqualTo(1);
    }

    /** 409로 튕긴 요청은 행을 만들지 않았으므로 전표도 없어야 한다 — 있으면 카운트가 부풀어 오른다. */
    @Test
    void 중복_등록으로_409가_나면_전표를_더_남기지_않는다() {
        bookmarkService.create(userId, BookmarkTargetType.PLACE, placeId);

        assertThatThrownBy(() -> bookmarkService.create(userId, BookmarkTargetType.PLACE, placeId))
                .isInstanceOf(BusinessException.class);

        assertThat(deltas(BookmarkTargetType.PLACE, placeId)).containsExactly(1);
    }

    @Test
    void 장소_북마크_해제는_마이너스1_전표_한_장을_남긴다() {
        bookmarkService.create(userId, BookmarkTargetType.PLACE, placeId);

        bookmarkService.delete(userId, BookmarkTargetType.PLACE, placeId);

        // 등록·해제 왕복이 전표 두 장으로 남고, 합이 0이라 배치가 접으면 카운트가 제자리로 온다
        assertThat(deltas(BookmarkTargetType.PLACE, placeId)).containsExactly(1, -1);
    }

    /**
     * 없는 북마크 삭제는 no-op이다. 발행이 exists 검사가 아니라 <b>DELETE 영향 행 수</b>에 걸려
     * 있다는 계약이 여기서 드러난다 — 지운 것이 없으면 뺄 것도 없다.
     */
    @Test
    void 없는_북마크를_해제하면_전표가_없다() {
        bookmarkService.delete(userId, BookmarkTargetType.PLACE, placeId);

        assertThat(deltas(BookmarkTargetType.PLACE, placeId)).isEmpty();
    }

    /** 코스는 카운트를 노출하지 않으므로 전표를 내지 않는다 — 소비자 없는 전표는 배치가 읽고 버릴 쓰레기다. */
    @Test
    void 코스_북마크는_등록도_해제도_전표를_남기지_않는다() {
        bookmarkService.create(userId, BookmarkTargetType.COURSE, courseId);
        bookmarkService.delete(userId, BookmarkTargetType.COURSE, courseId);

        assertThat(deltas(BookmarkTargetType.COURSE, courseId)).isEmpty();
    }

    private List<Map<String, Object>> eventRows(BookmarkTargetType type, long targetId) {
        return jdbcTemplate.queryForList("""
                SELECT target_type, target_id, delta FROM bookmark_count_events
                WHERE target_type = ? AND target_id = ?
                ORDER BY id""", type.name(), targetId);
    }

    private List<Integer> deltas(BookmarkTargetType type, long targetId) {
        return jdbcTemplate.queryForList("""
                SELECT delta FROM bookmark_count_events
                WHERE target_type = ? AND target_id = ?
                ORDER BY id""", Integer.class, type.name(), targetId);
    }

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /**
     * 이 IT는 롤백되지 않으므로({@code @SpringBootTest}는 기본 커밋) 만든 행을 직접 지운다.
     * {@code @AfterAll}은 static이라 {@code @Autowired JdbcTemplate}에 닿을 수 없어 커넥션을 직접 연다
     * (근거는 {@code PlaceListFlowIT}의 같은 메서드).
     *
     * <p>북마크를 유저 기준으로 되찾는 것이 순서의 핵심이다 — 남기면 {@code fk_bookmarks_user}가
     * users 삭제를 막는다. 전표는 FK가 없지만(V38) 남겨 두면 다음 IT의 단언에 섞인다.
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myUsers =
                "SELECT id FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM bookmarks WHERE user_id IN (" + myUsers + ")");
            st.executeUpdate("DELETE FROM bookmark_count_events");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
        }
    }
}
