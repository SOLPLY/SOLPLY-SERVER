package org.sopt.solply_server.domain.place.cache.town;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동네 번호 테이블의 유일한 입구.
 *
 * <p><b>읽기는 반드시 "지금 트랜잭션 안에서" 하나의 문장으로 한다.</b> 동네를 하나씩 따로 읽으면
 * 동네 A는 커밋 전, 동네 B는 커밋 후를 보게 돼 <b>존재한 적 없는 조합</b>이 만들어진다. 그 조합으로
 * 커서를 발급하면 다음 페이지가 어느 시점을 이어받는지 말할 수 없다.
 *
 * <p><b>bump는 원본을 고친 그 트랜잭션 안에서만 돈다</b>({@code MANDATORY}). 번호가 먼저 오르고
 * 원본 커밋이 실패하면 아무것도 안 바뀐 동네의 커서가 전부 끊긴다. 반대 순서면 바뀐 내용이 옛
 * 번호로 서빙된다.
 */
@Repository
@RequiredArgsConstructor
public class TownVersionRepository {

    private static final String READ_SQL = """
            SELECT town_id, version
              FROM place_list_town_versions
             WHERE town_id IN (:townIds)
            """;

    /**
     * 행이 없으면 만들고, 있으면 올린다. 행마다 원자적이고 단조다.
     *
     * <p>{@code towns}를 거치는 것은 값 목록을 INSERT 튜플로 펼치는 대신 쓰는 방법이자, 존재하지
     * 않는 동네의 번호 행이 생기지 않게 하는 울타리다. 그래서 이미 지워진 동네에 대한 bump는
     * 조용히 아무 일도 하지 않는다 — 그 동네를 조회할 경로가 없으므로 문제가 되지 않는다.
     */
    private static final String BUMP_SQL = """
            INSERT INTO place_list_town_versions (town_id, version)
            SELECT t.id, 1 FROM towns t WHERE t.id IN (:townIds)
            ON DUPLICATE KEY UPDATE version = place_list_town_versions.version + 1
            """;

    /**
     * 정기 전체 배치가 쓰는 한 문장. <b>실제 통계값이 바뀌었는지 보지 않고</b> 처리 대상이 된
     * 모든 동네를 올린다 — 표시값만 갈린 동네를 영구히 빼놓지 않기 위해서다.
     */
    private static final String BUMP_ALL_SQL = """
            INSERT INTO place_list_town_versions (town_id, version)
            SELECT src.town_id, 1 FROM (SELECT DISTINCT town_id FROM place_stats) AS src
            ON DUPLICATE KEY UPDATE version = place_list_town_versions.version + 1
            """;

    private static final String TOWNS_WITH_PLACES_SQL = """
            SELECT DISTINCT town_id FROM place_stats
            """;

    /**
     * 쓰기 전후로 불러 견주는 <b>지문</b>. 탐색의 대상·필터·순서가 보는 값만 담는다.
     *
     * <p>{@code town_id}는 대상 범위를, {@code tag_bitmask}는 필터 통과 여부를 정한다. 이름·
     * 썸네일·소개·좌표는 여기 없다 — 목록의 순서도 대상도 바꾸지 않기 때문이다. 정렬 키값
     * (점수·카운트·평점)도 여기 없다: 그것을 고치는 경로는 정기 전체 배치와 북마크 델타뿐이고,
     * 둘 다 자기 자리에서 따로 번호를 올린다.
     *
     * <p><b>⚠️ {@code FOR UPDATE}가 없으면 이 읽기는 쓸모가 없다.</b> 쓰기 트랜잭션은 이미
     * REPEATABLE READ 스냅샷을 잡은 뒤라, 평범한 SELECT는 <b>트랜잭션이 시작될 때의 값</b>을
     * 돌려준다 — 그 사이 다른 writer가 장소를 B로 옮기고 커밋했어도 여기서는 여전히 A로 보인다.
     * 그러면 "쓰기 전 동네"가 거짓이 되고, 실제 출발지 B의 번호가 영영 오르지 않는다. 잠금 읽기는
     * 최신 커밋을 보며, 동시에 같은 행을 겨눈 다른 writer를 줄 세운다.
     *
     * <p><b>⚠️ 잠금 순서는 언제나 {@code places} → {@code place_stats}다.</b> 호출부가 장소 행을
     * 먼저 잠근 뒤 이것을 부른다. 순서를 뒤집는 경로를 만들면 두 어드민 요청이 서로를 마주 보고
     * {@code ERROR 1213}으로 죽는다.
     */
    private static final String FINGERPRINT_SQL = """
            SELECT place_id, town_id, tag_bitmask
              FROM place_stats
             WHERE place_id IN (:placeIds)
             FOR UPDATE
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /**
     * 요청이 관련 동네들의 번호를 <b>한 read view에서</b> 관측한다. 호출부가 이미 트랜잭션을 열고
     * 있어야 한다.
     *
     * @return 행이 있던 동네만 담긴 맵. 없는 동네는 {@link TownVersions#ABSENT}로 채워진다
     */
    public TownVersions readInCurrentTransaction(Collection<Long> townIds) {
        if (townIds.isEmpty()) {
            return TownVersions.of(List.of(), Map.of());
        }
        Map<Long, Long> read = new HashMap<>(townIds.size() * 2);
        namedJdbcTemplate.query(READ_SQL,
                new MapSqlParameterSource("townIds", townIds),
                rs -> {
                    read.put(rs.getLong("town_id"), rs.getLong("version"));
                });
        return TownVersions.of(townIds, read);
    }

    /** 이 동네들의 번호를 올린다. 원본을 고친 트랜잭션 안에서만 부를 수 있다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bump(Collection<Long> townIds) {
        if (townIds.isEmpty()) {
            return;
        }
        namedJdbcTemplate.update(BUMP_SQL, new MapSqlParameterSource("townIds", townIds));
    }

    /**
     * 처리 대상 전 동네의 번호를 올린다. 정기 전체 배치의 트랜잭션 안에서만 부른다 — 배치가
     * 롤백되면 이 bump도 함께 사라진다.
     *
     * @return 올린 동네 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int bumpAllTownsWithPlaces() {
        return jdbcTemplate.update(BUMP_ALL_SQL);
    }

    /** 전체 배치의 bump 대상과 같은 집합 — 장소가 있는 동네 전부. 커밋 뒤 발행이 쓴다. */
    public List<Long> townIdsWithPlaces() {
        return jdbcTemplate.queryForList(TOWNS_WITH_PLACES_SQL, Long.class);
    }

    /**
     * 탐색이 보는 값만 담은 한 장소의 지문. 행이 없으면(삭제·비활성) 지문도 없다 — 그 "없음"이
     * 곧 삭제의 표시다.
     */
    public record PlaceFingerprint(long townId, long tagBitmask) {
    }

    /** 이 장소들의 <b>지금</b> 지문. 행이 없는 장소는 결과에 없다. */
    public Map<Long, PlaceFingerprint> fingerprintsOf(Collection<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PlaceFingerprint> read = new HashMap<>(placeIds.size() * 2);
        namedJdbcTemplate.query(FINGERPRINT_SQL,
                new MapSqlParameterSource("placeIds", placeIds),
                rs -> {
                    read.put(rs.getLong("place_id"),
                            new PlaceFingerprint(rs.getLong("town_id"), rs.getLong("tag_bitmask")));
                });
        return read;
    }
}
