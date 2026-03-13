package org.sopt.solply_server.domain.place.util;

import java.util.List;
import java.util.stream.Collectors;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceReviewSummary;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.springframework.stereotype.Component;

@Component
public class RetrievalTextBuilder {

    /**
     * retrieval_text 형식:
     * {장소명}은 {동네}에 위치한 {카테고리}다. {소개글}. {체크포인트들}. {태그 기반 문장들} 리뷰에서는 {리뷰 요약}이라는 평가가 자주 보인다.
     */
    public String build(Place place, PlaceReviewSummary reviewSummary) {
        StringBuilder sb = new StringBuilder();

        String mainTagName = place.getMainTag()
                .filter(Tag::isActive)
                .map(Tag::getName)
                .orElse("장소");

        // 1. 기본 문장
        sb.append(place.getName())
                .append("은 ")
                .append(place.getTown().getName())
                .append("에 위치한 ")
                .append(mainTagName)
                .append("다. ");

        // 2. 소개글
        sb.append(place.getIntroduction()).append(". ");

        // 3. 체크포인트
        List<String> checkpoints = place.getCheckpoints();
        if (checkpoints != null && !checkpoints.isEmpty()) {
            sb.append(String.join(". ", checkpoints)).append(". ");
        }

        // 4. 태그 기반 문장 (sentence가 있는 active 태그, main 태그 포함)
        String tagSentences = place.getTags().stream()
                .filter(Tag::isActive)
                .filter(t -> t.getSentence() != null && !t.getSentence().isBlank())
                .map(Tag::getSentence)
                .collect(Collectors.joining(" "));

        if (!tagSentences.isBlank()) {
            sb.append(tagSentences).append(" ");
        }

        // 5. 리뷰 요약
        if (reviewSummary != null
                && reviewSummary.getSummaryContent() != null
                && !reviewSummary.getSummaryContent().isBlank()) {
            sb.append("리뷰에서는 ")
                    .append(reviewSummary.getSummaryContent())
                    .append("이라는 평가가 자주 보인다.");
        }

        return sb.toString().trim();
    }
}
