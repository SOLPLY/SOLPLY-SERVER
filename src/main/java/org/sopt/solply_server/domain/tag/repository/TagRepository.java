package org.sopt.solply_server.domain.tag.repository;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByTypeAndActiveTrue(TagType type);
    List<Tag> findByParentIdAndActiveTrueOrderById(Long parentId);

    List<Tag> findAllByIdInAndActiveTrue(Set<Long> allTagIds);

    // validator에서 parent 관계 검증하려면 fetch join 필요
    @Query("""
        select t
        from Tag t
        left join fetch t.parent p
        where t.id in :ids
          and t.active = true
    """)
    List<Tag> findAllByIdInWithParentAndActiveTrue(@Param("ids") Collection<Long> ids);

    @Query("""
        select t
        from Tag t
        left join fetch t.parent p
        where t.id = :id
          and t.active = true
    """)
    Optional<Tag> findByIdWithParentAndActiveTrue(@Param("id") Long id);

    boolean existsByIdAndTypeAndActiveTrue(Long tagId, TagType tagType);

}
