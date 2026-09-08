package org.sopt.solply_server.domain.place.cache;

import java.sql.Statement;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 사진({@link PlaceListPhoto})에 붙일 <b>버전 번호를 내주는 곳</b>. 하는 일은
 * {@code place_list_snapshot_versions}에 빈 행 하나를 넣고 AUTO_INCREMENT id를 돌려주는 것뿐이다.
 *
 * <p><b>왜 시각이 아니라 DB 번호인가.</b> 빌더가 하나인 지금은 밀리초 시각으로도 단조였지만,
 * 인스턴스가 둘 이상이 되면 빌더도 둘이 된다(어드민 요청을 받은 인스턴스 + 타이머 리더). 그때
 * 시계가 느린 쪽이 더 새 데이터로 지은 사진이 작은 번호를 달고 {@link PlaceListSnapshot#adopt}의
 * 단조 가드에 "낡은 버전"으로 걸린다. 발급을 한 곳으로 좁히면 시계와 무관하게 번호가 단조다
 * ({@code docs/design/2026-09-09-admin-display-view-split.md} §4-5).
 *
 * <p><b>별도 빈이고 {@code REQUIRES_NEW}인 것이 이 클래스의 존재 이유다.</b> 부르는 곳
 * ({@link PlaceListSnapshotLoader#rebuild()})은 {@code readOnly = true} 트랜잭션이고, MySQL은
 * 읽기 전용 트랜잭션 안의 INSERT를 거부한다. 그래서 발급은 자기 트랜잭션을 열어 INSERT 하나로
 * 즉시 커밋한다. 같은 클래스의 메서드로 두면 자기 호출이라 프록시를 타지 않아 이 전파가
 * 조용히 무시되므로, 빈을 나누는 것이 계약이다. 값을 치르는 것은 커넥션이다 — 발급하는 순간
 * 재빌드의 읽기 커넥션과 이 INSERT 커넥션 <b>둘</b>을 동시에 쥐므로, 풀을 1로 조이면 재빌드가
 * 자기 자신을 기다린다.
 *
 * <p><b>폴백을 두지 않는다.</b> DB가 번호를 못 주면 예외를 그대로 올려 재빌드를 실패시킨다
 * (직전 사진이 그대로 남는 기존 정책). 실패했을 때 밀리초 시각으로 대신 찍으면 번호 공간이 둘로
 * 섞여, 한 번의 폴백이 그 뒤 실제 발급 번호를 전부 "낡은 버전"으로 만든다.
 */
@Component
@RequiredArgsConstructor
public class PlaceListVersionIssuer {

    /**
     * 컬럼을 적지 않는다 — 이 테이블에는 id와 발급 시각뿐이고 둘 다 DB가 채운다. 값을 실어
     * 보내는 순간 발급소가 아니라 데이터 테이블이 된다.
     */
    private static final String ISSUE_SQL =
            "INSERT INTO place_list_snapshot_versions (issued_at) VALUES (CURRENT_TIMESTAMP(3))";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 번호 하나를 발급한다. 한 사진에 한 번만 부르는 것이 "버전↔내용 1:1" 불변식의 전제다.
     *
     * @return 방금 생긴 행의 id. 같은 DB를 보는 모든 빌더에 걸쳐 단조 증가한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long issue() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> connection.prepareStatement(ISSUE_SQL, Statement.RETURN_GENERATED_KEYS),
                keyHolder);
        Number issued = keyHolder.getKey();
        if (issued == null) {
            // 드라이버가 생성 키를 안 돌려준 경우다. 여기서 시각으로 때우면 번호 공간이 섞인다
            throw new IllegalStateException("장소 목록 스냅샷 버전 발급이 번호를 돌려주지 않았다");
        }
        return issued.longValue();
    }
}
