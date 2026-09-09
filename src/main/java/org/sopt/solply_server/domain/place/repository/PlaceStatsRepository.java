package org.sopt.solply_server.domain.place.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code place_stats}의 쓰기·읽기 문장 모음. 장소당 행 하나이고 칸마다 주인이 다르다 —
 * 컬럼별 소유 주체는 {@link PlaceStats} javadoc의 표에 있다.
 *
 * <p><b>주체들의 대입 목록이 서로 겹치면 안 된다.</b> 겹치는 순간 늦게 도는 쪽이 상대의 신선한
 * 값을 자기가 읽은 낡은 스냅샷으로 되돌린다. 이 계약은 SQL의 {@code SET}·
 * {@code ON DUPLICATE KEY UPDATE} 목록에만 존재하므로 컬럼을 더할 때 반드시 어느 쪽 소유인지
 * 먼저 정할 것 ({@code PlaceStatsBatchProcessorIT}의 소유권 테스트들이 감시한다).
 */
public interface PlaceStatsRepository extends JpaRepository<PlaceStats, Long> {

    /**
     * 표시 카운트 셋(북마크 수·리뷰 수·평균 평점)을 원본에서 재계산해 <b>이미 존재하는 행에만</b>
     * 덮어쓴다. 문장 하나라 원자성을 물을 지점이 없다.
     *
     * <p><b>매시 회차의 문장이었으나 지금은 새벽 안전망의 것이다.</b> 북마크 축이 아웃박스 델타로
     * 넘어가면서 매시 자리는 {@link #updateReviewCounts} + 델타 소비가 맡고, 이 전량 재계산은
     * "버그로 생긴 표류의 상한을 하루로" 잡는 마지막 겹으로 남았다
     * ({@code docs/design/2026-08-17-bookmark-outbox-delta.md} 4-4). 세 축을 함께 덮으므로
     * <b>부르는 트랜잭션은 자기가 읽은 시점까지의 아웃박스를 같은 트랜잭션에서 비워야 한다</b> —
     * 그러지 않으면 이미 셈에 들어간 토글을 다음 델타 회차가 또 더한다
     * ({@code PlaceStatsBatchProcessor#recalculateCountsAndClearOutbox}).
     *
     * <p><b>UPDATE이지 UPSERT가 아닌 것이 이 문장의 요점이다.</b> 행의 존재와 어드민 소유 칸
     * ({@code town_id}·{@code created_at}·{@code tag_bitmask}·{@code name}·좌표 둘·
     * {@code main_tag_id}·{@code thumbnail_file_key})의 주인은 어드민 쓰기 트랜잭션 하나이고
     * ({@code AdminPlaceService}), 배치는 거기에 손대지 않는다. 예전에는 이 회차가 활성 장소 전량을 원본에서 다시 지어 그 칸들까지
     * 덮었는데, 그것은 안전망이 아니라 <b>어드민이 방금 커밋한 값을 배치가 읽은 낡은 스냅샷으로
     * 되돌릴 수 있는 경로</b>였다.
     *
     * <p>그래서 {@code places}·{@code place_tag}가 이 문장에 없다. 딸려 사라진 것이 둘이다 —
     * FK 부모 검사가 {@code places}에 남기던 S 락(어드민의 동네 일괄 작업을 배치 시간만큼 세우던
     * 유일한 차단)과, 태그 비트마스크를 매 회차 다시 짓던 {@code BIT_OR} 집계.
     *
     * <p><b>{@code created_at <= :calculatedAt} 상한을 지우지 말 것.</b> 잃는 것은 성능이 아니라
     * 멱등성이라는 문장의 참/거짓이다 — 같은 기준 시각으로 다시 돌렸을 때 그 사이 들어온 활동이
     * 결과를 바꾼다면 "회차를 재실행해도 안전하다"가 성립하지 않는다.
     *
     * <p><b>세 값 모두 COALESCE로 0을 채운다 (V37).</b> {@code avg_rating}은 예전에 NULL을 그대로
     * 흘려보냈지만, 평점순이 리뷰 0건 장소를 0점으로 맨 뒤에 싣게 되면서 컬럼이 NOT NULL로 조여졌다.
     * <b>저장 시점의 COALESCE는 인덱스와 무관하다</b> — 인덱스가 못 견디는 것은 조회의 정렬식에
     * COALESCE가 끼는 경우이고, 여기서는 컬럼에 실값이 들어갈 뿐이다. "평점 0점"과 "리뷰 없음"의
     * 구분은 응답 매핑이 맡는다 ({@code PlacePreviewDto}).
     *
     * <p><b>⚠️ 반드시 {@code READ_COMMITTED}에서 호출할 것.</b> 두 소스 테이블을 훑는 성질은
     * {@link #updateScores}와 같다 — REPEATABLE READ면 스캔 행에 shared next-key 락이 걸려 동시
     * 북마크·리뷰 INSERT가 {@code ERROR 1205}로 죽는다(벤치 실측 1,063만 건 대 0건).
     *
     * <p>인덱스 전제: 북마크 축은 {@code idx_bookmark_target}(V23), 리뷰 축은
     * {@code idx_place_reviews_place_created_rating}(V22)로 각각 인덱스 전용 스캔이어야 한다.
     *
     * <p><b>SET 목록에 회차 시각을 넣지 말 것 (V35).</b> InnoDB는 새 값이 기존 값과 전부 같은 행의
     * 쓰기를 생략하는데, 회차마다 반드시 달라지는 값이 하나라도 끼면 그 판정이 전 행에서 무조건
     * 실패한다 — 한 시간 동안 아무 활동도 없던 장소까지 매시 다시 쓰이고 undo·redo·binlog가
     * 그만큼 따라온다. 지금 이 문장이 실제로 건드리는 것은 <b>카운트가 달라진 장소뿐</b>이다.
     * 배치가 마지막으로 돈 시각이 필요하면 {@code shedlock} 테이블(V27)의
     * {@code place-stats-count} 행을 볼 것.
     *
     * @param calculatedAt 이번 회차의 기준 시각이자 <b>집계 대상의 상한</b>
     * @return <b>조건에 걸린</b> 행 수 = 그 시점의 목록 노출 대상 장소 수. 실제로 값이 바뀐 행 수가
     *         아니다 — Connector/J의 기본값({@code useAffectedRows=false})이 changed가 아니라
     *         matched를 돌려주므로, 위 최적화로 쓰기가 줄어도 이 수치는 장소 수를 그대로 센다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE place_stats ps
        LEFT JOIN (
            SELECT bm.target_id AS place_id,
                   COUNT(*) AS cnt
            FROM bookmarks bm
            WHERE bm.target_type = 'PLACE'
              AND bm.created_at <= :calculatedAt
            GROUP BY bm.target_id
        ) b ON b.place_id = ps.place_id
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   AVG(pr.rating) AS avg_rating
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
            GROUP BY pr.place_id
        ) r ON r.place_id = ps.place_id
        SET ps.bookmark_count = COALESCE(b.cnt, 0),
            ps.review_count   = COALESCE(r.cnt, 0),
            ps.avg_rating     = COALESCE(r.avg_rating, 0)
        """, nativeQuery = true)
    int updateCounts(@Param("calculatedAt") LocalDateTime calculatedAt);

    /**
     * 리뷰 축(리뷰 수·평균 평점)만 원본에서 재계산해 <b>이미 존재하는 행에만</b> 덮어쓴다 =
     * 매시 카운트 회차의 절반. 나머지 절반인 북마크 축은 아웃박스 델타 소비가 맡는다
     * ({@code BookmarkCountDeltaProcessor}).
     *
     * <p><b>{@code bookmark_count}가 SET 목록에 없는 것이 이 문장의 존재 이유다.</b> 북마크 축의
     * 주인이 전량 재계산에서 델타 소비로 넘어갔으므로, 여기서 함께 세면 회차마다 델타가 방금 더한
     * 값을 전량 스캔 결과로 되돌린다 — 대입 목록이 겹치면 안 된다는 이 인터페이스의 계약 그대로다.
     *
     * <p>리뷰 축은 델타로 만들지 않았다. 아팠던 축은 북마크뿐이고(리뷰 축은 원천 행 수가 그
     * 1.9%, 실측 117ms — {@code docs/design/2026-08-15-bookmark-count-supply.md}), 신선도 ≤1h를
     * 지키는 가장 단순한 수단이 매시 재계산이기 때문이다.
     *
     * <p>나머지 계약은 {@link #updateCounts}를 그대로 상속한다: {@code READ_COMMITTED} 필수,
     * {@code created_at <= :calculatedAt} 상한(= 멱등성), {@code COALESCE}로 0 채우기, SET 목록에
     * 회차 시각 금지. 근거는 옮겨 적지 않고 그쪽 javadoc <b>한 곳에만</b> 둔다.
     *
     * @param calculatedAt 이번 회차의 기준 시각이자 <b>집계 대상의 상한</b>
     * @return <b>조건에 걸린</b> 행 수 = 그 시점의 목록 노출 대상 장소 수 — 근거는 {@link #updateCounts}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE place_stats ps
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   AVG(pr.rating) AS avg_rating
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
            GROUP BY pr.place_id
        ) r ON r.place_id = ps.place_id
        SET ps.review_count = COALESCE(r.cnt, 0),
            ps.avg_rating   = COALESCE(r.avg_rating, 0)
        """, nativeQuery = true)
    int updateReviewCounts(@Param("calculatedAt") LocalDateTime calculatedAt);

    /**
     * 활성 장소 전량의 행을 원본에서 다시 짓는다 = <b>기동 시 최초 적재와 운영 복구의 문장</b>.
     * 정기 회차는 이 문장을 쓰지 않는다 ({@link #updateCounts}).
     *
     * <p>필요한 자리가 둘이다. 하나는 V32·V34처럼 {@code place_stats}를 재생성한 배포 직후 —
     * 테이블이 비어 있고 어드민이 다시 저장해 줄 장소가 없다. 다른 하나는 운영자가 DB에 직접
     * SQL을 날려 어드민 트랜잭션을 지나친 뒤, 파생 컬럼을 원본 기준으로 되맞출 때다.
     *
     * <p><b>{@code WHERE p.active = 1}이 조회의 불변식("행이 있는 장소 = 목록에 나와도 되는
     * 장소")의 출발점을 만든다</b> — 비활성 장소는 여기서 아예 들어가지 않으므로 목록 쿼리가
     * places를 되짚어 활성 여부를 묻지 않아도 된다. 다만 이 문장은 "새로 넣지 않을" 뿐 이미 있는
     * 행을 지우지 않는다. 지우는 주체는 {@link #deleteByPlaceIds} 하나다.
     *
     * <p><b>{@code ON DUPLICATE KEY UPDATE}가 필요한 이유는 재실행 가능성 하나다.</b> 최초 적재의
     * 가드는 리더 선출이 아니라 단순 카운트 검사라 두 인스턴스가 동시에 통과할 수 있고, 그때
     * 뒤에 온 쪽이 중복 키로 죽는 대신 같은 값을 덮어써야 한다. {@code popular_score}가 이 목록에
     * 끼면 재실행이 새벽에 계산한 점수를 0으로 되돌린다.
     *
     * <p><b>{@code BIT_OR(1 << pt.tag_id)}는 tag id ≤ 62를 전제한다.</b> 넘으면 다른 태그의 자리를
     * 조용히 덮어써 필터 결과가 틀린다. 그 상한을 지키는 것은 {@code AdminTagService#createTag}의
     * 가드이고, 읽기 쪽 {@code TagBitmask}가 같은 상한에서 예외를 던진다.
     *
     * <p><b>⚠️ 반드시 {@code READ_COMMITTED}에서 호출할 것.</b> {@code INSERT ... SELECT}는
     * REPEATABLE READ에서 소스 테이블 <em>전체</em>에 shared next-key 락을 걸어 동시
     * 북마크·리뷰 INSERT를 {@code ERROR 1205}로 죽인다. RC에서도 FK 부모 검사로 {@code places}에
     * 갱신 행 수만큼 S 락이 커밋까지 남아 어드민의 동네 일괄 작업이 대기한다 — 이 문장이 기동·복구
     * 전용인 지금은 그 대기가 정기적으로 일어나지 않는다.
     * 전제: {@code binlog_format = ROW}. STATEMENT/MIXED면 이 문장 자체가 {@code ERROR 1665}로 거부된다.
     *
     * <p><b>썸네일을 상관 서브쿼리로 고르는 이유.</b> 이 문장은 기동·복구의 전량이거나 어드민 쓰기
     * 한 건({@link #upsertRowsForActivePlaces}의 PK IN 소수 행)이라, 장소마다 서브쿼리를 한 번씩
     * 도는 비용을 받아들인다. {@code docs/design/2026-09-09-rebuild-streaming.md} §4-3이 측정으로
     * 기각한 것은 <b>재빌드마다</b> 도는 같은 서브쿼리다 — 10분 주기 × 전 장소에서는 그 비용이
     * 문장 하나를 더 도는 것보다 비쌌지만, 여기서는 어드민 빈도의 일회성 비용이다.
     *
     * <p>{@code VALUES(col)}은 deprecated라 실행마다 {@code Warning 1287}이 참조 수만큼 뜬다.
     * {@code INSERT ... SELECT}에서는 행 별칭 문법이 {@code ERROR 1054}로 깨져 쓸 수 없고,
     * 대안은 SELECT 전체를 파생 테이블로 감싸는 형태뿐이다 — MySQL 8.4 이상으로 올려 함수가
     * 실제로 제거될 때 그 형태로 교체할 것.
     *
     * @param calculatedAt 기준 시각이자 <b>집계 대상의 상한</b>
     * @return 영향 행 수 (MySQL은 INSERT를 1, UPDATE를 2로 센다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO place_stats (
            place_id, town_id, created_at, tag_bitmask,
            name, latitude, longitude, main_tag_id, thumbnail_file_key,
            bookmark_count, review_count, avg_rating)
        SELECT p.id,
               p.town_id,
               p.created_at,
               COALESCE(t.mask, 0),
               p.name,
               p.latitude,
               p.longitude,
               mpt.tag_id,
               (SELECT pi.image_file_key
                  FROM place_images pi
                 WHERE pi.place_id = p.id
                 ORDER BY pi.display_order, pi.image_file_key
                 LIMIT 1),
               COALESCE(b.cnt, 0),
               COALESCE(r.cnt, 0),
               COALESCE(r.avg_rating, 0)
        FROM places p
        LEFT JOIN (
            SELECT pt.place_id AS place_id,
                   BIT_OR(1 << pt.tag_id) AS mask
            FROM place_tag pt
            GROUP BY pt.place_id
        ) t ON t.place_id = p.id
        LEFT JOIN (
            SELECT pt.place_id AS place_id,
                   MIN(pt.id) AS pt_id
            FROM place_tag pt
            JOIN tags tg ON tg.id = pt.tag_id
            WHERE tg.type = 'MAIN'
            GROUP BY pt.place_id
        ) mm ON mm.place_id = p.id
        LEFT JOIN place_tag mpt ON mpt.id = mm.pt_id
        LEFT JOIN (
            SELECT bm.target_id AS place_id,
                   COUNT(*) AS cnt
            FROM bookmarks bm
            WHERE bm.target_type = 'PLACE'
              AND bm.created_at <= :calculatedAt
            GROUP BY bm.target_id
        ) b ON b.place_id = p.id
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   AVG(pr.rating) AS avg_rating
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
            GROUP BY pr.place_id
        ) r ON r.place_id = p.id
        WHERE p.active = 1
        ON DUPLICATE KEY UPDATE
            town_id            = VALUES(town_id),
            created_at         = VALUES(created_at),
            tag_bitmask        = VALUES(tag_bitmask),
            name               = VALUES(name),
            latitude           = VALUES(latitude),
            longitude          = VALUES(longitude),
            main_tag_id        = VALUES(main_tag_id),
            thumbnail_file_key = VALUES(thumbnail_file_key),
            bookmark_count     = VALUES(bookmark_count),
            review_count       = VALUES(review_count),
            avg_rating         = VALUES(avg_rating)
        """, nativeQuery = true)
    int rebuildRowsFromSource(@Param("calculatedAt") LocalDateTime calculatedAt);

    /**
     * 지정한 <b>활성</b> 장소들의 행을 원본에서 다시 지어 넣는다 = 어드민 쓰기 경로가 목록을
     * 그 자리에서 맞추는 문장. 생성·수정·재활성이 모두 이 하나를 쓴다.
     *
     * <p><b>{@code WHERE p.active = 1}이 불변식을 지킨다.</b> 비활성 장소는 여기서 걸러지므로,
     * 내려간 장소를 수정해도 행이 되살아나지 않는다 — "행이 있는 장소 = 목록에 나와도 되는 장소"가
     * 이 문장 하나로 유지된다. 그래서 세 경로가 분기 없이 같은 문장을 부를 수 있다.
     *
     * <p><b>{@code ON DUPLICATE KEY UPDATE}가 건드리는 것은 어드민 소유 여덟 칸뿐이다.</b> 표시 카운트
     * 셋과 점수 배치 소유의 두 칸은 그대로 둔다 — 태그를 고쳤다고 북마크 수가 0으로 돌아가면 안 된다.
     * 반대로 <b>신규 행</b>은 카운트
     * 0·평점 0·점수 0으로 들어가고, 두 정렬 모두 그 자리에서 장소를 보여준다 — 인기순은 점수 0
     * 자리에, 최신순은 맨 앞에 (근거는 {@code PlaceListDbQueryRepository#findPopularRows}).
     *
     * <p><b>{@code BIT_OR(1 << pt.tag_id)}는 tag id ≤ 62를 전제한다</b> — 근거와 가드는
     * {@link #rebuildRowsFromSource} javadoc과 같다.
     *
     * <p><b>어드민 소유 칸이 다섯 늘면서(V40 — {@code name}·좌표 둘·{@code main_tag_id}·
     * {@code thumbnail_file_key}) 표시값만 고친 수정도 이 문장을 부른다.</b> 이름이
     * {@code place_stats}의 칸이 된 순간 "소속 열쇠가 같으면 파생 컬럼도 그대로"라는 전제가 깨졌기
     * 때문이다. {@code main_tag_id}는 {@code place_tag.id} 오름차순 첫 MAIN 태그이고 <b>태그의 활성
     * 여부로 거르지 않는다</b> — 거르면 다음 MAIN 태그가 뽑혀 엔티티 경로와 값이 갈린다.
     *
     * <p><b>비동기 이미지 이동 후처리도 이 문장을 부른다</b> ({@code PlaceImageFieldUpdater}).
     * 어드민 커밋 뒤에 {@code place_images}의 키가 스테이징에서 최종으로 바뀌므로, 그 자리에서
     * {@code thumbnail_file_key}를 다시 짓지 않으면 표시값 패치가 죽은 키를 읽는다.
     *
     * <p>썸네일을 상관 서브쿼리로 고르는 근거는 {@link #rebuildRowsFromSource} javadoc에 있다 —
     * 여기는 PK IN 소수 행이라 장소마다 한 번 도는 비용이 문제가 되지 않는다.
     *
     * @param placeIds 비어 있으면 호출하지 말 것 — {@code IN ()}은 문법 오류다
     * @return 영향 행 수 (MySQL은 INSERT를 1, UPDATE를 2로 센다)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO place_stats (
            place_id, town_id, created_at, tag_bitmask,
            name, latitude, longitude, main_tag_id, thumbnail_file_key,
            bookmark_count, review_count, avg_rating)
        SELECT p.id,
               p.town_id,
               p.created_at,
               COALESCE(t.mask, 0),
               p.name,
               p.latitude,
               p.longitude,
               mpt.tag_id,
               (SELECT pi.image_file_key
                  FROM place_images pi
                 WHERE pi.place_id = p.id
                 ORDER BY pi.display_order, pi.image_file_key
                 LIMIT 1),
               0,
               0,
               0
        FROM places p
        LEFT JOIN (
            SELECT pt.place_id AS place_id,
                   BIT_OR(1 << pt.tag_id) AS mask
            FROM place_tag pt
            WHERE pt.place_id IN (:placeIds)
            GROUP BY pt.place_id
        ) t ON t.place_id = p.id
        LEFT JOIN (
            SELECT pt.place_id AS place_id,
                   MIN(pt.id) AS pt_id
            FROM place_tag pt
            JOIN tags tg ON tg.id = pt.tag_id
            WHERE tg.type = 'MAIN'
              AND pt.place_id IN (:placeIds)
            GROUP BY pt.place_id
        ) mm ON mm.place_id = p.id
        LEFT JOIN place_tag mpt ON mpt.id = mm.pt_id
        WHERE p.id IN (:placeIds)
          AND p.active = 1
        ON DUPLICATE KEY UPDATE
            town_id            = VALUES(town_id),
            created_at         = VALUES(created_at),
            tag_bitmask        = VALUES(tag_bitmask),
            name               = VALUES(name),
            latitude           = VALUES(latitude),
            longitude          = VALUES(longitude),
            main_tag_id        = VALUES(main_tag_id),
            thumbnail_file_key = VALUES(thumbnail_file_key)
        """, nativeQuery = true)
    int upsertRowsForActivePlaces(@Param("placeIds") List<Long> placeIds);

    /**
     * 지정한 장소들의 행을 지운다 = <b>목록에서 장소를 빼는 유일한 경로</b>.
     *
     * <p>두 정렬 모두 place_stats가 기준 테이블이라 여기 행이 남아 있는 동안 노출된다. 예전에는
     * 카운트 배치의 잔행 삭제가 뒤를 받쳤지만, 배치가 행의 존재에서 손을 뗀 지금은 이 문장이
     * 전부다 — <b>어드민 쓰기 경로가 이것을 부르지 않으면 내린 장소가 영구히 노출된다.</b>
     * {@code places.active}를 내리는 경로를 새로 만든다면 반드시 여기를 함께 부를 것.
     *
     * <p><b>되살리는 쪽도 행은 즉시 만든다 (V34).</b> 최신순의 기준 테이블이 place_stats가 되면서
     * 행이 없는 재활성 장소는 <em>최신순에서도</em> 사라지는데, 그것은 대가가 아니라 버그다 —
     * {@code AdminPlaceService#activatePlacesByTownIds}가 그 자리에서 행을 만든다.
     * 그 행은 아직 채점 전이지만 <b>인기순에도 즉시</b> 나온다 — 점수 0이 곧 그 장소의 자리이고,
     * 근거는 {@code PlaceListDbQueryRepository#findPopularRows} javadoc에 있다.
     *
     * <p><b>여기서 지운 행을 정기 회차가 되살리지 않는다.</b> {@link #updateCounts}는 이미 있는
     * 행만 갱신하기 때문이다. 되살리는 것은 어드민의 재활성 경로
     * ({@link #upsertRowsForActivePlaces})이거나 기동·복구의 {@link #rebuildRowsFromSource}이고,
     * 둘 다 {@code p.active = 1}을 원본에서 다시 판단한다. 이미 없는 행을 지우면 0을 돌려줄 뿐이다.
     *
     * <p><b>{@code clearAutomatically}를 켜지 않는다.</b> 호출부는 같은 트랜잭션에서 방금 로드한
     * {@code Place}를 이어서 지우는데, 컨텍스트를 비우면 그 엔티티가 detach돼 삭제가 merge(불필요한
     * SELECT)를 거친다. 어드민 경로가 {@code PlaceStats} 엔티티를 읽는 일이 없어 1차 캐시가 낡을
     * 자리도 없다.
     *
     * @param placeIds 비어 있으면 호출하지 말 것 — {@code IN ()}은 문법 오류다
     * @return 지운 행 수. 배치가 아직 행을 만들지 않은 장소면 0이고, 그것이 정상이다
     */
    @Modifying
    @Query(value = """
        DELETE FROM place_stats
         WHERE place_id IN (:placeIds)
        """, nativeQuery = true)
    int deleteByPlaceIds(@Param("placeIds") List<Long> placeIds);

    /**
     * 인기 점수를 원본에서 재계산해 <b>이미 존재하는 행에만</b> 덮어쓴다.
     *
     * <pre>
     * score = W_B × ln(1 + Σ북마크[0.5^(경과일 / 반감기)])
     *       + W_R × (조정평점 − C)
     * 조정평점 = (Σ평점 + m × C) / (리뷰 수 + m),   C = 전체 평균 평점
     * </pre>
     *
     * <p><b>INSERT가 아니라 UPDATE인 것이 소유권 계약이다.</b> 행을 만드는 주체는 어드민 쓰기
     * 트랜잭션이고, 이 문장은 그 행의 점수 두 칸만 정한다. INSERT로 바꾸면 {@code town_id}·
     * {@code tag_bitmask}에 값을 지어내야 하고, 그 순간 어드민이 소유한 칸을 배치가 침범한다.
     * 어드민이 방금 만든 행은 여기서 0이 아닌 실제 점수를 받아 인기순의 제자리로 옮겨간다.
     *
     * <p><b>감쇠가 북마크에만 걸리는 것은 의도적 비대칭이다</b> — 인기(북마크)는 최근 활동이라
     * 감쇠하고, 평판(리뷰)은 시점 무관한 누적 판단이라 1건이 1표씩 들어간다.
     *
     * <p><b>북마크 축에 로그를 씌우는 이유는 두 축의 치역이 다르기 때문이다.</b> 북마크 합은 건수에
     * 비례해 수만까지 가는데 리뷰 항은 평점 범위에 갇혀 있어, 그냥 더하면 리뷰가 순위에 아무
     * 영향을 못 준다. 로그가 자릿수를 맞춘다. {@code W_B}가 로그 <em>바깥</em>인 것도 계약이다
     * ({@code PlaceStatsProperties#bookmarkWeight} 참조).
     *
     * <p><b>리뷰 축이 평균의 편차인 것은 왜곡 방지다.</b> 합 형태였을 때는 리뷰 수가 많을수록
     * 값이 무한정 커져 평점의 <em>높낮이</em>가 아니라 <em>개수</em>가 순위를 정했다. 베이지안
     * 평균은 표본이 적은 장소를 전체 평균 쪽으로 당겨, 리뷰 1건짜리 5점이 100건짜리 4.5점을
     * 이기지 못하게 한다. {@code C}를 빼서 중심화하므로 <b>리뷰가 없는 장소의 기여는 정확히 0</b>이고,
     * 전체 평균이 중앙(3.0)보다 위인 한 저평점의 페널티가 고평점의 보상보다 크다.
     *
     * <p><b>{@code created_at <= :calculatedAt} 상한을 지우지 말 것.</b> 잃는 것은 성능이 아니라
     * 멱등성이라는 문장의 참/거짓이다. 게다가 상한이 없으면 미래 시각 활동에서
     * {@code POW(0.5, 음수) > 1}이 되어 북마크 축이 감쇠가 아니라 증폭이 된다.
     *
     * <p><b>표시용 {@code review_count}·{@code avg_rating}을 재활용하지 않고 리뷰를 다시 훑는
     * 이유.</b> 두 값의 신선도가 이 배치와 다르다(카운트는 ≤1h, 점수는 ≤24h). 재활용하면 점수가
     * "한 시간 전 카운트 + 지금 계산한 감쇠"라는 섞인 시점 위에 선다. 게다가
     * {@code cnt × avg_rating}으로 조정 평점의 분자를 대신하면 {@code avg_rating}이
     * {@code DECIMAL(3,2)}로 이미 반올림된 값이라 리뷰 수를 곱한 만큼 오차가 커진다.
     * 그래서 여기서도 {@code SUM(rating)}을 따로 뽑는다.
     * (V37 전에는 이 자리에 더 급한 이유가 하나 더 있었다 — 리뷰 0건 장소의 {@code avg_rating}이
     * NULL이라 {@code 0 × NULL = NULL}이 {@code NOT NULL} 점수 컬럼에 걸려 배치가 통째로 터졌다.
     * 평점이 0으로 채워지면서 그 경로는 사라졌고, 위의 두 이유는 그대로다.)
     *
     * <p><b>⚠️ NULL이 조용히 번지는 자리가 둘 더 있다. 아래 두 장치를 지우지 말 것.</b>
     * <ol>
     *   <li><b>{@code COALESCE(AVG(pr.rating), 3.0)}.</b> 리뷰 테이블이 통째로 비면 {@code C}가
     *       NULL이라 <em>전</em> 장소의 점수가 NULL이 된다. 서비스 초기에 실재하는 상태다.
     *       폴백 값이 무엇이든 결과는 같다 — 리뷰가 하나도 없으면 모든 장소가 조정평점 = C가 되어
     *       {@code C}가 상쇄된다. 그래서 프로퍼티로 빼지 않고 리터럴로 둔다.</li>
     *   <li><b>{@code + 0e0}이 {@code C}를 DOUBLE로 승격시킨다.</b> {@code AVG}는 DECIMAL을
     *       돌려주는데, 그대로 두면 조정 평점의 나눗셈이 DECIMAL 산술로 계산돼 스케일이
     *       {@code div_precision_increment}(기본 4)에 잘린다. 감쇠항은 DOUBLE이라 두 축의
     *       정밀도가 어긋나고, 그 차이는 동점 근처의 순위로만 드러나 추적이 어렵다.</li>
     * </ol>
     *
     * <p>{@code LN}의 정의역은 안전하다 — 감쇠 합이 0 이상이라 {@code LN(1 + Σ) ≥ LN(1) = 0}이고
     * 로그가 발산할 입구가 없다. 다만 그것은 {@code 1 +}가 있을 때만 참이다.
     *
     * <p><b>{@code C}는 전 장소가 공유하는 값이라 한 장소의 리뷰가 다른 장소의 점수를 움직인다.</b>
     * 이 결합은 공식의 성질이지 버그가 아니지만, 테스트 픽스처를 짤 때 반드시 의식해야 한다 —
     * 리뷰를 한 장소에만 넣으면 {@code C}가 그 장소의 평균과 같아져 기여가 항상 0이 된다.
     *
     * <p><b>⚠️ 이 문장도 {@code READ_COMMITTED}에서 호출할 것.</b> 소스 테이블 스캔의 락 성질은
     * {@link #updateCounts}와 같다. 다만 여기서는 갱신 대상이 {@code place_stats} 전 행이라
     * 그 X 락이 커밋까지 남는다 — 그래서 두 배치가 겹쳐 돌지 않게 시각을 갈라 뒀다
     * ({@code PlaceStatsFacade} 참조).
     *
     * @param calculatedAt   감쇠 기준 시각이자 <b>집계 대상의 상한</b>
     * @param bookmarkWeight 로그 압축된 북마크 축의 배율. 로그 <b>바깥</b>에 곱해진다
     * @param reviewWeight   리뷰 축의 배율. 실제 기여는 (조정평점 − C)가 곱해진 유계 값이다
     * @param halfLifeDays   북마크 감쇠 반감기(일). <b>0이면 북마크 항이 조용히 통째로 0이 되고
     *                       음수면 증폭이 된다</b> — {@code PlaceStatsProperties}의 {@code @Positive}가 막는다
     * @param minReviewCount 베이지안 사전 표본 수 {@code m}. <b>0이면 베이지안 보정이 통째로 사라지고
     *                       리뷰 0건 장소가 {@code 0/0}으로 NULL이 된다</b> — 같은 {@code @Positive}가 막는다
     * @return 갱신된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE place_stats ps
        CROSS JOIN (
            SELECT COALESCE(AVG(pr.rating), 3.0) + 0e0 AS c
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
        ) g
        LEFT JOIN (
            SELECT bm.target_id AS place_id,
                   SUM(POW(0.5,
                       TIMESTAMPDIFF(SECOND, bm.created_at, :calculatedAt)
                           / 86400.0 / :halfLifeDays)) AS decayed
            FROM bookmarks bm
            WHERE bm.target_type = 'PLACE'
              AND bm.created_at <= :calculatedAt
            GROUP BY bm.target_id
        ) b ON b.place_id = ps.place_id
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   SUM(pr.rating) AS sum_rating
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
            GROUP BY pr.place_id
        ) r ON r.place_id = ps.place_id
        SET ps.popular_score = :bookmarkWeight * LN(1 + COALESCE(b.decayed, 0))
                                   + :reviewWeight * (
                                       (COALESCE(r.sum_rating, 0) + :minReviewCount * g.c)
                                           / (COALESCE(r.cnt, 0) + :minReviewCount) - g.c),
            ps.score_calculated_at = :calculatedAt
        """, nativeQuery = true)
    int updateScores(
            @Param("calculatedAt") LocalDateTime calculatedAt,
            @Param("bookmarkWeight") double bookmarkWeight,
            @Param("reviewWeight") double reviewWeight,
            @Param("halfLifeDays") double halfLifeDays,
            @Param("minReviewCount") int minReviewCount);

    /**
     * 점수를 한 번이라도 받은 행이 있는가. 기동 시 점수 백필 여부를 정하는 유일한 판정이다.
     *
     * <p>{@code count() > 0}으로는 이 질문에 답할 수 없다 — 카운트 배치가 만든 0점·미채점 행도
     * 행이기 때문이다. V32 직후처럼 테이블을 재생성한 배포에서 그 구분이 없으면 다음 새벽까지
     * 전 장소가 0점으로 서빙된다.
     */
    boolean existsByScoreCalculatedAtNotNull();

    /**
     * 북마크 검색 경로의 점수·카운트 조회. PK IN 조회라 후보 수(시 단위 병합 최대 ~1,800)에
     * 선형이고 북마크 수와는 무관하다.
     *
     * <p>JPQL이 아닌 것은 아래 원시 행 조회와 짝을 맞추기 위해서다 — 매핑도 여기서 직접 한다.
     */
    default List<PlaceStatsView> findViewsByPlaceIds(List<Long> placeIds) {
        return findViewRows(placeIds).stream()
                .map(row -> new PlaceStatsView(
                        ((Number) row[0]).longValue(),
                        (BigDecimal) row[1],
                        ((Number) row[2]).intValue(),
                        ((Number) row[3]).intValue(),
                        (BigDecimal) row[4]))
                .toList();
    }

    /** {@link #findViewsByPlaceIds}의 원시 행. 직접 부르지 말 것 — 매핑은 그쪽이 맡는다. */
    @Query(value = """
        SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
               ps.review_count, ps.avg_rating
        FROM place_stats ps
        WHERE ps.place_id IN (:placeIds)
        """, nativeQuery = true)
    List<Object[]> findViewRows(@Param("placeIds") List<Long> placeIds);
}
