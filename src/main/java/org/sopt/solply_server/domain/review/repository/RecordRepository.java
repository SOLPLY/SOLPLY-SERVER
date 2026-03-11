package org.sopt.solply_server.domain.review.repository;

import org.sopt.solply_server.domain.review.entity.Record;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordRepository extends JpaRepository<Record, Long> {
}