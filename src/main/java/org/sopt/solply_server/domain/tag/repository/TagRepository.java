package org.sopt.solply_server.domain.tag.repository;

import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByType(TagType type);
    List<Tag> findByParentIdOrderById(Long parentId);
    boolean existsByIdAndType(Long id, TagType type);
}
