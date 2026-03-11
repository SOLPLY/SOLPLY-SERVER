package org.sopt.solply_server.domain.review.service;

import org.sopt.solply_server.domain.review.dto.request.CreateRecordRequestDto;
import org.sopt.solply_server.domain.review.dto.response.CreateRecordResponseDto;

public interface RecordService {
  CreateRecordResponseDto createRecord(Long userId, CreateRecordRequestDto request);
}