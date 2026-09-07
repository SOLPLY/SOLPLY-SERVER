package org.sopt.solply_server.domain.place.entity;

public enum EmbeddingStatus {
    INIT,    // 최초 생성됨 (임베딩 전)
    READY,   // 최신 임베딩 완료
    DIRTY,   // 원본 수정으로 인한 업데이트 필요
    FAILED   // API 호출 실패 (재시도 대상)
}
