package org.sopt.solply_server.domain.place.service.facade;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.domain.tag.service.TagService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceTagFacade {


    private final TagService tagService;


    public List<Tag> getAllTags(Set<Long> tagIds) {
        return tagService.findTags(tagIds);
    }
}