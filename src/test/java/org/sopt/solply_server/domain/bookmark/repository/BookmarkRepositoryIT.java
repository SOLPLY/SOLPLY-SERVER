package org.sopt.solply_server.domain.bookmark.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(QueryDslConfig.class)
class BookmarkRepositoryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.flyway.placeholders.s3_env", () -> "test");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("decorator.datasource.enabled", () -> "false");
    }

    @Autowired
    BookmarkRepository bookmarkRepository;

    @Autowired
    EntityManager em;

    private Long userId;
    private Long townId;
    private Long placeA;   // 북마크 순서: A(가장 오래됨) → B(중간) → C(최신)
    private Long placeB;
    private Long placeC;
    private Long otherTownId;
    private Long otherTownPlace;

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
        List<Long> ids = bookmarkRepository.findBookmarkedPlaceIdsByTownOrdered(userId, townId);
        assertThat(ids).containsExactly(placeC, placeB, placeA); // 최신순
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
