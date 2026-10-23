package org.sopt.solply_server.domain.place.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

class PlaceMainTagIndexMigrationIT {

    @Test
    void rejectsMultipleMainTagsAndBackfillsExistingRowsWithoutChangingMask() throws Exception {
        // V48 이전 배포 데이터가 있는 상태를 별도 DB에서 재현한다. 다른 IT의 스키마를 수정하지 않는다.
        try (var mysql = new MySQLContainer<>("mysql:8.0")) {
            mysql.start();
            try (var connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE tags (id BIGINT PRIMARY KEY, type VARCHAR(20))");
                sql.execute("CREATE TABLE place_tag (place_id BIGINT, tag_id BIGINT)");
                sql.execute("""
                        CREATE TABLE place_stats (
                            place_id BIGINT PRIMARY KEY, town_id BIGINT, main_tag_id BIGINT,
                            tag_bitmask BIGINT, popular_score DECIMAL(18,6), created_at DATETIME,
                            bookmark_count BIGINT, review_count BIGINT, avg_rating DECIMAL(3,2),
                            score_calculated_at DATETIME)
                        """);
                sql.execute("INSERT INTO tags VALUES (1, 'MAIN'), (2, 'MAIN'), (3, 'OPTION1')");
                sql.execute("INSERT INTO place_tag VALUES (10, 1), (10, 2), (10, 3)");
                sql.execute("""
                        INSERT INTO place_stats (place_id, town_id, main_tag_id, tag_bitmask)
                        VALUES (10, 100, 99, 14), (11, 100, 99, 0)
                        """);
                Flyway flyway = Flyway.configure()
                        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(true).baselineVersion("48").target("49").load();
                assertThatThrownBy(flyway::migrate)
                        .hasStackTraceContaining("a listed place has multiple MAIN tags");

                // 원본을 정리한 뒤 실패한 Flyway 이력을 repair하고 재적용할 수 있다.
                sql.execute("DELETE FROM place_tag WHERE place_id = 10 AND tag_id = 2");
                sql.execute("UPDATE place_stats SET tag_bitmask = 10 WHERE place_id = 10");
                flyway.repair();
                flyway.migrate();
                try (var rows = sql.executeQuery(
                        "SELECT main_tag_id, tag_bitmask FROM place_stats ORDER BY place_id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                    assertThat(rows.getLong(2)).isEqualTo(10); // 메인 비트(2)와 옵션 비트(8) 유지
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isNull();
                    assertThat(rows.getLong(2)).isZero();
                }
            }
        }
    }
}
