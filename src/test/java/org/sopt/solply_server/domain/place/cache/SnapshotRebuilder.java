package org.sopt.solply_server.domain.place.cache;

import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테스트가 <b>"지금 원본에서 다시 지어 설치하라"</b>고 말하는 손잡이.
 *
 * <p>{@code SnapshotInstaller#rebuildAndInstall()} 한 줄이면 되는 일이지만, IT에서는 그 앞에
 * "번호를 올려 둔다"가 함께 필요할 때가 많다 — 설치자의 단조 가드가 <b>revision이 올라야만</b>
 * 새 스냅샷을 받아들이기 때문이다. 테스트가 place_stats를 직접 고쳐 놓고 리빌드를 부르면, 번호가
 * 그대로라 설치가 조용히 거절된다. 그 함정을 IT마다 각자 밟지 않도록 여기 모아 둔다.
 *
 * <p><b>운영 경로와 다른 점을 분명히 해 둔다.</b> 운영에서는 데이터를 고친 그 트랜잭션이 번호를
 * 함께 올린다. 여기서는 테스트가 데이터를 이미 고쳐 둔 뒤라 번호만 따로 올린다 — 원자성 검증은
 * 쓰기 경로를 직접 부르는 IT의 몫이다.
 */
public final class SnapshotRebuilder {

    private final SnapshotInstaller installer;
    private final SnapshotMetadataRepository metadataRepository;
    private final TransactionTemplate bumpTransaction;

    public SnapshotRebuilder(SnapshotInstaller installer,
            SnapshotMetadataRepository metadataRepository,
            PlatformTransactionManager transactionManager) {
        this.installer = installer;
        this.metadataRepository = metadataRepository;
        this.bumpTransaction = new TransactionTemplate(transactionManager);
        this.bumpTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.bumpTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 번호를 올리고(정렬이 갈렸다고 보고) 원본에서 전량을 다시 지어 설치한다.
     *
     * @return 설치된 스냅샷의 번호 둘
     */
    public SnapshotMetadata rebuildAndInstall() {
        return rebuildAndInstall(SnapshotCursorPolicy.ADVANCE);
    }

    /**
     * 커서 정책을 지정해 다시 짓는다. {@link SnapshotCursorPolicy#PRESERVE}는 "표시값만 바뀐
     * 회차" — revision은 오르되 진행 중인 커서는 끊기지 않는 상태를 만든다.
     */
    public SnapshotMetadata rebuildAndInstall(SnapshotCursorPolicy policy) {
        bump(policy);
        installer.rebuildAndInstall();
        return current();
    }

    /** 번호만 올린다 — "DB는 앞섰는데 이 인스턴스는 아직 못 지은" 상태를 만들 때 쓴다. */
    public void bump(SnapshotCursorPolicy policy) {
        bumpTransaction.executeWithoutResult(status -> metadataRepository.bump(policy));
    }

    /** 지금 DB가 말하는 번호 둘. */
    public SnapshotMetadata current() {
        return metadataRepository.read();
    }
}
