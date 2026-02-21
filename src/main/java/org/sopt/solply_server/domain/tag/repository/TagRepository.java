package org.sopt.solply_server.domain.tag.repository;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByTypeAndTagUsageAndActiveTrue(TagType type, TagUsage tagUsage);
    List<Tag> findByParentIdAndTagUsageAndActiveTrueOrderById(Long parentId, TagUsage tagUsage);


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

    Optional<Tag> findByIdAndActiveTrue(Long id);
}
