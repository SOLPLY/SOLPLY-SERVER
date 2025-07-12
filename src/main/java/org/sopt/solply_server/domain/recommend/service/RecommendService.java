package org.sopt.solply_server.domain.recommend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.response.PlaceRecommendationGetResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendService {

    public PlaceRecommendationGetResponse getRecommendPlaces(Long userId, Long townId) {

    }

}