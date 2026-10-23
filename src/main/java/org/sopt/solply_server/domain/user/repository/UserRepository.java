package org.sopt.solply_server.domain.user.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.Optional;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByNickname(String nickname);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
       update User u
          set u.nickname = :nickname,
              u.isNewUser = true
        where u.id = :userId
    """)
    void withdraw(Long userId, String nickname);

    /**
     * 인증 경로 전용 단건 조회 — <b>트랜잭션을 열지 않는다.</b>
     *
     * <p><b>왜 따로 두는가.</b> {@code findById}는 {@code SimpleJpaRepository}의 클래스 레벨
     * {@code @Transactional(readOnly = true)}를 물려받는다. 서비스 트랜잭션 바깥에서 부르면
     * (= 인증 필터가 매 요청 하는 일) 이 한 번의 SELECT를 위해 트랜잭션이 새로 열린다.
     * 2026-08-04 실측(performance_schema digest)에서 인기순 목록 요청 1건이 낸 18문장 중 5문장이
     * <b>이 조회를 감싸는 제어 문장</b>이었다:
     * {@code SET autocommit=0} · {@code SET SESSION TRANSACTION READ ONLY} · {@code COMMIT} ·
     * {@code SET autocommit=1} · {@code SET SESSION TRANSACTION READ WRITE}.
     * 요청당 5문장이 아무것도 보호하지 않고 나갔다 — 단건 읽기는 그 자체로 원자적이라
     * 격리 수준을 협상할 대상이 애초에 없다.
     *
     * <p><b>{@code SUPPORTS}인 이유.</b> "트랜잭션이 있으면 참여하고, 없으면 열지 않는다"가
     * 정확히 필요한 의미다. {@code NOT_SUPPORTED}는 바깥 트랜잭션을 <em>중단</em>시켜 이 경로가
     * 트랜잭션 안에서 불릴 미래에 뜻이 달라지고, {@code NEVER}는 그때 예외가 된다.
     *
     * <p><b>{@code findById}의 전역 의미는 건드리지 않는다.</b> 다른 호출자들은 서비스 트랜잭션
     * 안에서 부르고 반환 엔티티를 변경한다 — 거기서는 트랜잭션이 필요하고, 참여만 하면 되므로
     * 비용도 없다. 이 메서드는 "인증 경로"라는 좁은 용처를 이름으로 못 박은 별개의 진입점이다.
     *
     * <p><b>불변 조건: 소프트 삭제 필터가 계속 걸린다.</b> JPQL이라
     * {@code User}의 {@code @Where(is_deleted = false)}가 그대로 적용된다
     * ({@code findAnyById}처럼 네이티브로 내려가면 우회되는데, 여기서는 그러면 안 된다 —
     * 탈퇴 유저의 토큰이 인증을 통과한다). {@code PrincipalDetailsServiceIT}가 값으로 감시한다.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findForAuthentication(Long id);

    @Query(value = "SELECT * FROM users WHERE id = :id LIMIT 1", nativeQuery = true)
    Optional<User> findAnyById(@Param("id") Long id);


    // 소셜/재가입용(삭제 포함) - native로 @Where 우회
    @Query(value = "SELECT * FROM users WHERE email = :email LIMIT 1", nativeQuery = true)
    Optional<User> findAnyByEmail(@Param("email") String email);

    @Query("SELECT COUNT(u) FROM User u WHERE u.isDeleted = false ")
    long countByActiveTrue();
}
