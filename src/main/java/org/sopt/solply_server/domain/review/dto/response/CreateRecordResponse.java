package org.sopt.solply_server.domain.review.dto.response;

import org.sopt.solply_server.domain.review.entity.Record;

public record CreateRecordResponse(
    Long recordId
) {
  public static CreateRecordResponse from(Record record) {
    return new CreateRecordResponse(record.getId());
  }
}