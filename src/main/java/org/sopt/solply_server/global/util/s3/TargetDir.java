package org.sopt.solply_server.global.util.s3;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TargetDir {
    PLACE("places"),
    PLACE_REQUEST("place-requests"),
    PLACE_REPORT("place-reports"),
    USER_PROFILE("user-profiles"),
    RECORD("records");

    private final String dir;

}
