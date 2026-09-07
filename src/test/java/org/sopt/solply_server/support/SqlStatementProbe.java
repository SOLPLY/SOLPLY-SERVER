package org.sopt.solply_server.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 나간 SQL과 <b>그 순간의 트랜잭션 유무</b>를 기록하는 관찰자.
 *
 * <p>하이버네이트 통계({@code Statistics})로는 "문장이 몇 개인가"까지만 알 수 있고, 이 프로젝트가
 * 줄이려는 비용에는 문장을 감싸는 <b>트랜잭션 제어</b>({@code SET autocommit} · {@code COMMIT} …)가
 * 포함된다. 그것을 값으로 만들려면 SQL이 준비되는 순간의 스레드 상태를 봐야 한다 — 하이버네이트가
 * {@code inspect}를 같은 스레드에서 동기 호출하므로 스프링의 스레드 로컬이 그대로 보인다.
 *
 * <p><b>왜 static인가.</b> 하이버네이트가 프로퍼티에 적힌 클래스 이름으로 직접 인스턴스를 만들기
 * 때문에 스프링 빈이 될 수 없다. 테스트는 관찰 직전에 {@link #clear()}를 부른다. 스위트가 순차
 * 실행이고 다른 컨텍스트는 유휴 상태라 간섭은 없다(배치 스케줄러를 끄는 것도 그 때문이다).
 *
 * <p>사용법 — 하위 IT의 {@code @DynamicPropertySource}에서:
 * <pre>{@code
 * registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
 *         SqlStatementProbe.class::getName);
 * }</pre>
 */
public class SqlStatementProbe implements StatementInspector {

    private static final List<String> SQLS = new CopyOnWriteArrayList<>();
    private static final List<Boolean> TX_ACTIVE = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        SQLS.add(sql);
        TX_ACTIVE.add(TransactionSynchronizationManager.isActualTransactionActive());
        return sql;
    }

    public static void clear() {
        SQLS.clear();
        TX_ACTIVE.clear();
    }

    /** 기록된 SQL 문장들 (순서 그대로) */
    public static List<String> sqls() {
        return List.copyOf(SQLS);
    }

    /** 각 문장이 나갈 때 트랜잭션이 실제로 열려 있었는지 */
    public static List<Boolean> txActive() {
        return List.copyOf(TX_ACTIVE);
    }
}
