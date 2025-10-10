package org.sopt.solply_server.domain.user.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.Optional;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    User findByEmail(String email);

    boolean existsByNickname(String nickname);


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
       update User u
          set u.email = :email,
              u.nickname = :nickname,
              u.isNewUser = true
        where u.id = :userId
    """)
    void withdraw(Long userId, String email, String nickname);

    @Query(value = "SELECT * FROM users WHERE email = :email LIMIT 1", nativeQuery = true)
    Optional<User> findAnyByEmail(@Param("email") String email);


    boolean existsByEmail(String email);

    @Query(value = """
        SELECT u.*
          FROM users u
          JOIN social_user_info s ON s.user_id = u.id
         WHERE s.social_code = :socialCode
         LIMIT 1
    """, nativeQuery = true)
    Optional<User> findAnyUserBySocialCode(@Param("socialCode") String socialCode);

}
