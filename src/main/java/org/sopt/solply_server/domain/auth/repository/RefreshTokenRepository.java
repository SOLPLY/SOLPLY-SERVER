package org.sopt.solply_server.domain.auth.repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.RefreshTokenRow;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenMaterial;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code refresh_token} 접근. <b>JdbcTemplate이고 JPA가 아니다</b> — 이유는
 * {@code RefreshTokenRow} javadoc에 있다(회전 패자가 최신 행을 봐야 한다).
 *
 * <p>이 클래스는 트랜잭션을 열지 않는다. 경계는 전부 {@code RefreshTokenService}와
 * 호출하는 서비스가 정한다 — 회전은 짧은 READ COMMITTED, 탈퇴는 탈퇴 트랜잭션에 참여해야 하고,
 * 그 선택이 여기에 박히면 호출자가 뒤집을 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final RowMapper<RefreshTokenRow> ROW_MAPPER = (rs, rowNum) -> new RefreshTokenRow(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("family_id"),
            rs.getString("jwt_id"),
            rs.getString("parent_jwt_id"),
            SocialPlatform.valueOf(rs.getString("platform")),
            rs.getInt("token_format_version"),
            rs.getLong("issued_at"),
            rs.getLong("expires_at"),
            nullableLong(rs.getLong("rotated_at"), rs.wasNull()),
            nullableLong(rs.getLong("grace_expires_at"), rs.wasNull()),
            nullableLong(rs.getLong("revoked_at"), rs.wasNull())
    );

    private static final String SELECT_COLUMNS = """
            SELECT id, user_id, family_id, jwt_id, parent_jwt_id, platform, token_format_version,
                   issued_at, expires_at, rotated_at, grace_expires_at, revoked_at
              FROM refresh_token
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 사용자 공통 잠금. 발급·회전·계열 로그아웃·전체 폐기·탈퇴가 전부 이 한 줄을 먼저 잡는다.
     *
     * <p><b>잠금을 refresh 행이 아니라 users 행에 거는 이유.</b> 회전은 부모 행을 바꾸고 자식
     * 행을 <em>만든다</em>. 아직 없는 행은 잠글 수 없으므로 경쟁자들이 만나는 지점은 그 위,
     * 곧 사용자여야 한다. 잠금 순서도 여기서 고정된다 — 사용자 → refresh 행, 언제나.
     *
     * <p><b>소프트 삭제된 사용자도 잠근다.</b> {@code User} 엔티티에 걸린
     * {@code @Where(is_deleted = false)}를 타면 탈퇴한 사용자의 행을 잡을 수 없어, 탈퇴 폐기와
     * 재가입 발급이 서로를 보지 못한 채 지나간다. 네이티브로 내려가 필터를 우회하고
     * 삭제 여부를 값으로 돌려받아 판정은 호출자가 한다.
     */
    public Optional<LockedUser> lockUser(Long userId) {
        List<LockedUser> rows = jdbcTemplate.query(
                "SELECT role, is_deleted FROM users WHERE id = :userId FOR UPDATE",
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> new LockedUser(rs.getString("role"), rs.getBoolean("is_deleted"))
        );
        return rows.stream().findFirst();
    }

    public Optional<RefreshTokenRow> findByJwtId(String jwtId) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE jwt_id = :jwtId",
                new MapSqlParameterSource("jwtId", jwtId),
                ROW_MAPPER
        ).stream().findFirst();
    }

    public Optional<RefreshTokenRow> findByParentJwtId(String parentJwtId) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE parent_jwt_id = :parentJwtId",
                new MapSqlParameterSource("parentJwtId", parentJwtId),
                ROW_MAPPER
        ).stream().findFirst();
    }

    public void insert(RefreshTokenMaterial material, String parentJwtId) {
        jdbcTemplate.update("""
                        INSERT INTO refresh_token
                            (user_id, family_id, jwt_id, parent_jwt_id, platform, token_format_version,
                             issued_at, expires_at)
                        VALUES
                            (:userId, :familyId, :jwtId, :parentJwtId, :platform, :formatVersion,
                             :issuedAt, :expiresAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", material.userId())
                        .addValue("familyId", material.familyId())
                        .addValue("jwtId", material.jwtId())
                        // 로그인 직후 첫 토큰은 부모가 없다. 타입을 명시해 드라이버가 NULL을
                        // 어떻게 보낼지 추측하지 않게 한다.
                        .addValue("parentJwtId", parentJwtId, Types.VARCHAR)
                        .addValue("platform", material.platform().name())
                        .addValue("formatVersion", material.formatVersion())
                        .addValue("issuedAt", material.issuedAtEpochSecond())
                        .addValue("expiresAt", material.expiresAtEpochSecond())
        );
    }

    /**
     * 회전의 조건부 UPDATE. <b>이 문장의 반환값이 경쟁의 판정이다</b> — 앞선 SELECT는 분기를
     * 고르기 위한 참고일 뿐이고, 그 사이에 상태가 바뀌었는지는 오직 여기서 드러난다.
     *
     * <p>1행이면 내가 이 부모를 회전시킨 유일한 요청이다. 0행이면 그 사이 누군가 회전시켰거나
     * (다른 요청) 폐기됐거나(전체 폐기) 만료됐다는 뜻이고, 어느 쪽인지는 <b>다시 읽어야</b> 안다.
     */
    public int markRotated(long id, long rotatedAtEpochMilli, long graceExpiresAtEpochMilli,
                           long nowEpochSecond) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                           SET rotated_at = :rotatedAt,
                               grace_expires_at = :graceExpiresAt
                         WHERE id = :id
                           AND rotated_at IS NULL
                           AND revoked_at IS NULL
                           AND expires_at > :nowSeconds
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("rotatedAt", rotatedAtEpochMilli)
                        .addValue("graceExpiresAt", graceExpiresAtEpochMilli)
                        .addValue("nowSeconds", nowEpochSecond)
        );
    }

    /**
     * 계열 하나를 끊는다(로그아웃). 이미 폐기된 행은 건드리지 않으므로 같은 access로 여러 번
     * 불러도 결과가 같다.
     */
    public int revokeFamily(Long userId, String familyId, long revokedAtEpochMilli) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                           SET revoked_at = :revokedAt
                         WHERE user_id = :userId
                           AND family_id = :familyId
                           AND revoked_at IS NULL
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("familyId", familyId)
                        .addValue("revokedAt", revokedAtEpochMilli)
        );
    }

    /**
     * 사용자의 모든 계열을 끊는다(재사용 감지·탈퇴).
     *
     * <p><b>회전된 부모도 포함된다.</b> 만료·폐기만 빼고 전부다 — 유예 중인 부모를 남겨 두면
     * 방금 재사용을 일으킨 그 토큰이 몇 초 더 살아 있는다. 반대로 만료된 행을 건드리지 않는 것은
     * 이미 쓸 수 없는 행에 폐기 도장을 찍어 봐야 정리 배치의 판정만 흐려지기 때문이다.
     */
    public int revokeAllByUserId(Long userId, long revokedAtEpochMilli, long nowEpochSecond) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                           SET revoked_at = :revokedAt
                         WHERE user_id = :userId
                           AND revoked_at IS NULL
                           AND expires_at > :nowSeconds
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("revokedAt", revokedAtEpochMilli)
                        .addValue("nowSeconds", nowEpochSecond)
        );
    }

    /**
     * 보존 기간이 지난 <b>계열</b>을 고른다. 토큰 단위가 아니다 — 계열 안에 하나라도 살아 있는
     * 토큰이 있으면 그 계열의 부모 이력도 남겨야 한다(재사용 판정의 근거다).
     */
    public List<String> findExpiredFamilyIds(long cutoffEpochSecond, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT family_id
                          FROM refresh_token
                         GROUP BY family_id
                        HAVING MAX(expires_at) < :cutoff
                         LIMIT :limit
                        """,
                new MapSqlParameterSource()
                        .addValue("cutoff", cutoffEpochSecond)
                        .addValue("limit", limit),
                String.class
        );
    }

    /**
     * 고른 계열을 지운다. <b>삭제 단위는 계열 전체다.</b>
     *
     * <p>위 조회가 고르는 계열은 {@code MAX(expires_at) < cutoff}, 곧 <b>그 계열의 모든 토큰이
     * 이미 기준 시각 전에 만료된 계열</b>이다. 그래서 이 문장은 부모와 자식을 함께 지우고,
     * 부모만 사라지고 자식이 남는(또는 그 반대의) 반쪽 상태를 만들지 않는다 — 그것이 이 정리가
     * 지키는 계약이고, 살아 있는 계열은 애초에 선택되지 않는다.
     *
     * <p>삭제에 같은 {@code expires_at < cutoff} 술어를 다시 두는 이유는 <b>두 문장이 같은
     * 집합을 보게 하기 위해서다</b>. 조회와 삭제가 갈려 있는 것은 MySQL의 다중 테이블 DELETE가
     * {@code LIMIT}을 받지 않아서일 뿐이므로, 조건까지 갈리면 한 문장이 고른 것과 다른 것을
     * 다른 문장이 지우게 된다.
     */
    public int deleteExpiredTokensOfFamilies(List<String> familyIds, long cutoffEpochSecond) {
        if (familyIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update("""
                        DELETE FROM refresh_token
                         WHERE family_id IN (:familyIds)
                           AND expires_at < :cutoff
                        """,
                new MapSqlParameterSource()
                        .addValue("familyIds", familyIds)
                        .addValue("cutoff", cutoffEpochSecond)
        );
    }

    private static Long nullableLong(long value, boolean wasNull) {
        return wasNull ? null : value;
    }

    /** 잠금과 동시에 읽어 온 사용자 상태. 역할은 문자열 그대로다 — 변환은 서비스가 한다. */
    public record LockedUser(String role, boolean deleted) {
    }
}
