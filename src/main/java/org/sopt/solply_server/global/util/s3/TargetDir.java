package org.sopt.solply_server.global.util.s3;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TargetDir {
    PLACE_REQUEST("place-requests"),
    PLACE_REPORT("place-reports");

    private final String dir;

}
