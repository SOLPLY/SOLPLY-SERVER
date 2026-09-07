package org.sopt.solply_server.domain.course.repository;

import java.util.Collection;
import java.util.List;
import org.sopt.solply_server.domain.course.entity.CourseSearchDocument;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseSearchDocumentRepository extends JpaRepository<CourseSearchDocument, Long> {

    @Query("""
            SELECT d FROM CourseSearchDocument d
            JOIN FETCH d.course c
            JOIN FETCH c.tag
            JOIN FETCH c.town
            WHERE c.active = true
              AND c.isShared = true
              AND c.town.id = :townId
              AND d.embedding IS NOT NULL
            """)
    List<CourseSearchDocument> findActiveSharedByTownIdWithEmbedding(@Param("townId") Long townId);

    @Query("""
            SELECT d FROM CourseSearchDocument d
            JOIN FETCH d.course c
            JOIN FETCH c.tag
            JOIN FETCH c.town
            WHERE c.active = true
              AND c.isShared = true
              AND c.town.parent.id = :parentTownId
              AND d.embedding IS NOT NULL
            """)
    List<CourseSearchDocument> findActiveSharedByParentTownIdWithEmbedding(@Param("parentTownId") Long parentTownId);

    @Query("""
            SELECT d.courseId FROM CourseSearchDocument d
            WHERE d.status IN :statuses
            """)
    List<Long> findCourseIdsByStatusIn(@Param("statuses") Collection<EmbeddingStatus> statuses);

    @Modifying
    @Query("""
            UPDATE CourseSearchDocument d
            SET d.status = 'DIRTY', d.updatedAt = CURRENT_TIMESTAMP
            WHERE d.status <> 'INIT'
              AND d.courseId = :courseId
            """)
    int markDirtyByCourseId(@Param("courseId") Long courseId);
}
