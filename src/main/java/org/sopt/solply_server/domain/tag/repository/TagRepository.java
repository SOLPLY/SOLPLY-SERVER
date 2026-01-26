package org.sopt.solply_server.domain.tag.repository;
import java.util.Optional;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByType(TagType type);
    List<Tag> findByParentIdOrderById(Long parentId);
    boolean existsByIdAndType(Long id, TagType type);

    @Query("""
      select t from Tag t
      left join fetch t.parent
      order by t.id asc
    """)
    List<Tag> findAllWithParent();

    @Query("""
        select distinct t from Tag t
        left join fetch t.parent
        left join fetch t.personaMappings pm
        where t.id = :id
    """)
    Optional<Tag> findByIdWithDetails(@Param("id") Long id);

    @Query("""
        select t from Tag t
        where t.parent.id = :parentId
    """)
    List<Tag> findChildren(@Param("parentId") Long parentId);

    List<Tag> findByParentId(Long parentId);
}
