package org.sopt.solply_server.domain.place.service;


import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.repository.PlaceReportRepository;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class PlaceReportImageUpdater implements ImageFieldUpdater {

    private final PlaceReportRepository placeReportRepository;
    @Override
    public TargetDir supportedDir() {
        return TargetDir.PLACE_REPORT;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceImages(long reportId, List<String> destKeys) {
        var report = placeReportRepository.findById(reportId).orElseThrow();
        report.getImageKeys().clear();
        for (String key : new LinkedHashSet<>(destKeys)) {
            report.getImageKeys().add(key);
        }
    }

}