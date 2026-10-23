package org.sopt.solply_server.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * <b>커넥션을 몇 번 얻었는지</b>를 세는 데이터소스 래퍼.
 *
 * <p>{@code SqlStatementProbe}는 하이버네이트가 만드는 문장만 본다 — 인증이 {@code JdbcTemplate}으로
 * 내려가면 그 관찰자는 아무것도 못 본다. "인증 경로는 저장소를 읽지 않는다"를 재려면 JPA 아래,
 * 곧 커넥션을 얻는 지점에서 세야 한다. 그래서 이 래퍼가 {@link DataSource#getConnection()} 호출을
 * 센다 — 어떤 계층이 무엇으로 질의하든 DB에 닿으려면 여기를 지난다.
 *
 * <p>커넥션 <em>획득</em> 수이지 문장 수가 아니다. 인증이 기대하는 값은 0이므로 둘의 차이는
 * 이 단언에서 의미를 갖지 않는다.
 */
@TestConfiguration
public class ConnectionCountingDataSourceConfig {

    private static final AtomicInteger ACQUIRED = new AtomicInteger();

    public static void clear() {
        ACQUIRED.set(0);
    }

    public static int acquiredConnections() {
        return ACQUIRED.get();
    }

    @Bean
    static BeanPostProcessor connectionCountingDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof DataSource dataSource
                        && !(bean instanceof CountingDataSource)) {
                    return new CountingDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    private static final class CountingDataSource extends DelegatingDataSource {

        private CountingDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            ACQUIRED.incrementAndGet();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            ACQUIRED.incrementAndGet();
            return super.getConnection(username, password);
        }
    }
}
