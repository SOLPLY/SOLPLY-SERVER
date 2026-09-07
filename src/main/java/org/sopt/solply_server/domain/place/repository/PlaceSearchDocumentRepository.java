package org.sopt.solply_server.domain.place.repository;

import java.util.List;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceSearchDocumentRepository extends JpaRepository<PlaceSearchDocument, Long> {

    @Query("""
            SELECT psd FROM PlaceSearchDocument psd
            JOIN FETCH psd.place p
            WHERE p.town.id = :townId
              AND p.active = true
              AND psd.embedding IS NOT NULL
            """)
    List<PlaceSearchDocument> findActiveByTownIdWithEmbedding(@Param("townId") Long townId);

    @Query("""
            SELECT psd FROM PlaceSearchDocument psd
            JOIN FETCH psd.place p
            WHERE p.town.parent.id = :parentTownId
              AND p.active = true
              AND psd.embedding IS NOT NULL
            """)
    List<PlaceSearchDocument> findActiveByParentTownIdWithEmbedding(@Param("parentTownId") Long parentTownId);

    @Query("""
            SELECT psd.placeId FROM PlaceSearchDocument psd
            WHERE psd.status IN :statuses
            """)
    List<Long> findPlaceIdsByStatusIn(@Param("statuses") List<EmbeddingStatus> statuses);

    @Query("""
            SELECT psd FROM PlaceSearchDocument psd
            JOIN FETCH psd.place
            WHERE psd.placeId IN :placeIds
            """)
    List<PlaceSearchDocument> findAllByPlaceIdInWithPlace(@Param("placeIds") List<Long> placeIds);

    @Modifying
    @Query("""
            UPDATE PlaceSearchDocument psd
            SET psd.status = 'DIRTY', psd.updatedAt = CURRENT_TIMESTAMP
            WHERE psd.status <> 'INIT'
              AND psd.placeId IN (
                SELECT DISTINCT p.id FROM Place p JOIN p.placeTags pt WHERE pt.tag.id = :tagId
              )
            """)
    int markDirtyByTagId(@Param("tagId") Long tagId);

    @Modifying
    @Query("""
            UPDATE PlaceSearchDocument psd
            SET psd.status = 'DIRTY', psd.updatedAt = CURRENT_TIMESTAMP
            WHERE psd.status <> 'INIT'
              AND psd.placeId IN (
                SELECT DISTINCT p.id FROM Place p JOIN p.placeTags pt WHERE pt.tag.id IN :tagIds
              )
            """)
    int markDirtyByTagIds(@Param("tagIds") List<Long> tagIds);

}
