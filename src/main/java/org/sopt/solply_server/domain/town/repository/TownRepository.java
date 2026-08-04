package org.sopt.solply_server.domain.town.repository;

import java.util.List;

import java.util.Optional;
import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TownRepository extends JpaRepository<Town, Long> {
	List<Town> findByParentIsNull();

	List<Town> findByParent(Town parent);

    Optional<Town> findTownByIdAndActiveTrue(Long townId);

	/**
	 * 자기 자신 + active 자식들의 id. 요청 하나가 필요한 town 정보를 <b>1문장</b>으로 가져온다.
	 *
	 * <p><b>두 문장이었다 (2026-08-03 통합).</b> 목록 경로는 같은 town id로 두 번 DB에 갔다 —
	 * {@code existsById}(존재 검증, COUNT)와 {@code parent_id} 조회(leaf 확장). 둘 다 towns의
	 * 같은 좁은 범위를 보는데, 한쪽은 PK 1행이고 다른 쪽은 {@code idx_town_parent_id} 한 구간이라
	 * {@code OR}로 합치면 한 문장에 담긴다. 반환된 행에서 두 판정이 모두 나온다:
	 * <ul>
	 *   <li>{@code id == townId}인 행이 있는가 → 존재 검증</li>
	 *   <li>나머지 행들 → leaf 목록 (비어 있으면 자기 자신이 leaf)</li>
	 * </ul>
	 *
	 * <p><b>{@code parent_id}를 함께 select하지 않는 이유.</b> 자기 행과 자식 행은 id만으로 갈린다 —
	 * 자식 행의 id는 {@code townId}일 수 없다(자기 부모인 town은 없다). 컬럼을 하나 더 실어
	 * 프로젝션 타입을 새로 만들 이유가 없다.
	 *
	 * <p><b>active 조건의 비대칭은 기존 동작이다.</b> 자기 행에는 {@code active} 조건이 없고
	 * (옛 {@code existsById}가 안 봤다 — 비활성 동네 id도 존재 검증을 통과한다), 자식 행에만 있다.
	 * 조건을 "정리"해서 대칭으로 만들면 비활성 동네 조회가 200에서 404로 바뀐다.
	 *
	 * <p>{@code order by}는 결과를 바꾸지 않지만 {@code IN (...)} 절의 SQL 텍스트를 고정한다 —
	 * 뒤따르는 목록 쿼리의 digest가 행 순서에 따라 갈리지 않는다.
	 */
	@Query("""
		select t.id from Town t
		 where t.id = :townId
			or (t.parent.id = :townId and t.active = true)
		 order by t.id
		""")
	List<Long> findSelfAndActiveChildIds(@Param("townId") Long townId);
}
