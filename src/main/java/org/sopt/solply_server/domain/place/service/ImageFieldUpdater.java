package org.sopt.solply_server.domain.place.service;

import java.util.List;
import org.sopt.solply_server.global.util.s3.TargetDir;

public interface ImageFieldUpdater {
    TargetDir supportedDir();
    void replaceImages(long targetId, List<String> destKeys);
}