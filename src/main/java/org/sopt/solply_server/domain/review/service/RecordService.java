package org.sopt.solply_server.domain.review.service;

import org.sopt.solply_server.domain.review.dto.request.CreateRecordRequest;
import org.sopt.solply_server.domain.review.dto.response.CreateRecordResponse;

public interface RecordService {
  CreateRecordResponse createRecord(Long userId, CreateRecordRequest request);
}