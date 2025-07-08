package org.sopt.solply_server.domain.user.repository;

import org.sopt.solply_server.domain.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

}
