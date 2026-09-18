package org.sopt.solply_server.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 인증 IT 공통 — 스케줄 차단과 <b>애플리케이션 커넥션 풀 밖에서 읽는 길</b>.
 *
 * <p><b>왜 별도 커넥션이 필요한가.</b> 이 스위트가 증명하려는 것 대부분이 "커밋됐는가"다 —
 * 재사용 감지의 전체 폐기, 자식 INSERT 실패 시 부모 롤백, 회전 경쟁의 승패. 같은 커넥션으로
 * 읽으면 아직 커밋되지 않은 자기 변경이 보여 단언이 거짓 양성이 된다. 여기서 여는 커넥션은
 * 스프링도 하이버네이트도 모르는 완전히 독립된 세션이므로, 보이는 것은 <b>커밋된 것뿐</b>이다.
 *
 * <p><b>cron을 전부 끄는 이유.</b> {@code @SpringBootTest}는 실제 앱을 띄우므로 {@code @Scheduled}가
 * 등록된다. 스위트가 매시 :15·:30이나 04:40을 지나는 순간 배치가 돌아 다른 IT가 의존하는
 * place_stats를 덮거나, refresh 보존 정리가 이 클래스가 심어 둔 행을 지운다.
 * {@code "-"}는 스프링이 "등록하지 않음"으로 읽는 센티널이다({@code Scheduled.CRON_DISABLED}).
 */
public abstract class AuthMySqlSupport extends MySqlContainerSupport {

    /** 베이스의 {@code datasource}와 이름이 겹치면 상위를 숨겨 데이터소스 설정이 사라진다. */
    @DynamicPropertySource
    static void authSuiteProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    /** 애플리케이션 풀과 무관한 새 세션. 커밋된 것만 보인다. */
    protected static Connection openIndependentConnection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 독립 커넥션으로 읽은 refresh 행들. 컬럼은 문자열 키의 맵으로 그대로 돌려준다. */
    protected static List<Map<String, Object>> readCommittedRows(String sql, Object... args) {
        try (Connection con = openIndependentConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= columns; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("독립 커넥션 조회 실패: " + sql, e);
        }
    }

    protected static void executeOnIndependentConnection(String sql) {
        try (Connection con = openIndependentConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("독립 커넥션 실행 실패: " + sql, e);
        }
    }
}
