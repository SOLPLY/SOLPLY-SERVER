package org.sopt.solply_server.domain.admin.user.repository;

import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<User, Long> {

}