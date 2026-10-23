package org.sopt.solply_server.domain.admin.auth.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 콜백이 발급하는 일회용 교환 코드. 소비 규칙과 그 근거는
 * {@code AdminOAuthStateRepository}와 같다.
 *
 * <p>역할을 담지 않는다 — 권한은 교환 시점에 DB에서 다시 읽는다. 코드에 실어 보내면 발급과
 * 교환 사이에 권한이 내려간 사용자가 옛 권한으로 토큰을 받는다.
 */
@Repository
@RequiredArgsConstructor
public class AdminAuthCodeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public void save(String authCode, Long userId, SocialPlatform platform, long expiresAtEpochMilli) {
        jdbcTemplate.update("""
                        INSERT INTO admin_auth_code (auth_code, user_id, platform, expires_at)
                        VALUES (:authCode, :userId, :platform, :expiresAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("authCode", authCode)
                        .addValue("userId", userId)
                        .addValue("platform", platform.name())
                        .addValue("expiresAt", expiresAtEpochMilli)
        );
    }

    /** 코드를 소비하고 담겨 있던 주체를 돌려준다. 이미 소비됐거나 만료됐거나 없으면 비어 있다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<ConsumedAuthCode> consume(String authCode, long nowEpochMilli) {
        int updated = jdbcTemplate.update("""
                        UPDATE admin_auth_code
                           SET consumed_at = :now
                         WHERE auth_code = :authCode
                           AND consumed_at IS NULL
                           AND expires_at > :now
                        """,
                new MapSqlParameterSource()
                        .addValue("authCode", authCode)
                        .addValue("now", nowEpochMilli)
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT user_id, platform FROM admin_auth_code WHERE auth_code = :authCode",
                new MapSqlParameterSource("authCode", authCode),
                (rs, rowNum) -> new ConsumedAuthCode(
                        rs.getLong("user_id"), SocialPlatform.valueOf(rs.getString("platform")))
        ).stream().findFirst();
    }

    public int deleteExpired(long nowEpochMilli, int limit) {
        return jdbcTemplate.update(
                "DELETE FROM admin_auth_code WHERE expires_at < :now LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("now", nowEpochMilli)
                        .addValue("limit", limit)
        );
    }

    public record ConsumedAuthCode(Long userId, SocialPlatform platform) {
    }
}
