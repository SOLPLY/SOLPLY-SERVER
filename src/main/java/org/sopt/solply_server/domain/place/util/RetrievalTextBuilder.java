package org.sopt.solply_server.domain.place.util;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceRetrievalData;
import org.springframework.stereotype.Component;

@Component
public class RetrievalTextBuilder {

    /**
     * retrieval_text 형식:
     * {장소명}은 {동네}에 위치한 {카테고리}다. {소개글}. {체크포인트들}. {태그 기반 문장들} 리뷰에서는 {리뷰 요약}이라는 평가가 자주 보인다.
     */
    public String build(PlaceRetrievalData data) {
        StringBuilder sb = new StringBuilder();

        // 1. 기본 문장
        sb.append(data.placeName())
                .append("은 ")
                .append(data.townName())
                .append("에 위치한 ")
                .append(data.mainTagName())
                .append("다. ");

        // 2. 소개글
        sb.append(data.introduction()).append(". ");

        // 3. 체크포인트
        List<String> checkpoints = data.checkpoints();
        if (checkpoints != null && !checkpoints.isEmpty()) {
            sb.append(String.join(". ", checkpoints)).append(". ");
        }

        // 4. 태그 기반 문장
        String tagMeanings = String.join(" ", data.tagMeanings());
        if (!tagMeanings.isBlank()) {
            sb.append(tagMeanings).append(" ");
        }

        // 5. 리뷰 요약
        if (data.reviewSummary() != null && !data.reviewSummary().isBlank()) {
            sb.append("리뷰에서는 ")
                    .append(data.reviewSummary())
                    .append("이라는 평가가 자주 보인다.");
        }

        return sb.toString().trim();
    }
}
