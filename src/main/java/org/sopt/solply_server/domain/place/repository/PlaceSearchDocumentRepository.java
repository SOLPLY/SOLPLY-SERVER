package org.sopt.solply_server.domain.place.repository;

import java.util.List;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.springframework.data.jpa.repository.JpaRepository;
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
            SELECT psd FROM PlaceSearchDocument psd
            WHERE psd.status IN :statuses
            """)
    List<PlaceSearchDocument> findAllByStatusIn(@Param("statuses") List<EmbeddingStatus> statuses);

    @Query("""
            SELECT psd FROM PlaceSearchDocument psd
            JOIN FETCH psd.place
            WHERE psd.placeId IN :placeIds
            """)
    List<PlaceSearchDocument> findAllByPlaceIdInWithPlace(@Param("placeIds") List<Long> placeIds);

    @Query("""
            SELECT psd FROM PlaceSearchDocument psd
            JOIN FETCH psd.place p
            JOIN p.placeTags pt
            WHERE pt.tag.id = :tagId
              AND psd.status <> 'INIT'
            """)
    List<PlaceSearchDocument> findAllByTagId(@Param("tagId") Long tagId);
}
