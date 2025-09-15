package org.sopt.solply_server.global.util.s3;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ImageCategory {
    PLACE_REQUESTS("place-requests"),
    PLACE_REPORTS("place-reports");

    private final String dir;

}
