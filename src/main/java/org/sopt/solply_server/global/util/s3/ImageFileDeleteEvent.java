package org.sopt.solply_server.global.util.s3;

import java.util.List;

public record ImageFileDeleteEvent(
    List<String> imageKeys
) {
}