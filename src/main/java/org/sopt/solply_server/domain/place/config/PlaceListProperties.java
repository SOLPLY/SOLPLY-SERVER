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
     * 캐시가 아니라 버그이고, 그 상태로 낸 수치는 서로 다른 응답의 비용을 비교한 것이라 뜻이 없다.
     *
     * <p><b>기본값 {@code MEMORY}는 판정의 결과다</b> (2026-08-16 같은 창 세 구조 캠페인,
     * {@code docs/perf/2026-08-16-sort6-structures-surge.md}). {@code DB}는 폴백으로 한 세대
     * 유지한다 — V36 인덱스와 {@link #forceSortIndex}가 그 몫이다.
     *
     * <p><b>{@code MEMORY}가 아니면 스냅샷을 <em>짓지도</em> 않는다</b> — 골격 스위치와 같은
     * 근거다. 읽지 않는 값을 매시 짓는 낭비이기도 하지만, 더 중요한 것은 기준선에 빌드 시점의
     * CPU·풀 점유가 섞이면 방식 간 차이가 캐시 효과인지 배치 잡음인지 갈라낼 수 없다는 점이다.
     * 그래서 런타임에 {@code MEMORY}로 바꿔도 <b>다음 재생성 트리거까지는 스냅샷이 없고</b>,
     * 그 사이 조회는 DB 경로로 되돌아간다. 벤치는 재기동으로 구성을 바꾸므로 기동 워밍업이 그
     * 자리를 메운다.
     */
    private SortSource sortSource = SortSource.MEMORY;

    /**
     * 정적 정렬 3종(평점·리뷰 수·북마크 수)의 쿼리에 <b>의도 인덱스를 {@code FORCE INDEX}로 고정</b>할지.
     * 인기·최신·거리는 이 스위치와 무관하다 — 셋은 이미 의도한 계획으로만 돈다.
     *
     * <p><b>벤치의 A강제 팔을 위한 진단 스위치이고, 운영의 기본이 아니다.</b> 기본값 false가 그
     * 뜻이다. 켜고 끄는 것으로 응답이 달라지면 안 되며(같은 결과를 다른 계획으로 얻을 뿐),
     * 그 등가는 {@code PlaceListDbQueryRepositoryIT}가 두 모드를 나란히 돌려 문다.
     *
     * <p><b>왜 이 스위치가 필요한가.</b> V36이 지은 세 인덱스는 컬럼 집합이 V34 인기순 인덱스
     * ({@code idx_place_stats_town_score})의 부분집합이라 세 쿼리에 다섯 인덱스가 전부 커버링이
     * 된다. 그러면 옵티마이저는 WHERE + 커버링 비용만으로 인덱스를 정하고 <b>순서를 얻으려고
     * 인덱스를 바꾸지 않으므로</b>, 근소하게 싼 score 인덱스로 몰려 filesort가 붙는다. 1페이지
     * 45칸이 전멸했고, 2페이지에서는 커서에 실려 온 정렬키 리터럴이 range 추정을 움직여 계획이
     * <em>직전 페이지 이력</em>에 따라 갈렸다
     * ({@code docs/perf/2026-08-16-sort6-index-path.md}).
     *
     * <p><b>꺼져 있으면 SQL 문장이 바이트째 지금과 같아야 한다.</b> 힌트가 미발동일 때 문장이
     * 불변인 것은 이 저장소의 조건부 힌트 선례와 같은 계약이다(태그 술어의 마스크 0 규칙,
     * {@code appendTagFilters}). 그래야 A자연 팔이 "스위치를 들이기 전"과 같은 문장을 돌린 것이
     * 되어 두 팔의 대조가 성립한다.
     *
     * <p><b>채택 여부는 아직 정해지지 않았다.</b> 반사실 대조에서 강제가 손해를 보는 요청 형상은
     * 없었지만(단일 동네 9배 이득, 다중 동네 동일), "힌트로 옵티마이저를 이기는 구조를 하나 더
     * 들이는" 선택이라 같은 창 A자연/A강제/B 캠페인의 실측 뒤에 판단한다.
     */
    private boolean forceSortIndex = false;

    /**
     * 정적 5축을 <b>어느 구조로</b> 답할지. 비교를 위해 마련한 경계이며, 이 값 하나로 후보를
     * 바꿔 가며 같은 요청을 돌릴 수 있다.
     *
     * <p><b>기동 시 고르는 값이다.</b> 도중에 바꾸면 커서가 싣고 있는 범위 표현의 체계가 달라져
     * 진행 중이던 탐색이 끊긴다({@code PlaceListCursor}의 {@code T}/{@code G} 머리글자).
     *
     * <p><b>기본값 {@code TOWN_LAZY_SORT}는 두 옛 후보의 갈림을 없앤 구조다.</b> 2026-09-21
     * 비교 캠페인은 사전정렬과 요청 정렬의 CPU·지연·할당 차이를 구별해 내지 못했고, 사전정렬은
     * 인기 한 축만 조회하는 요청에도 읽히지 않는 네 축을 준비했다. 그 판정에서 남은 것은 두
     * 가지다 — <b>쓰지 않을 축은 준비하지 말 것</b>, 그리고 <b>같은 축을 같은 번호로 반복 정렬하지
     * 말 것</b>. 기본 경로는 객체만 적재한 뒤 요청받은 축을 그 자리에서 한 벌 만들어 그 번호가
     * 사는 동안 다시 쓴다.
     *
     * <p>나머지는 비교 경로로 그대로 남는다. {@code TOWN_PRESORTED}는 다섯을 미리 세우고,
     * {@code TOWN_REQUEST_SORT}는 요청마다 합집합을 한 번 정렬한다(재사용 없음).
     * {@code GLOBAL_SNAPSHOT}은 새 계약의 채택 후보가 아니라 비용 기준선이고,
     * {@code DB_DIRECT}는 이 창에서 앱 CPU 절감을 확인하지 못한 채 DB CPU와 표본 추정 할당이
     * 컸다. 설정으로 언제든 되돌릴 수 있다({@code solply.place-list.list-source}).
     *
     * <p><b>기본값 변경은 아직 측정 근거가 아니다.</b> 최초 생성 비용과 반복 절감을 실제로
     * 견준 수치는 없다 — 그 확인은 이번 작업의 뒤 단계에 있다.
     */
    private ListSource listSource = ListSource.TOWN_LAZY_SORT;

    /**
     * 다섯 구조. <b>기본값이 곧 우승 선언은 아니다</b> — 이 enum은 같은 조건에서 견줄 자리를
     * 만들고, 기본값은 채택한 구조다. 나머지 넷은 비교 경로다.
     */
    public enum ListSource {

        /** 전 동네를 한 벌로 지어 두는 기존 구조. 거리순은 이 값과 무관하게 언제나 이 경로다. */
        GLOBAL_SNAPSHOT,

        /** 요청받은 동네만 적재하고, 그 동네의 다섯 축을 미리 세워 둔다. */
        TOWN_PRESORTED,

        /** 요청받은 동네만 적재하되 정렬은 요청 시점에 한다. 준비 비용과 요청 비용의 맞교환. */
        TOWN_REQUEST_SORT,

        /**
         * <b>채택 구조.</b> 요청받은 동네의 객체만 적재하고, 정적 축의 배열은 그 축을 처음
         * 요청받은 자리에서 한 벌 만들어 같은 {@code (동네, 번호)}가 사는 동안 다시 쓴다.
         *
         * <p>{@link #TOWN_PRESORTED}와 다른 점은 <b>언제 만드는가</b>뿐이다 — 읽는 방식도
         * 커서·병합도 같은 코드를 지난다. {@link #TOWN_REQUEST_SORT}와 다른 점은 만든 배열을
         * <b>버리지 않는다</b>는 것이다.
         *
         * <p>배열 생성은 물어본 요청의 스레드에서 돌고, 같은 축을 동시에 물은 요청들은 하나의
         * 생성을 나눠 쓴다. 원소가 이미 캐시에 있으면 배열 때문에 DB를 다시 읽지 않는다.
         */
        TOWN_LAZY_SORT,

        /**
         * 캐시 없이 DB가 정렬·페이지를 맡는다.
         *
         * <p><b>일반 목록 요청 경로에 연결돼 있다.</b> 고르면 정적 5축 요청이
         * {@code PlaceListRequestOrchestrator}의 동네 경로로 들어와 캐시 조회 없이
         * {@code TownDbDirectReader}로 간다. 그 안에서 REQUIRES_NEW·읽기 전용·REPEATABLE READ
         * 트랜잭션 하나를 열고 동네 번호 관측 → 커서 범위 비교 → 페이지 문장 → 페이지
         * place_id의 정렬 키·표시값 hydrate를 같은 read view에서 끝낸다. SQL은
         * {@code PlaceListDbQueryRepository}의 것을 그대로 부른다.
         *
         * <p><b>예산도 커서도 다른 두 동네 후보와 같다.</b> 동네 경로로 들어오는 셋
         * ({@link #TOWN_PRESORTED}·{@link #TOWN_REQUEST_SORT}·이 값)이 요청 진입에서 시작한
         * 같은 총예산 안에서 돌고(커넥션 대기와 SQL 실행을 면제하지 않는다), 만료 판정도 같은
         * 범위 표현 비교다.
         *
         * <p><b>{@link #GLOBAL_SNAPSHOT}은 여기서 빠진다.</b> 그쪽은 기존 전역 경로를 그대로
         * 써서 커서가 {@code v7}이고 대기 예산도 기존 전역 회차 catch-up 예산이다. 거리순과
         * 북마크 검색도 이 값과 무관하게 그 전역 경로로 간다.
         */
        DB_DIRECT
    }

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
