package org.sopt.solply_server.domain.admin.place.facade;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceDetailsGetResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceListResponse;
import org.sopt.solply_server.domain.admin.place.dto.response.AdminPlaceUpsertResponse;
import org.sopt.solply_server.domain.admin.place.service.AdminPlaceService;
import org.sopt.solply_server.domain.place.service.PlaceSearchDocumentService;
import org.sopt.solply_server.domain.place.service.facade.PlaceEmbeddingFacade;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminPlaceFacade {

    private final AdminPlaceService adminPlaceService;
    private final PlaceSearchDocumentService placeSearchDocumentService;
    private final PlaceEmbeddingFacade placeEmbeddingFacade;

    public AdminPlaceUpsertResponse createPlace(Long adminUserId, AdminPlaceUpsertRequest request) {
        return adminPlaceService.createPlace(adminUserId, request);
    }

    public AdminPlaceUpsertResponse updatePlace(Long placeId, AdminPlaceUpsertRequest request) {
        AdminPlaceUpsertResponse response = adminPlaceService.updatePlace(placeId, request);
        placeSearchDocumentService.markDirtyByPlaceId(placeId);
        return response;
    }

    public AdminPlaceDetailsGetResponse getPlaceDetails(Long placeId) {
        return adminPlaceService.getPlaceDetails(placeId);
    }

    public AdminPlaceListResponse searchPlaces(String keyword) {
        return adminPlaceService.searchPlaces(keyword);
    }

    public AdminPlaceListResponse getPlacesByTown(Long townId) {
        return adminPlaceService.getPlacesByTown(townId);
    }

    public void deletePlace(Long placeId) {
        adminPlaceService.deletePlace(placeId);
    }

    public void initializePendingEmbeddings() {
        placeEmbeddingFacade.initializePendingDocuments();
    }
}
