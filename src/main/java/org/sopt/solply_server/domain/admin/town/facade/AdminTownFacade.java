package org.sopt.solply_server.domain.admin.town.facade;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.town.dto.request.AdminTownActivationRequest;
import org.sopt.solply_server.domain.admin.town.dto.response.AdminTownUpsertResponse;
import org.sopt.solply_server.domain.admin.town.service.AdminTownService;
import org.sopt.solply_server.domain.admin.town.util.AdminTownValidator;
import org.sopt.solply_server.domain.place.service.PlaceSearchDocumentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AdminTownFacade {

    private final AdminTownService adminTownService;
    private final AdminTownValidator adminTownValidator;
    private final PlaceSearchDocumentService placeSearchDocumentService;

    @Transactional
    public AdminTownUpsertResponse updateTownStatus(Long townId, AdminTownActivationRequest request) {
        adminTownValidator.validateTownId(townId);

        if (request.active()) {
            List<Long> activatedTownIds = adminTownService.activateTown(townId);
            placeSearchDocumentService.resetObsoleteByTownIds(activatedTownIds);
        } else {
            adminTownService.deactivateTown(townId);
        }

        return AdminTownUpsertResponse.of(townId);
    }
}
