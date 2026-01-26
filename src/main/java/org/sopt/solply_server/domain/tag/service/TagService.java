package org.sopt.solply_server.domain.tag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.tag.dto.TagDto;
import org.sopt.solply_server.domain.tag.dto.response.TagListGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;
    private final TagValidator tagValidator;

    public TagListGetResponse findTags(Long parentId) {
        List<Tag> tags;

        if (parentId == null) {
            tags = tagRepository.findByTypeAndActiveTrue(TagType.MAIN);
        } else {
            tagValidator.validateTagType(parentId, TagType.MAIN);
            tags = tagRepository.findByParentIdAndActiveTrueOrderById(parentId);
        }

        return TagListGetResponse.from(tags.stream().map(TagDto::from).toList());
    }
}