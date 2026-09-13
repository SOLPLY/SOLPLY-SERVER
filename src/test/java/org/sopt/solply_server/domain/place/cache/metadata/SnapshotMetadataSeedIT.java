package org.sopt.solply_server.domain.place.cache.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.support.MySqlContainerSupport;

/**
 * <b>V46이 커서 회차를 어디서 시작하는가.</b> 이 한 줄이 배포 순간에 떠 있던 스크롤의 운명을
 * 정한다.
 *
 * <p>정책은 "배포 시점의 진행 중 스크롤은 전부 만료"다. 옛 발행 회차의 최대값에 <b>1을 더해</b>
 * 시작하므로, 그 직전에 발급된 커서는 자기가 싣고 있는 번호와 달라 전부 끊긴다. 그대로
 * 이어받으면(+1 없이) 마지막 발행의 커서만 조용히 통과하는데, 그 커서가 가리키는 정렬 배열은
 * 새 구조가 다시 지은 것이라 <b>번호만 맞고 내용이 다른 커서</b>가 된다.
 *
 * <p><b>마이그레이션 파일 자체를 돌린다.</b> Flyway는 컨테이너당 한 번만 도므로 그 결과를 다시
 * 관찰할 수 없다. 그래서 임시 스키마를 만들어 옛 테이블을 세우고 <b>V46의 SQL을 그대로</b> 태운다
 * — 여기서 보는 것이 실제로 배포될 그 파일이라는 것이 이 방식의 요점이다.
 */
class SnapshotMetadataSeedIT extends MySqlContainerSupport {

    private static final String MIGRATION = "db/migration/V46__place_list_snapshot_metadata.sql";

    /**
     * 발행이 있던 DB에서 올 때 — 옛 최대 회차 <b>다음</b> 번호에서 시작한다. 옛 커서(=최대
     * 회차를 싣고 있는 것)는 이 값과 달라 만료된다.
     */
    @Test
    void 옛_발행이_있으면_그_다음_회차에서_시작한다() throws Exception {
        String schema = "v46_seed_with_publications_" + System.nanoTime();
        withSchema(schema, statement -> {
            createLegacyTables(statement);
            statement.executeUpdate("""
                    INSERT INTO place_list_publications (cursor_version) VALUES (40), (41), (137)
                    """);
            statement.executeUpdate(
                    "UPDATE place_list_publication_pointer SET publication_id = 3 WHERE id = 1");

            runMigration(statement);

            assertThat(cursorVersionOf(statement))
                    .as("옛 커서가 싣고 있던 137은 더 이상 통하지 않는다").isEqualTo(138L);
            assertThat(revisionOf(statement))
                    .as("revision은 옛 발행 id의 최대값을 이어받는다").isEqualTo(3L);
        });
    }

    /** 신규·테스트 DB에서 올 때 — 이어받을 것이 없으므로 1에서 시작한다. */
    @Test
    void 옛_발행이_없으면_1에서_시작한다() throws Exception {
        String schema = "v46_seed_empty_" + System.nanoTime();
        withSchema(schema, statement -> {
            createLegacyTables(statement);

            runMigration(statement);

            assertThat(cursorVersionOf(statement)).isEqualTo(1L);
            assertThat(revisionOf(statement)).isZero();
        });
    }

    /** 옛 구조는 이 마이그레이션에서 함께 사라진다. */
    @Test
    void 옛_발행_테이블들은_이_마이그레이션에서_사라진다() throws Exception {
        String schema = "v46_seed_drop_" + System.nanoTime();
        withSchema(schema, statement -> {
            createLegacyTables(statement);

            runMigration(statement);

            for (String table : List.of("place_list_publications",
                    "place_list_publication_pointer", "place_list_snapshot_jobs",
                    "place_list_snapshot_consumer", "place_list_rebuild_requests")) {
                assertThat(tableExists(statement, schema, table))
                        .as("%s가 남아 있다", table).isFalse();
            }
        });
    }

    // === 하네스 ===

    private interface SchemaWork {
        void run(Statement statement) throws Exception;
    }

    /**
     * 임시 스키마를 만들어 그 안에서만 돌린다. 운영 스키마(Flyway가 이미 V46까지 올린 곳)를
     * 건드리면 같은 컨테이너를 쓰는 다른 IT가 통째로 무너진다.
     */
    private void withSchema(String schema, SchemaWork work) throws Exception {
        try (Connection connection = newConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + schema);
            try {
                statement.execute("USE " + schema);
                work.run(statement);
            } finally {
                statement.executeUpdate("DROP DATABASE " + schema);
            }
        }
    }

    /** V43·V45가 만들던 테이블의 <b>이 마이그레이션이 읽고 지우는 부분만</b> 세운다 */
    private static void createLegacyTables(Statement statement) throws Exception {
        statement.executeUpdate("""
                CREATE TABLE place_list_publications (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    cursor_version BIGINT NULL,
                    PRIMARY KEY (id)
                ) ENGINE = InnoDB
                """);
        statement.executeUpdate("""
                CREATE TABLE place_list_publication_pointer (
                    id TINYINT NOT NULL,
                    publication_id BIGINT NULL,
                    PRIMARY KEY (id),
                    CONSTRAINT fk_seed_pointer FOREIGN KEY (publication_id)
                        REFERENCES place_list_publications (id)
                ) ENGINE = InnoDB
                """);
        statement.executeUpdate(
                "INSERT INTO place_list_publication_pointer (id, publication_id) VALUES (1, NULL)");
        statement.executeUpdate(
                "CREATE TABLE place_list_snapshot_jobs (id BIGINT NOT NULL, PRIMARY KEY (id))");
        statement.executeUpdate(
                "CREATE TABLE place_list_snapshot_consumer (id TINYINT NOT NULL, PRIMARY KEY (id))");
        statement.executeUpdate("""
                CREATE TABLE place_list_rebuild_requests (
                    id TINYINT NOT NULL,
                    requested_seq BIGINT NOT NULL DEFAULT 0,
                    processed_seq BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (id)
                ) ENGINE = InnoDB
                """);
    }

    private static void runMigration(Statement statement) throws Exception {
        for (String sql : statementsOf(readMigration())) {
            statement.executeUpdate(sql);
        }
    }

    private static String readMigration() throws IOException {
        try (InputStream in = SnapshotMetadataSeedIT.class.getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            if (in == null) {
                throw new IllegalStateException("마이그레이션을 찾지 못했다 - " + MIGRATION);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 주석 줄을 걷어내고 {@code ;}로 자른다 — 이 파일의 주석에는 세미콜론이 없다 */
    private static List<String> statementsOf(String script) {
        StringBuilder body = new StringBuilder();
        for (String line : script.split("\n")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            body.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String raw : body.toString().split(";")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private static long cursorVersionOf(Statement statement) throws Exception {
        return queryLong(statement,
                "SELECT cursor_version FROM place_list_snapshot_metadata WHERE id = 1");
    }

    private static long revisionOf(Statement statement) throws Exception {
        return queryLong(statement,
                "SELECT revision FROM place_list_snapshot_metadata WHERE id = 1");
    }

    private static boolean tableExists(Statement statement, String schema, String table)
            throws Exception {
        return queryLong(statement, "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + schema + "' AND table_name = '" + table + "'") > 0;
    }

    private static long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * <b>root로 붙는다.</b> 컨테이너의 일반 사용자({@code test})는 자기 데이터베이스에만 권한이
     * 있어 임시 스키마를 만들지 못한다. Testcontainers의 MySQL 이미지는 root 비밀번호를 같은
     * 값으로 둔다.
     */
    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }
}
