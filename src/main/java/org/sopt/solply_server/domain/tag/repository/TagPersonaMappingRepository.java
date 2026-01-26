package org.sopt.solply_server.domain.tag.repository;

import java.util.List;
import org.sopt.solply_server.domain.tag.entity.TagPersonaMapping;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagPersonaMappingRepository extends JpaRepository<TagPersonaMapping, Long> {

    @Query("""
        select m
        from TagPersonaMapping m
        join fetch m.tag t
        where m.persona = :persona
          and t.active = true
        order by m.weight desc
    """)
    List<TagPersonaMapping> findActiveByPersonaOrderByWeightDesc(UserPersona persona);
}