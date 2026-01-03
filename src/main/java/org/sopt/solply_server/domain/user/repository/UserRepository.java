package org.sopt.solply_server.domain.user.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.Optional;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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

    @Query(value = "SELECT * FROM users WHERE id = :id LIMIT 1", nativeQuery = true)
    Optional<User> findAnyById(@Param("id") Long id);


    // 소셜/재가입용(삭제 포함) - native로 @Where 우회
    @Query(value = "SELECT * FROM users WHERE email = :email LIMIT 1", nativeQuery = true)
    Optional<User> findAnyByEmail(@Param("email") String email);

}
