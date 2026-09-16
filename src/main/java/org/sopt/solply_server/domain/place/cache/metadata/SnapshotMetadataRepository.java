package org.sopt.solply_server.domain.place.cache.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 번호 둘을 읽고 올리는 자리. 문장은 셋뿐이다.
 *
 * <p><b>올리는 문장이 하나인 것이 계약이다.</b> UPDATE 한 문장 안에서 DB가 현재 값을 읽어 더한다 —
 * 읽어서 자바로 가져오고 더해서 다시 쓰는 read-modify-write가 아니다. 그 방식이었다면 두
 * 트랜잭션이 같은 값을 읽어 같은 값을 쓰고, 변경 하나가 번호 없이 사라진다. 엔티티를 만들지 않고
 * JDBC로 두는 것도 같은 이유다 — ORM에 올려 두면 누군가 반드시 {@code metadata.setRevision(...)}을
 * 쓴다.
 *
 * <p><b>회차가 오르면 revision은 0으로 리셋된다.</b> 그래서 번호 쌍은 "몇 회차의 몇 번째 변경"으로
 * 읽히고, 두 값의 전순서는 사전식이다({@link SnapshotMetadata#isNewerThan}). 리셋과 회차 증가가
 * 같은 UPDATE 안에 있어야 둘이 어긋난 쌍이 DB에 보이는 창이 없다.
 */
@Repository
@RequiredArgsConstructor
public class SnapshotMetadataRepository {

    private static final String READ_SQL =
            "SELECT revision, cursor_version FROM place_list_snapshot_metadata WHERE id = 1";

    // 파라미터 둘 다 cursorIncrement다 — 회차를 올리는 회차에서만 revision이 0으로 돌아간다
    private static final String BUMP_SQL = """
            UPDATE place_list_snapshot_metadata
               SET revision = IF(? = 1, 0, revision + 1),
                   cursor_version = cursor_version + ?
             WHERE id = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 지금의 번호 둘. 조회 경로가 요청마다 부르는 문장이라 단일 행 PK 조회 하나로 끝난다.
     *
     * <p>{@code REQUIRES_NEW}인 것은 호출자의 트랜잭션에 얹히지 않기 위해서다 — 특히 리빌드
     * 읽기 트랜잭션은 자기 안에서 {@link #readInCurrentTransaction()}을 써야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SnapshotMetadata read() {
        return readInCurrentTransaction();
    }

    /**
     * 지금 열려 있는 트랜잭션 <b>안에서</b> 읽는다. 리빌드의 첫 문장이 이것이다.
     *
     * <p>MySQL의 REPEATABLE READ는 <b>첫 일반 SELECT가 도는 순간</b> read view를 만들고, 그 뒤의
     * 모든 문장이 같은 시점을 본다. 그래서 이 트랜잭션 안에서는 번호를 먼저 읽든 나중에 읽든
     * 값이 같다 — <b>첫 문장으로 두는 것은 read view가 열리는 자리를 눈에 보이게 고정하는 구현
     * 규칙</b>이지 정합성의 조건이 아니다. 규칙을 두는 이유는 그 자리가 코드에 드러나야, 나중에
     * 이 앞에 다른 읽기가 끼어드는 변경을 알아볼 수 있기 때문이다.
     *
     * <p>정합성이 실제로 걸려 있는 곳은 쓰기 쪽이다 — 목록을 바꾸는 트랜잭션이 같은 트랜잭션에서
     * 번호를 올리므로, 이 read view가 본 변경의 번호는 반드시 함께 보인다.
     */
    public SnapshotMetadata readInCurrentTransaction() {
        return jdbcTemplate.queryForObject(READ_SQL, (rs, rowNum) ->
                new SnapshotMetadata(rs.getLong("revision"), rs.getLong("cursor_version")));
    }

    /**
     * 번호를 올린다. <b>반드시 데이터를 고친 그 트랜잭션 안에서</b> 불려야 하므로 전파는
     * {@code MANDATORY}다 — 트랜잭션 없이 부르면 그 자리에서 터진다.
     *
     * <p>{@code MANDATORY}가 막는 것은 "데이터는 커밋됐는데 번호는 안 올랐다"와 그 반대다.
     * 앞쪽이면 변경이 어느 인스턴스에도 반영되지 않고, 뒤쪽이면 모든 인스턴스가 바뀐 것 없는
     * 원본을 다시 읽는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bump(SnapshotCursorPolicy policy) {
        if (jdbcTemplate.update(BUMP_SQL,
                policy.cursorIncrement(), policy.cursorIncrement()) != 1) {
            throw new IllegalStateException(
                    "목록 스냅샷 메타데이터 행이 없다 - V46 마이그레이션을 확인할 것");
        }
    }
}
