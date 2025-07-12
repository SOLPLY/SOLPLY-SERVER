package org.sopt.solply_server.global.cache;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CachePrefix {

    BOOKMARK("bookmark");  // 개별 북마크 조회용 키

    private final String prefix;

}
