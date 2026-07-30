package org.sopt.solply_server.domain.bookmark.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.sopt.solply_server.domain.bookmark.util.BookmarkTargetValidatorRegistry;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, BookmarkService.class})
class BookmarkRepositoryIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // 표시 카운트 보정의 핵심 주장이 "추가 쿼리 0"이라 발행 statement 수를 실측한다
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired
    BookmarkRepository bookmarkRepository;

    /**
     * row → Map 변환·캐스팅 층을 <b>실제 row로</b> 실행하기 위해 서비스를 빈으로 올린다.
     * 이 층만 아무 테스트도 밟지 않아, 캐스팅을 LocalDateTime.MIN으로 바꿔도 112개 테스트가
     * 전부 통과하던 구멍이 있었다 (레포지토리 IT는 row 타입만, 서비스 단위 테스트는 facade를 목킹).
     */
    @Autowired
    BookmarkService bookmarkService;

    /** getMyBookmarkTimesMap이 쓰지 않는 협력자 — 빈 생성을 위해서만 채운다 */
    @MockBean
    BookmarkTargetValidatorRegistry validatorRegistry;

    @MockBean
    EntityLoader entityLoader;

    @Autowired
    EntityManager em;

    @Autowired
    EntityManagerFactory emf;

    private Long userId;
    private Long townId;
    private Long placeA;   // 북마크 순서: A(가장 오래됨) → B(중간) → C(최신)
    private Long placeB;
    private Long placeC;
    private Long otherTownId;
    private Long otherTownPlace;
    private Long unbookmarkedPlace;

    private Long courseTownId;
    private Long otherCourseTownId;
    private Long courseA;  // 북마크 순서: A(가장 오래됨) → B(중간) → C(최신)
    private Long courseB;
    private Long courseC;
    private Long otherTownCourse;

    @BeforeEach
    void setUp() {
        // Flyway V2가 실데이터(towns/places/courses)를 넣어두므로 이를 픽스처로 사용.
        // 같은 동네의 active 장소 3개 + 다른 동네의 active 장소 1개를 고른다.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.id, p.town_id FROM places p
                WHERE p.active = true
                ORDER BY p.town_id, p.id
                """).getResultList();

        Map<Long, List<Long>> byTown = rows.stream().collect(Collectors.groupingBy(
                r -> ((Number) r[1]).longValue(),
                Collectors.mapping(r -> ((Number) r[0]).longValue(), Collectors.toList())));

        townId = byTown.entrySet().stream()
                .filter(e -> e.getValue().size() >= 3)
                .findFirst().orElseThrow().getKey();
        List<Long> places = byTown.get(townId);
        placeA = places.get(0);
        placeB = places.get(1);
        placeC = places.get(2);

        otherTownId = byTown.keySet().stream()
                .filter(t -> !t.equals(townId)).findFirst().orElseThrow();
        otherTownPlace = byTown.get(otherTownId).get(0);

        // 어느 유저도 북마크하지 않는 장소 (카운트 0건 검증용)
        List<Long> bookmarked = List.of(placeA, placeB, placeC, otherTownPlace);
        unbookmarkedPlace = rows.stream()
                .map(r -> ((Number) r[0]).longValue())
                .filter(id -> !bookmarked.contains(id))
                .findFirst().orElseThrow();

        // 같은 동네의 active 코스 3개 + 다른 동네의 active 코스 1개를 고른다.
        @SuppressWarnings("unchecked")
        List<Object[]> courseRows = em.createNativeQuery("""
                SELECT c.id, c.town_id FROM courses c
                WHERE c.active = true
                ORDER BY c.town_id, c.id
                """).getResultList();

        Map<Long, List<Long>> coursesByTown = courseRows.stream().collect(Collectors.groupingBy(
                r -> ((Number) r[1]).longValue(),
                Collectors.mapping(r -> ((Number) r[0]).longValue(), Collectors.toList())));

        courseTownId = coursesByTown.entrySet().stream()
                .filter(e -> e.getValue().size() >= 3)
                .findFirst().orElseThrow().getKey();
        List<Long> courses = coursesByTown.get(courseTownId);
        courseA = courses.get(0);
        courseB = courses.get(1);
        courseC = courses.get(2);

        otherCourseTownId = coursesByTown.keySet().stream()
                .filter(t -> !t.equals(courseTownId)).findFirst().orElseThrow();
        otherTownCourse = coursesByTown.get(otherCourseTownId).get(0);

        User user = User.create("bookmark-it@test.com");
        em.persist(user);
        em.flush();
        userId = user.getId();

        persistPlaceBookmarkAt(placeA, "2026-01-01T10:00:00");
        persistPlaceBookmarkAt(placeB, "2026-02-01T10:00:00");
        persistPlaceBookmarkAt(placeC, "2026-03-01T10:00:00");
        persistPlaceBookmarkAt(otherTownPlace, "2026-01-15T10:00:00");

        persistCourseBookmarkAt(courseA, "2026-01-01T10:00:00");
        persistCourseBookmarkAt(courseB, "2026-02-01T10:00:00");
        persistCourseBookmarkAt(courseC, "2026-03-01T10:00:00");
        persistCourseBookmarkAt(otherTownCourse, "2026-01-15T10:00:00");
    }

    /** created_at은 @CreatedDate라 직접 지정 불가 → 저장 후 native UPDATE로 고정 */
    private void persistPlaceBookmarkAt(Long placeId, String createdAt) {
        User user = em.find(User.class, userId);
        Bookmark b = Bookmark.create(user, BookmarkTargetType.PLACE, placeId);
        em.persist(b);
        em.flush();
        em.createNativeQuery("UPDATE bookmarks SET created_at = :ts WHERE id = :id")
                .setParameter("ts", LocalDateTime.parse(createdAt))
                .setParameter("id", b.getId())
                .executeUpdate();
        em.clear();
    }

    private void persistCourseBookmarkAt(Long courseId, String createdAt) {
        User user = em.find(User.class, userId);
        Bookmark b = Bookmark.create(user, BookmarkTargetType.COURSE, courseId);
        em.persist(b);
        em.flush();
        em.createNativeQuery("UPDATE bookmarks SET created_at = :ts WHERE id = :id")
                .setParameter("ts", LocalDateTime.parse(createdAt))
                .setParameter("id", b.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    void 동네별_북마크_장소_id를_최신순으로_반환한다() {
        List<Long> ids = bookmarkRepository.findBookmarkedPlaceIdsByTownsOrdered(userId, List.of(townId));
        assertThat(ids).containsExactly(placeC, placeB, placeA); // 최신순
    }

    @Test
    void 여러_동네의_북마크_장소_id를_최신순으로_반환한다() {
        // given: townId(placeA 1/1, placeB 2/1, placeC 3/1) + otherTownId(otherTownPlace 1/15)

        // when
        List<Long> ids = bookmarkRepository.findBookmarkedPlaceIdsByTownsOrdered(
                userId, List.of(townId, otherTownId));

        // then: 동네 경계 없이 북마크 최신순으로 병합된다
        assertThat(ids).containsExactly(placeC, placeB, otherTownPlace, placeA);
    }

    @Test
    void 장소별_북마크_수를_집계한다() {
        // given: placeA에 다른 유저의 북마크를 1개 더 추가 → placeA 2개, placeB 1개, unbookmarkedPlace 0개
        User other = User.create("bookmark-count-it@test.com");
        em.persist(other);
        em.persist(Bookmark.create(other, BookmarkTargetType.PLACE, placeA));
        em.flush();
        em.clear();

        // when
        Map<Long, Long> counts = bookmarkRepository
                .countByPlaceIds(List.of(placeA, placeB, unbookmarkedPlace)).stream()
                .collect(Collectors.toMap(
                        r -> ((Number) r[0]).longValue(),
                        r -> ((Number) r[1]).longValue()));

        // then
        assertThat(counts.get(placeA)).isEqualTo(2L);
        assertThat(counts.get(placeB)).isEqualTo(1L);
        assertThat(counts).doesNotContainKey(unbookmarkedPlace); // 0건은 행 없음
    }

    @Test
    void 동네별_최신_북마크_장소를_윈도우_함수로_반환한다() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bookmarkRepository.findLatestBookmarkedPlaceIdPerTown(userId);
        Map<Long, Long> latestByTown = rows.stream().collect(Collectors.toMap(
                r -> ((Number) r[0]).longValue(),
                r -> ((Number) r[1]).longValue()));
        assertThat(latestByTown)
                .containsEntry(townId, placeC)
                .containsEntry(otherTownId, otherTownPlace)
                .hasSize(2);
    }

    @Test
    void 내_북마크_시각_조회는_targetId를_Long_createdAt을_LocalDateTime으로_반환한다() {
        // 네이티브 쿼리에서 TINYINT(1)이 Boolean으로 와 ClassCastException이 난 전례가 있어
        // JPQL 프로젝션의 실제 런타임 타입을 못 박아둔다. 호출측 캐스팅이 이 검증에 기댄다.
        List<Object[]> rows = bookmarkRepository.findMyBookmarkTimesByTargetIds(
                userId, BookmarkTargetType.PLACE, List.of(placeA));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isInstanceOf(Long.class);
        assertThat(rows.get(0)[1]).isInstanceOf(LocalDateTime.class);
    }

    @Test
    void 내_북마크_시각을_대상별로_반환하고_북마크하지_않은_대상은_행이_없다() {
        List<Object[]> rows = bookmarkRepository.findMyBookmarkTimesByTargetIds(
                userId, BookmarkTargetType.PLACE, List.of(placeA, placeC, unbookmarkedPlace));

        Map<Long, LocalDateTime> times = rows.stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (LocalDateTime) r[1]));

        assertThat(times).containsOnlyKeys(placeA, placeC);
        assertThat(times.get(placeA)).isEqualTo(LocalDateTime.parse("2026-01-01T10:00:00"));
        assertThat(times.get(placeC)).isEqualTo(LocalDateTime.parse("2026-03-01T10:00:00"));
        assertThat(times).doesNotContainKey(unbookmarkedPlace); // 미북마크는 행 없음 = 여부 판정 겸용
    }

    @Test
    void 내_북마크_시각_조회는_다른_유저의_북마크를_섞지_않는다() {
        User other = User.create("bookmark-times-other-it@test.com");
        em.persist(other);
        em.persist(Bookmark.create(other, BookmarkTargetType.PLACE, unbookmarkedPlace));
        em.flush();
        em.clear();

        List<Object[]> rows = bookmarkRepository.findMyBookmarkTimesByTargetIds(
                userId, BookmarkTargetType.PLACE, List.of(placeA, unbookmarkedPlace));

        assertThat(rows.stream().map(r -> (Long) r[0]))
                .containsExactly(placeA); // 남이 누른 unbookmarkedPlace는 내 보정 대상이 아니다
    }

    @Test
    void 내_북마크_시각_조회는_타입_경계를_지킨다() {
        // 같은 id 값을 갖는 COURSE 북마크가 있어도 PLACE 조회에 섞이면 안 된다
        List<Object[]> rows = bookmarkRepository.findMyBookmarkTimesByTargetIds(
                userId, BookmarkTargetType.COURSE, List.of(courseA, placeA));

        assertThat(rows.stream().map(r -> (Long) r[0])).containsExactly(courseA);
    }

    @Test
    void 서비스가_실제_row를_생성_시각_그대로_맵에_담는다() {
        // 레포지토리 IT가 row 타입을, 서비스 단위 테스트가 배선을 덮지만 그 사이의
        // "row → Map 변환 + 캐스팅" 층은 실제 row로 실행되지 않았다. 값을 통째로 상수로
        // 바꿔도(예: LocalDateTime.MIN) 아무 테스트가 죽지 않던 구멍을 여기서 막는다.
        Map<Long, LocalDateTime> times = bookmarkService.getMyBookmarkTimesMap(
                userId, BookmarkTargetType.PLACE, List.of(placeA, placeC, unbookmarkedPlace));

        assertThat(times).containsOnlyKeys(placeA, placeC);
        assertThat(times.get(placeA)).isEqualTo(LocalDateTime.parse("2026-01-01T10:00:00"));
        assertThat(times.get(placeC)).isEqualTo(LocalDateTime.parse("2026-03-01T10:00:00"));
    }

    @Test
    void 서비스는_userId가_없거나_대상이_비면_조회하지_않고_빈_맵을_준다() {
        assertThat(bookmarkService.getMyBookmarkTimesMap(
                null, BookmarkTargetType.PLACE, List.of(placeA))).isEmpty();
        assertThat(bookmarkService.getMyBookmarkTimesMap(
                userId, BookmarkTargetType.PLACE, List.of())).isEmpty();
        assertThat(bookmarkService.getMyBookmarkTimesMap(
                userId, BookmarkTargetType.PLACE, null)).isEmpty();
    }

    @Test
    void 내_북마크_시각_조회는_statement를_1회만_발행한다() {
        // "추가 쿼리 0"의 근거 — 여부 조회(findBookmarkedTargetIdsByTargetIds)와 동일하게 1회다.
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

        stats.clear();
        bookmarkRepository.findBookmarkedTargetIdsByTargetIds(
                userId, BookmarkTargetType.PLACE, List.of(placeA, placeB, placeC));
        long statusOnly = stats.getPrepareStatementCount();

        stats.clear();
        bookmarkRepository.findMyBookmarkTimesByTargetIds(
                userId, BookmarkTargetType.PLACE, List.of(placeA, placeB, placeC));
        long withTimes = stats.getPrepareStatementCount();

        assertThat(statusOnly).isEqualTo(1L);
        assertThat(withTimes).isEqualTo(1L); // createdAt을 더 실어도 쿼리 수는 그대로
    }

    @Test
    void 동네별_북마크_코스_id를_최신순으로_반환한다() {
        List<Long> ids = bookmarkRepository.findBookmarkedCourseIdsByTownOrdered(userId, courseTownId);
        assertThat(ids).containsExactly(courseC, courseB, courseA); // 최신순
    }

    @Test
    void 동네별_최신_북마크_코스를_윈도우_함수로_반환한다() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bookmarkRepository.findLatestBookmarkedCourseIdPerTown(userId);
        Map<Long, Long> latestByTown = rows.stream().collect(Collectors.toMap(
                r -> ((Number) r[0]).longValue(),
                r -> ((Number) r[1]).longValue()));
        assertThat(latestByTown)
                .containsEntry(courseTownId, courseC)
                .containsEntry(otherCourseTownId, otherTownCourse)
                .hasSize(2);
    }
}
