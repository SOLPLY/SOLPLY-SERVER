package org.sopt.solply_server.domain.admin.tag.facade;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagActivationRequest;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagActivationResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagDetailsResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagListResponse;
import org.sopt.solply_server.domain.admin.tag.service.AdminTagService;
import org.sopt.solply_server.domain.place.service.PlaceSearchDocumentService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminTagFacade {

    private final AdminTagService adminTagService;
    private final PlaceSearchDocumentService placeSearchDocumentService;

    public Long createTag(AdminTagUpsertRequest request) {
        return adminTagService.createTag(request);
    }

    public AdminTagListResponse getTags() {
        return adminTagService.getTags();
    }

    public AdminTagDetailsResponse getTagDetails(Long id) {
        return adminTagService.getTagDetails(id);
    }

    public Long updateTag(Long id, AdminTagUpsertRequest request) {
        Long result = adminTagService.updateTag(id, request);
        placeSearchDocumentService.markDirtyByTagId(id);
        return result;
    }

    public AdminTagActivationResponse toggleActive(Long id, AdminTagActivationRequest request) {
        AdminTagActivationResponse response = adminTagService.toggleActive(id, request);
        placeSearchDocumentService.markDirtyByTagId(id);
        return response;
    }
}
