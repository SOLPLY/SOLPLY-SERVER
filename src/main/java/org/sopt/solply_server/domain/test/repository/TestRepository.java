package org.sopt.solply_server.domain.test.repository;

import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRepository extends JpaRepository<User, Long> {
}
