package org.sopt.solply_server.domain.admin.auth.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 OAuth state → nonce. 일회용이다.
 *
 * <p>Redis에서는 {@code GETDEL} 한 문장이 "읽기 + 일회 소비"였다. 그 원자성을 MySQL에서
 * 재현하는 방법이 {@link #consume}의 조건부 UPDATE다 — <b>1행을 바꾼 요청만 승자</b>이고,
 * 값은 그 뒤에 읽는다. 지우는 대신 도장을 찍는 이유는 DELETE가 지운 행의 값을 돌려주지 않아
 * 승패 판정과 값 읽기를 한 문장에 담을 수 없기 때문이다. 도장이 찍힌 행은 정리 배치가 걷는다.
 */
@Repository
@RequiredArgsConstructor
public class AdminOAuthStateRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public void save(String state, String nonce, long expiresAtEpochMilli) {
        jdbcTemplate.update(
                "INSERT INTO admin_oauth_state (state, nonce, expires_at) VALUES (:state, :nonce, :expiresAt)",
                new MapSqlParameterSource()
                        .addValue("state", state)
                        .addValue("nonce", nonce)
                        .addValue("expiresAt", expiresAtEpochMilli)
        );
    }

    /**
     * state를 소비하고 저장돼 있던 nonce를 돌려준다. 이미 소비됐거나 만료됐거나 없으면
     * {@code Optional.empty()} — 셋을 구분하지 않는 것은 <b>구분해서 알려 줄 이유가 없기
     * 때문이다</b>. 호출자에게는 전부 "이 state로는 진행할 수 없다"이고, 그 이상을 응답으로
     * 내보내면 어느 state가 존재했는지를 알려 주는 꼴이 된다.
     *
     * <p>두 문장이지만 원자적이다. UPDATE가 승자를 정하고 그 행은 커밋까지 잠겨 있으므로,
     * 뒤따르는 SELECT는 내가 방금 도장을 찍은 그 값을 본다. 짧은 자기 트랜잭션을 여는 것은
     * 바깥 트랜잭션에 조용히 참여해 두 문장이 갈라지는 일을 막기 위해서다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<String> consume(String state, long nowEpochMilli) {
        int updated = jdbcTemplate.update("""
                        UPDATE admin_oauth_state
                           SET consumed_at = :now
                         WHERE state = :state
                           AND consumed_at IS NULL
                           AND expires_at > :now
                        """,
                new MapSqlParameterSource()
                        .addValue("state", state)
                        .addValue("now", nowEpochMilli)
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return jdbcTemplate.queryForList(
                "SELECT nonce FROM admin_oauth_state WHERE state = :state",
                new MapSqlParameterSource("state", state),
                String.class
        ).stream().findFirst();
    }

    /** 만료된 행을 걷는다. 소비된 행도 만료 시각이 지나면 여기에 걸린다. */
    public int deleteExpired(long nowEpochMilli, int limit) {
        return jdbcTemplate.update(
                "DELETE FROM admin_oauth_state WHERE expires_at < :now LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("now", nowEpochMilli)
                        .addValue("limit", limit)
        );
    }
}
