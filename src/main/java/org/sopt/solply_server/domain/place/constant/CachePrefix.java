package org.sopt.solply_server.domain.place.constant;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CachePrefix {

    BOOKMARK_KEY_PREFIX("bookmark");  // 개별 북마크 조회용 키

    private final String prefix;

}
