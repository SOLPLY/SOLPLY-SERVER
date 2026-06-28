package org.sopt.solply_server.domain.recommend.repository;

import java.util.List;

import org.sopt.solply_server.domain.recommend.entity.RecommendExamplePhrase;
import org.sopt.solply_server.domain.recommend.entity.RecommendTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendExamplePhraseRepository extends JpaRepository<RecommendExamplePhrase, Long> {

    List<RecommendExamplePhrase> findByTargetTypeOrderByDisplayOrderAscIdAsc(
            RecommendTargetType targetType);
}
