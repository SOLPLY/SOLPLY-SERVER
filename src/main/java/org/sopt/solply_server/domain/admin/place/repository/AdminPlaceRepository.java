package org.sopt.solply_server.domain.admin.place.repository;

import java.util.List;
import java.util.Optional;
import org.sopt.solply_server.domain.admin.place.repository.querydsl.AdminPlaceRepositoryCustom;
import org.sopt.solply_server.domain.place.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminPlaceRepository extends JpaRepository<Place, Long>, AdminPlaceRepositoryCustom {

    @Query("""
        select distinct p
        from Place p
        left join fetch p.town t
        left join fetch p.checkpoints cp
        where p.id = :placeId
    """)
    Optional<Place> findByIdWithTownAndCheckpoints(@Param("placeId") Long placeId);

    @Query("""
        select distinct p
        from Place p
        join fetch p.town t
        left join fetch p.placeTags pt
        left join fetch pt.tag tg
        where t.id = :townId
        order by p.createdAt desc
    """)
    List<Place> findAdminPlacesWithTagsByTownId(@Param("townId") Long townId);

    @Query("""
        select distinct p
        from Place p
        left join fetch p.placeTags pt
        left join fetch pt.tag t
        where p.id in :placeIds
    """)
    List<Place> findByIdInWithTags(List<Long> placeIds);

    boolean existsByTown_Id(@Param("townId") Long townId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Place p
        set p.active = :active
        where p.town.id in :townIds
    """)
    int updateActiveByTownId(@Param("townIds") List<Long> townIds, @Param("active") boolean active);

	@Query("""
		select count(p) > 0
		from Place p
		where p.town.id in :townIds
	""")
	boolean existsPlacesByTown_Ids(@Param("townIds") List<Long> townIds);

    @Query("""
        SELECT p
        FROM Place p
        JOIN FETCH p.town
        WHERE p.id = :id
    """)
    Optional<Place> findByIdWithTown(@Param("id") Long placeId);
}
