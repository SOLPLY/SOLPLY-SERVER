package org.sopt.solply_server.domain.user.repository;

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
}
