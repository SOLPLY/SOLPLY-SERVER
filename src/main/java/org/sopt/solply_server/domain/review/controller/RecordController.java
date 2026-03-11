package org.sopt.solply_server.domain.review.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.review.dto.request.CreateRecordRequestDto;
import org.sopt.solply_server.domain.review.dto.response.CreateRecordResponseDto;
import org.sopt.solply_server.domain.review.service.RecordService;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/records")
public class RecordController {

  private final RecordService recordService;

  @PostMapping
  public ResponseEntity<CustomApiResponse<CreateRecordResponseDto>> createRecord(
      @RequestAttribute("userId") Long userId,
      @Valid @RequestBody CreateRecordRequestDto request
  ) {
    CreateRecordResponseDto response = recordService.createRecord(userId, request);
    return CustomApiResponse.success("혼놀 기록 작성이 완료되었습니다.", response);
  }
}