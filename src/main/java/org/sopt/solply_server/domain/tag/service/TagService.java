package org.sopt.solply_server.domain.tag.service;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.tag.dto.TagDto;
import org.sopt.solply_server.domain.tag.dto.response.TagListGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
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

    public TagListGetResponse findTags(TagUsage tagUsage, Long parentId) {
        List<Tag> tags;

        if (parentId == null) {
            tags = tagRepository.findByTypeAndTagUsageAndActiveTrue(TagType.MAIN, tagUsage);
        } else {
            tags = tagRepository.findByParentIdAndTagUsageAndActiveTrueOrderById(parentId, tagUsage);
        }

        return TagListGetResponse.from(tags.stream().map(TagDto::from).toList());
    }

    public List<Tag> findTags(Collection<Long> tagIds) {
        return tagRepository.findAllById(tagIds);
    }
}