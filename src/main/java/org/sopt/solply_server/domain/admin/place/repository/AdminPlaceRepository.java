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

    /**
     * 동네에 속한 장소 id. 재활성 직후 place_stats 행을 지어 넣는 경로가 쓴다
     * ({@code AdminPlaceService#activatePlacesByTownIds}) — 활성 여부는 그쪽 문장이 다시 보므로
     * 여기서 거르지 않는다.
     */
    @Query("select p.id from Place p where p.town.id in :townIds")
    List<Long> findIdsByTownIds(@Param("townIds") List<Long> townIds);

    /**
     * <b>⚠️ {@code active = false}로 부르려면 place_stats 행도 함께 지워야 한다</b>
     * ({@code PlaceStatsRepository#deleteByPlaceIds}). 목록 조회 두 정렬 모두 place_stats가 기준
     * 테이블이고 그 행의 존재를 뒤에서 정리해 주던 카운트 배치의 잔행 삭제가 없어졌으므로,
     * 플래그만 내리면 내려간 장소가 <b>영구히</b> 목록에 남는다.
     *
     * <p>지금 호출부는 재활성({@code true}) 하나뿐이고, 그쪽은
     * {@code AdminPlaceService#activatePlacesByTownIds}가 행을 다시 짓는다.
     */
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
