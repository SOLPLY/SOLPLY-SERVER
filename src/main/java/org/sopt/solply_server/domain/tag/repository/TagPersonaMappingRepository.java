package org.sopt.solply_server.domain.tag.repository;

import java.util.List;
import org.sopt.solply_server.domain.tag.entity.TagPersonaMapping;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagPersonaMappingRepository extends JpaRepository<TagPersonaMapping, Long> {

    @Query("""
        select tpm
        from TagPersonaMapping tpm
        join fetch tpm.tag t
        where tpm.persona = :persona
        order by tpm.weight desc, t.id asc
    """)
    List<TagPersonaMapping> findAllByPersonaOrderByWeightDesc(UserPersona persona);
}