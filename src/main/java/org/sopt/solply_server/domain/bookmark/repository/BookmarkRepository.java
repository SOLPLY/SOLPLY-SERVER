package org.sopt.solply_server.domain.bookmark.repository;


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


    List<Bookmark> findByUserIdAndTargetTypeAndTargetIdIn(Long userId, BookmarkTargetType type, Set<Long> ids);

    @Query("""
        select b.targetId
        from Bookmark b
        where b.user.id = :userId
          and b.targetType = :targetType
    """)
    Set<Long> findBookmarkedTargetIds(
            @Param("userId") Long userId,
            @Param("targetType") BookmarkTargetType targetType);
}