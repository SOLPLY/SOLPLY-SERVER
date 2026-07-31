package org.sopt.solply_server.domain.bookmark.repository;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.sopt.solply_server.domain.bookmark.entity.Bookmark;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    void deleteByUserIdAndTargetTypeAndTargetId(Long userId, BookmarkTargetType type, Long targetId);

    boolean existsByUserIdAndTargetTypeAndTargetId(Long userId, BookmarkTargetType type, Long targetId);

    @Query("""
        select b.targetId
        from Bookmark b
        join Course c on c.id = b.targetId
        where b.user.id = :userId
          and b.targetType = 'COURSE'
          and c.active = true
    """)
    Set<Long> findBookmarkedActiveCourseIds(@Param("userId") Long userId);

    @Query("""
        select b.targetId
        from Bookmark b
        where b.user.id = :userId
          and b.targetType = :targetType
          and b.targetId in :targetIds
    """)
    Set<Long> findBookmarkedTargetIdsByTargetIds(
            @Param("userId") Long userId,
            @Param("targetType") BookmarkTargetType targetType,
            @Param("targetIds") List<Long> targetIds);

    @Query("""
        select b.targetId
        from Bookmark b
        where b.user.id = :userId
          and b.targetType = :type
          and b.createdAt >= :since
    """)
    List<Long> findTargetIdsByUserAndTypeSince(Long userId, BookmarkTargetType type, LocalDateTime since);

    // == DB 직행 조회 쿼리 (캐시 제거 후 단일 경로) == //

    /**
     * 장소별 북마크 수 집계 (타겟 축 커버링 인덱스). row: [target_id, cnt]. 0건인 장소는 행 없음
     *
     * <p>프로덕션 읽기 경로는 place_stats로 전환됐고, 이 쿼리는 기각한 대안(매 요청 실시간 집계)의
     * 비용 측정용으로 남긴다.
     */
    @Query(value = """
        SELECT b.target_id, COUNT(*) AS cnt
        FROM bookmarks b
        WHERE b.target_type = 'PLACE'
          AND b.target_id IN (:placeIds)
        GROUP BY b.target_id
    """, nativeQuery = true)
    List<Object[]> countByPlaceIds(@Param("placeIds") List<Long> placeIds);

    /** 여러 동네의 북마크 장소 id를 최신순으로 반환 (시 단위 = leaf 합집합 조회용) */
    @Query(value = """
        SELECT b.target_id
        FROM bookmarks b
        INNER JOIN places p ON p.id = b.target_id
        WHERE b.user_id = :userId
          AND b.target_type = 'PLACE'
          AND p.town_id IN (:townIds)
          AND p.active = true
        ORDER BY b.created_at DESC, b.target_id DESC
    """, nativeQuery = true)
    List<Long> findBookmarkedPlaceIdsByTownsOrdered(
            @Param("userId") Long userId, @Param("townIds") List<Long> townIds);

    /** 특정 동네의 북마크 코스 id를 최신순으로 반환 */
    @Query(value = """
        SELECT b.target_id
        FROM bookmarks b
        INNER JOIN courses c ON c.id = b.target_id
        WHERE b.user_id = :userId
          AND b.target_type = 'COURSE'
          AND c.town_id = :townId
          AND c.active = true
        ORDER BY b.created_at DESC, b.target_id DESC
    """, nativeQuery = true)
    List<Long> findBookmarkedCourseIdsByTownOrdered(
            @Param("userId") Long userId, @Param("townId") Long townId);

    /**
     * 동네별 가장 최근 북마크 장소 1개 (폴더 프리뷰).
     * row: [town_id (Long), target_id (Long)]
     */
    @Query(value = """
        SELECT t.town_id, t.target_id FROM (
            SELECT p.town_id AS town_id, b.target_id AS target_id,
                   ROW_NUMBER() OVER (PARTITION BY p.town_id ORDER BY b.created_at DESC, b.target_id DESC) AS rn
            FROM bookmarks b
            INNER JOIN places p ON p.id = b.target_id
            WHERE b.user_id = :userId
              AND b.target_type = 'PLACE'
              AND p.active = true
        ) t WHERE t.rn = 1
    """, nativeQuery = true)
    List<Object[]> findLatestBookmarkedPlaceIdPerTown(@Param("userId") Long userId);

    /**
     * 동네별 가장 최근 북마크 코스 1개 (폴더 프리뷰).
     * row: [town_id (Long), target_id (Long)]
     */
    @Query(value = """
        SELECT t.town_id, t.target_id FROM (
            SELECT c.town_id AS town_id, b.target_id AS target_id,
                   ROW_NUMBER() OVER (PARTITION BY c.town_id ORDER BY b.created_at DESC, b.target_id DESC) AS rn
            FROM bookmarks b
            INNER JOIN courses c ON c.id = b.target_id
            WHERE b.user_id = :userId
              AND b.target_type = 'COURSE'
              AND c.active = true
        ) t WHERE t.rn = 1
    """, nativeQuery = true)
    List<Object[]> findLatestBookmarkedCourseIdPerTown(@Param("userId") Long userId);

}
