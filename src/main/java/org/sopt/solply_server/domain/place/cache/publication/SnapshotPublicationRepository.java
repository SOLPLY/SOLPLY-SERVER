package org.sopt.solply_server.domain.place.cache.publication;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행물과 명시 포인터.
 *
 * <p><b>쓰기 넷에 {@code @Transactional}을 달지 않는다.</b> {@link SnapshotPublicationService}가
 * 트랜잭션을 소유하고 이 메서드들은 거기 참여한다 — 넷이 갈리면 CAS에 진 발행이 고아 payload
 * 행을 남기고, 발행과 처리 표시가 서로 다른 트랜잭션에서 커밋된다.
 */
@Repository
@RequiredArgsConstructor
public class SnapshotPublicationRepository {

    private static final String POINTER_SQL =
            "SELECT publication_id FROM place_list_publication_pointer WHERE id = 1";

    // ⚠️ 포인터와 그 행을 한 문장으로 조인해 읽는다. SELECT 둘로 나누면 그 사이에 포인터가
    //    옮겨가고 옛 행이 정리될 수 있다 — READ COMMITTED에서 "한 트랜잭션"은 문장마다 새 시야를
    //    주므로 트랜잭션으로 묶는 것만으로는 그 창이 닫히지 않는다. 한 문장이라야 원자적이다
    private static final String DOWNLOAD_SQL = """
            SELECT p.id, p.cursor_version, p.format_version, p.entry_count,
                   p.payload_bytes, p.payload_sha256, p.payload
              FROM place_list_publication_pointer ptr
              JOIN place_list_publications p ON p.id = ptr.publication_id
             WHERE ptr.id = 1
            """;

    // payload를 뺀 DOWNLOAD_SQL이다. 한 문장인 이유도 같다 — 포인터와 그 행을 따로 읽으면 그
    // 사이에 포인터가 옮겨간다. 조회 경로가 "내가 낡았나"를 물으러 오는 문장이라 BLOB을 읽지 않는
    // 것이 요점이고, 그래서 DOWNLOAD_SQL을 재사용하지 않고 따로 둔다
    private static final String HEAD_SQL = """
            SELECT p.id, p.cursor_version
              FROM place_list_publication_pointer ptr
              JOIN place_list_publications p ON p.id = ptr.publication_id
             WHERE ptr.id = 1
            """;

    private static final String INSERT_SQL = """
            INSERT INTO place_list_publications
                (cursor_version, format_version, entry_count, payload_bytes, payload_sha256, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String FILL_CURSOR_VERSION_SQL = """
            UPDATE place_list_publications SET cursor_version = id
             WHERE id = ? AND cursor_version IS NULL
            """;

    // <=> 는 NULL-safe 비교다. 미발행 기준(NULL)까지 같은 문장으로 다룬다
    private static final String CAS_POINTER_SQL = """
            UPDATE place_list_publication_pointer
               SET publication_id = ?
             WHERE id = 1 AND publication_id <=> ?
            """;

    private static final String DELETE_OLDER_SQL =
            "DELETE FROM place_list_publications WHERE id < ?";

    private final JdbcTemplate jdbcTemplate;

    /** @return 현재 발행 id. {@code null}이면 아직 아무도 발행하지 않았다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Long readCurrentPublicationId() {
        return jdbcTemplate.queryForObject(POINTER_SQL, Long.class);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PublishedSnapshot> download() {
        return jdbcTemplate.query(DOWNLOAD_SQL, (rs, rowNum) -> new PublishedSnapshot(
                        rs.getLong("id"),
                        rs.getLong("cursor_version"),
                        rs.getInt("format_version"),
                        rs.getInt("entry_count"),
                        rs.getInt("payload_bytes"),
                        rs.getString("payload_sha256"),
                        rs.getBytes("payload")))
                .stream()
                .findFirst();
    }

    /**
     * 지금 발행물의 번호 둘만. <b>payload를 읽지 않는다</b> — 조회 경로가 부르는 유일한 발행물
     * 문장이라 여기서 BLOB을 끌고 오면 요청마다 수 MB를 읽게 된다.
     *
     * <p>{@code cursor_version}이 {@code NULL}인 행을 볼 창은 없다 — 구조 발행은 회차를 채운
     * <b>뒤에</b> 포인터를 옮기고 그 둘이 한 트랜잭션이다({@link SnapshotPublicationService}).
     *
     * @return 아직 아무도 발행하지 않았으면 비어 있다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PublicationHead> readCurrentHead() {
        return jdbcTemplate.query(HEAD_SQL, (rs, rowNum) -> new PublicationHead(
                        rs.getLong("id"), rs.getLong("cursor_version")))
                .stream()
                .findFirst();
    }

    /** @return 새로 정해진 발행 id */
    public long insertPayload(PublicationCandidate candidate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps =
                    connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            if (candidate.carriedCursorVersion() == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, candidate.carriedCursorVersion());
            }
            ps.setInt(2, candidate.formatVersion());
            ps.setInt(3, candidate.entryCount());
            ps.setInt(4, candidate.payloadBytes());
            ps.setString(5, candidate.payloadSha256());
            ps.setBytes(6, candidate.payload());
            return ps;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("발행물 INSERT가 id를 돌려주지 않았다");
        }
        return id.longValue();
    }

    /** 구조 발행의 회차를 자기 id로 확정한다. */
    public void fillCursorVersionFromId(long publicationId) {
        if (jdbcTemplate.update(FILL_CURSOR_VERSION_SQL, publicationId) != 1) {
            throw new IllegalStateException("구조 발행의 회차를 채우지 못했다 - id=" + publicationId);
        }
    }

    /** @return 옮겼으면 true. false는 "내가 잡은 기준이 더 이상 현재가 아니다" */
    public boolean casPointer(long newPublicationId, Long basePublicationId) {
        return jdbcTemplate.update(CAS_POINTER_SQL, newPublicationId, basePublicationId) == 1;
    }

    /**
     * 현재보다 오래된 발행물을 지운다. 현재 행은 포인터의 FK가 지킨다.
     *
     * <p>발행 트랜잭션 <b>밖</b>에서 따로 부른다 — 여기서 실패해도 이미 커밋된 발행을 되돌릴
     * 이유가 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteOlderThan(long currentPublicationId) {
        return jdbcTemplate.update(DELETE_OLDER_SQL, currentPublicationId);
    }
}
