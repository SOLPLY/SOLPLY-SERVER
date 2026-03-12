package org.sopt.solply_server.domain.place.repository;

import java.util.List;
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
}
