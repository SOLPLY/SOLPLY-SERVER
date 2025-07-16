package org.sopt.solply_server.domain.place.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SnsPlatform {
    INSTAGRAM("인스타그램");

    private final String displayName;
}
