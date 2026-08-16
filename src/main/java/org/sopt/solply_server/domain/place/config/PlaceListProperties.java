package org.sopt.solply_server.domain.place.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 목록 조회 경로의 런타임 스위치.
 *
 * <p><b>기본값이 저장소에 남는 유일한 선언이다.</b> {@code application.yml}은 .gitignore의
 * {@code *.yml}에 걸려 커밋되지 않으므로(시크릿이 들어 있다) yml에 적은 값은 각 환경에만 존재한다.
 * {@code PlaceStatsProperties}와 같은 사정이다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "solply.place-list")
public class PlaceListProperties {

    /**
     * 목록 응답의 골격 필드(이름·썸네일 URL·대표 태그·동네 id)를 어디서 얻는가.
     *
     * <p><b>A/B/C를 같은 빌드에서 돌리기 위한 스위치다.</b> 이 값이 무엇이든 응답 body와 커서
     * 토큰은 같아야 한다. 다르면 캐시가 아니라 버그다.
     *
     * <p><b>{@code SNAPSHOT}이 아니면 스냅샷을 <em>짓지도</em> 않는다.</b> 읽지 않는 값을 매시
     * 짓는 것은 순전한 낭비이기도 하지만, 더 중요한 이유는 측정이다 — 다른 방식이 비교의
     * 기준선인데 거기에 빌드 시점의 CPU·풀 점유가 섞이면 방식 간 차이가 캐시 효과인지 배치
     * 잡음인지 갈라낼 수 없다.
     *
     * <p>런타임에 {@code SNAPSHOT}으로 되돌리면 다음 카운트 배치(≤1h)에서 스냅샷이 채워진다.
     * 그 전까지는 전량 미스라 응답은 여전히 옳고 성능만 기존과 같다.
     */
    private SkeletonSource skeletonSource = SkeletonSource.SNAPSHOT;

    /**
     * 목록의 <b>정렬·필터·페이징</b>을 누가 하는가. 골격 스위치와 축이 다르다 — 이쪽은 "어떤 장소가
     * 어떤 순서로 나오는가"를 정하고, 골격은 "정해진 id에 표시값을 어디서 붙이는가"를 정한다.
     *
     * <p><b>후보 A(DB 인덱스 정렬)와 후보 B(인메모리 스냅샷)를 같은 빌드에서 팔 교대로 재기 위한
     * 스위치다.</b> 이 값이 무엇이든 응답 body와 커서 토큰은 <b>바이트째</b> 같아야 한다. 다르면
     * 캐시가 아니라 버그이고, 그 상태로 낸 수치는 서로 다른 응답의 비용을 비교한 것이라 뜻이 없다
     * ({@code PlaceSortSnapshotIT}의 모드 등가 게이트가 판정보다 먼저 통과해야 하는 조건이다).
     *
     * <p><b>기본값이 {@code DB}인 이유는 판정 전이기 때문이다.</b> 후보 A는 이미 완성돼 있고 B는
     * 아직 재는 중이라, 아무것도 정하지 않은 환경이 밟는 경로는 A여야 한다.
     *
     * <p><b>{@code MEMORY}가 아니면 스냅샷을 <em>짓지도</em> 않는다</b> — 골격 스위치와 같은
     * 근거다. 읽지 않는 값을 매시 짓는 낭비이기도 하지만, 더 중요한 것은 기준선에 빌드 시점의
     * CPU·풀 점유가 섞이면 방식 간 차이가 캐시 효과인지 배치 잡음인지 갈라낼 수 없다는 점이다.
     * 그래서 런타임에 {@code MEMORY}로 바꿔도 <b>다음 재생성 트리거까지는 스냅샷이 없고</b>,
     * 그 사이 조회는 DB 경로로 되돌아간다. 벤치는 재기동으로 팔을 바꾸므로 기동 워밍업이 그 자리를
     * 메운다 ({@code PlaceSortWarmup}).
     */
    private SortSource sortSource = SortSource.DB;

    public enum SortSource {
        /** 정렬·필터·페이징을 DB에 맡긴다 (V34·V36 인덱스) — 후보 A. */
        DB,
        /** 사전 정렬 스냅샷에서 seek·머지로 만든다 — 후보 B. */
        MEMORY
    }

    public enum SkeletonSource {
        /** 배치 회차마다 미리 지은 스냅샷. 요청당 골격 쿼리 0회 — 채택안. */
        SNAPSHOT,
        /**
         * 스냅샷 로더의 SQL·조립식을 <b>페이지 id로 제한해</b> 요청마다 실행한다. 쿼리 횟수는
         * {@link #ENTITY}와 같은 2회이므로 두 방식의 차이가 엔티티 하이드레이션 비용만 남는다.
         */
        PROJECTION,
        /** 캐시 도입 이전 경로 — 태그 페치조인 엔티티 + 이미지 지연로딩. */
        ENTITY
    }
}
