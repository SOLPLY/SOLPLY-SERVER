package org.sopt.solply_server.domain.tag.repository;

import org.sopt.solply_server.domain.tag.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

}
