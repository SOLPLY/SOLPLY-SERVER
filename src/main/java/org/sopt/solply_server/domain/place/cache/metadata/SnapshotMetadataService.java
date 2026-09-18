package org.sopt.solply_server.domain.place.cache.metadata;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목록에 영향을 주는 쓰기가 <b>자기 트랜잭션의 마지막에</b> 부르는 자리.
 *
 * <p><b>왜 마지막인가.</b> 이 UPDATE는 모든 쓰기 경로가 마주치는 단일 행을 잠근다. 트랜잭션의
 * 앞에서 부르면 그 뒤에 남은 작업(태그 재삽입·이미지 정리·통계 UPDATE 수천 행)이 도는 내내
 * 다른 쓰기가 그 락을 기다린다. 마지막에 부르면 잡고 있는 구간이 커밋까지의 짧은 꼬리뿐이다.
 *
 * <p><b>왜 flush가 먼저인가.</b> JPA는 커밋 직전에 몰아서 SQL을 낸다. flush 없이 이 UPDATE를
 * 내면 순서가 뒤집혀 "번호를 먼저 올리고 데이터를 나중에 쓰는" 트랜잭션이 된다 — 커밋 원자성
 * 덕분에 결과는 같지만, 락을 잡는 구간이 다시 길어진다. 지연 쓰기를 먼저 내보내 락 구간을
 * 실제로 꼬리에 붙인다.
 */
@Service
@RequiredArgsConstructor
public class SnapshotMetadataService {

    private final SnapshotMetadataRepository metadataRepository;
    private final EntityManager entityManager;

    /**
     * 이 트랜잭션이 목록을 바꿨음을 알린다.
     *
     * <p><b>올린 번호를 돌려주지 않는다.</b> 표시값을 커밋 직후 얹는 경로가 그 번호를 달고 가면
     * <b>번호는 이 트랜잭션의 것, 데이터는 커밋 뒤에 읽은 것</b>인 어긋난 쌍이 된다. 그 경로는
     * 값을 읽는 트랜잭션이 스스로 관측한 번호를 쓴다({@code SnapshotViewPatcher}).
     *
     * @param policy 진행 중인 스크롤을 끊을지. 부르는 자리가 반드시 고른다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markChanged(SnapshotCursorPolicy policy) {
        entityManager.flush();
        metadataRepository.bump(policy);
    }
}
