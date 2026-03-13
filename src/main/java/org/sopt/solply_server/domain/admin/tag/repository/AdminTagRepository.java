package org.sopt.solply_server.domain.admin.tag.repository;

import java.util.List;
import java.util.Optional;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminTagRepository extends JpaRepository<Tag, Long> {
    boolean existsByIdAndType(Long id, TagType type);
    @Query("""
        select t from Tag t
        where t.parent.id = :parentId
    """)
    List<Tag> findChildren(@Param("parentId") Long parentId);

    @Query("""
        select t.id from Tag t
        where t.parent.id = :parentId
    """)
    List<Long> findChildIds(@Param("parentId") Long parentId);

    @Query("""
        select t
        from Tag t
        left join fetch t.parent p
        order by t.id asc
    """)
    List<Tag> findAllWithParent();

    @Query("""
        select distinct t
        from Tag t
        left join fetch t.parent p
        left join fetch t.personaMappings pm
        where t.id = :id
    """)
    Optional<Tag> findByIdWithDetails(@Param("id") Long id);

}