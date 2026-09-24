package org.sopt.solply_server.domain.place.cache.town;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쓰기 경로가 부르는 자리. <b>어느 동네를 올릴지는 서버가 변경 성격으로 판단한다</b> — 어드민의
 * "목록 재시작" 체크 여부에 기대지 않는다.
 *
 * <p><b>지문 비교는 입구 하나가 쓰는 재료일 뿐이다.</b> 어느 입구를 부를지는 쓰기 경로가 자기
 * 성격으로 정한다. 지금 쓰기 경로가 쓰는 입구는 아래 셋이고, {@link #markTownsChanged}는 장소
 * id가 아니라 동네 자체가 대상인 변경을 위해 열어 둔 자리다.
 *
 * <table>
 *   <caption>입구별 담당 경계</caption>
 *   <tr><th>쓰기</th><th>부르는 입구</th><th>판정</th></tr>
 *   <tr><td>관리자의 대상·태그 변경</td>
 *       <td>{@link #fingerprintOf} + {@link #markChangedIfSearchAffecting}</td>
 *       <td>지문 비교</td></tr>
 *   <tr><td>정렬 키값 변경(북마크 델타)</td><td>{@link #markSortKeysChanged}</td>
 *       <td>지문은 그대로 — 견주지 않고 지금 동네를 올린다</td></tr>
 *   <tr><td>정기 전체 배치 성공</td><td>{@link #markAllTownsChanged}</td>
 *       <td>값 변화와 무관하게 처리 대상 전 동네</td></tr>
 * </table>
 *
 * <p><b>⚠️ 지문이 모든 순서 변경을 덮지는 않는다.</b> 지문은
 * {@code (동네, 태그 비트마스크)}뿐이라 <em>대상과 필터</em>의 변경만 잡는다. 정렬 키값
 * (인기 점수·리뷰 수·평점·북마크 수)이 갈리는 쓰기는 지문이 그대로여서 지문 비교로는 보이지
 * 않으므로 {@link #markSortKeysChanged}를 따로 부른다. 지문 비교를 부르는 쪽에서 정렬 키까지
 * 덮인다고 믿으면 그 동네의 순서가 조용히 낡는다.
 *
 * <p><b>지문 비교의 재료.</b> 쓰기 <em>전</em>과 <em>후</em>에 각 장소의
 * {@code (동네, 태그 비트마스크)}를 읽어 견준다. 대상·필터를 바꾸는 변경은 이 지문을 흔들고,
 * 이름·썸네일·소개·좌표만 바꾼 수정은 지문이 그대로다.
 *
 * <table>
 *   <caption>지문 비교가 덮는 경우</caption>
 *   <tr><th>변경</th><th>쓰기 전</th><th>쓰기 후</th><th>올리는 동네</th></tr>
 *   <tr><td>생성</td><td>없음</td><td>A</td><td>A</td></tr>
 *   <tr><td>삭제·비활성</td><td>A</td><td>없음</td><td>A</td></tr>
 *   <tr><td>동네 이동</td><td>A</td><td>B</td><td>A와 B</td></tr>
 *   <tr><td>태그 재지정</td><td>A/마스크1</td><td>A/마스크2</td><td>A</td></tr>
 *   <tr><td><b>이름·썸네일만</b></td><td>A/마스크1</td><td>A/마스크1</td><td><b>없음</b></td></tr>
 * </table>
 *
 * <p>호출부가 변경 종류를 분류해 넘길 필요가 없다. 분류를 손으로 하면 갈래가 하나 새는 순간 그
 * 동네의 캐시가 조용히 틀린다.
 *
 * <p><b>⚠️ 쓰기 전 지문은 writer를 직렬화한 뒤에 읽어야 한다.</b> A→B와 A→C가 동시에 A를 읽고
 * 순서대로 커밋하면, 뒤에 커밋한 쪽은 실제로 B→C인데 자기가 읽어 둔 A를 출발지로 삼아 B를
 * 빠뜨린다. 그래서 호출부는 대상 행을 잠근 <b>뒤</b> {@link #fingerprintOf}를 부른다
 * ({@code AdminPlaceService}가 {@code PESSIMISTIC_WRITE}로 잠근다).
 */
@Service
@RequiredArgsConstructor
public class TownVersionService {

    private final TownVersionRepository townVersionRepository;
    private final EntityManager entityManager;
    private final TownCommitPublisher commitPublisher;

    /** 번호 변경의 유일한 출구. 커밋 뒤 새 번호를 공유 사본으로 미리 싣도록 건다. */
    private void bump(Set<Long> towns) {
        townVersionRepository.bump(towns);
        commitPublisher.afterCommit(towns);
    }

    /**
     * 쓰기 <b>전</b>에 불러 둔다. 잠금을 잡은 뒤에 부를 것.
     *
     * <p>{@code flush}가 먼저인 이유는 영속성 컨텍스트에만 있는 변경이 아직 {@code place_stats}에
     * 닿지 않았기 때문이다 — 그 상태로 읽으면 이미 바뀐 값을 "쓰기 전"이라고 부르게 된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, TownVersionRepository.PlaceFingerprint> fingerprintOf(
            Collection<Long> placeIds) {
        entityManager.flush();
        return townVersionRepository.fingerprintsOf(placeIds);
    }

    /**
     * 쓰기 <b>뒤</b>에 불러 마무리한다. 지문이 달라진 장소의 <b>전후 동네</b>만 올린다.
     *
     * @param before {@link #fingerprintOf}가 잠금 아래에서 읽어 둔 값
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markChangedIfSearchAffecting(
            Map<Long, TownVersionRepository.PlaceFingerprint> before,
            Collection<Long> placeIds) {

        entityManager.flush();
        Map<Long, TownVersionRepository.PlaceFingerprint> after =
                townVersionRepository.fingerprintsOf(placeIds);

        Set<Long> affected = new LinkedHashSet<>();
        for (Long placeId : placeIds) {
            TownVersionRepository.PlaceFingerprint was = before.get(placeId);
            TownVersionRepository.PlaceFingerprint now = after.get(placeId);
            if (java.util.Objects.equals(was, now)) {
                continue;   // 탐색의 대상·필터·순서가 보는 값이 그대로다 — 표시값만 갈린 수정
            }
            if (was != null) {
                affected.add(was.townId());
            }
            if (now != null) {
                affected.add(now.townId());
            }
        }
        bump(affected);
    }

    /**
     * <b>정렬 키값만 바뀐 변경</b>의 입구. 지문은 그대로지만 순서가 갈리므로 그 장소들이 지금
     * 속한 동네를 올린다.
     *
     * <p>지문 비교로 덮지 못하는 유일한 쓰기다 — 북마크 델타가 여기로 온다. 이 경로에서 장소가
     * 동네를 옮기는 일은 없으므로 쓰기 전후를 견줄 필요도 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markSortKeysChanged(Collection<Long> placeIds) {
        entityManager.flush();
        Set<Long> towns = new LinkedHashSet<>();
        for (TownVersionRepository.PlaceFingerprint fingerprint
                : townVersionRepository.fingerprintsOf(placeIds).values()) {
            towns.add(fingerprint.townId());
        }
        bump(towns);
    }

    /**
     * 동네를 직접 지목해 올린다 — 장소 id가 아니라 동네 자체가 대상인 변경에만 쓴다.
     *
     * <p>지문 규칙이 덮지 못하는 경우가 이것뿐이라 입구를 좁게 남긴다. 표시값만 바뀐 변경에
     * 이것을 부르면 계약이 깨진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markTownsChanged(Collection<Long> townIds) {
        entityManager.flush();
        bump(new LinkedHashSet<>(townIds));
    }

    /**
     * 정기 전체 배치가 성공했을 때 처리 대상 전 동네를 올린다.
     *
     * <p>배치 트랜잭션 안에서 도는 것이 계약이다. 배치가 실패하면 bump도 함께 없던 일이 된다.
     *
     * @return 올린 동네 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int markAllTownsChanged() {
        entityManager.flush();
        int bumped = townVersionRepository.bumpAllTownsWithPlaces();
        commitPublisher.afterCommitAll();
        return bumped;
    }
}
