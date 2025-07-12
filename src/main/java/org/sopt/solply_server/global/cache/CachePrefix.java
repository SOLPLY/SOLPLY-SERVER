package org.sopt.solply_server.global.cache;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CachePrefix {

//    BOOKMARK("bookmark"),  // 개별 북마크 조회용 키
    PLACE_BOOKMARK("place_bookmark"),  // 새로운 Place 북마크 키
    COURSE_BOOKMARK("course_bookmark"); // 새로운 Course 북마크 키

    private final String prefix;

    public String getPrefix() {
        return prefix;
    }
}
