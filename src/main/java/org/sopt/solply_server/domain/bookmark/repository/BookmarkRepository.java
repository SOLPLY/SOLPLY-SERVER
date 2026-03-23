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

    // == Town-scoped backfill 쿼리 == //

    /**
     * 특정 동네의 장소 북마크 backfill용.
     * row: [target_id (Long), created_at (LocalDateTime)]
     */
    @Query(value = """
        SELECT b.target_id, b.created_at
        FROM bookmarks b
        INNER JOIN places p ON p.id = b.target_id
        WHERE b.user_id = :userId
          AND b.target_type = 'PLACE'
          AND p.town_id = :townId
          AND p.active = true
    """, nativeQuery = true)
    List<Object[]> findPlaceBookmarksByUserAndTown(
            @Param("userId") Long userId,
            @Param("townId") Long townId);

    /**
     * 특정 동네의 코스 북마크 backfill용.
     * row: [target_id (Long), created_at (LocalDateTime)]
     */
    @Query(value = """
        SELECT b.target_id, b.created_at
        FROM bookmarks b
        INNER JOIN courses c ON c.id = b.target_id
        WHERE b.user_id = :userId
          AND b.target_type = 'COURSE'
          AND c.town_id = :townId
          AND c.active = true
    """, nativeQuery = true)
    List<Object[]> findCourseBookmarksByUserAndTown(
            @Param("userId") Long userId,
            @Param("townId") Long townId);

    /**
     * 장소 북마크 전체 backfill용 (폴더 프리뷰).
     * row: [target_id (Long), created_at (LocalDateTime), town_id (Long)]
     */
    @Query(value = """
        SELECT b.target_id, b.created_at, p.town_id
        FROM bookmarks b
        INNER JOIN places p ON p.id = b.target_id
        WHERE b.user_id = :userId
          AND b.target_type = 'PLACE'
          AND p.active = true
    """, nativeQuery = true)
    List<Object[]> findAllPlaceBookmarksWithTownId(@Param("userId") Long userId);

    /**
     * 코스 북마크 전체 backfill용 (폴더 프리뷰).
     * row: [target_id (Long), created_at (LocalDateTime), town_id (Long)]
     */
    @Query(value = """
        SELECT b.target_id, b.created_at, c.town_id
        FROM bookmarks b
        INNER JOIN courses c ON c.id = b.target_id
        WHERE b.user_id = :userId
          AND b.target_type = 'COURSE'
          AND c.active = true
    """, nativeQuery = true)
    List<Object[]> findAllCourseBookmarksWithTownId(@Param("userId") Long userId);
}
