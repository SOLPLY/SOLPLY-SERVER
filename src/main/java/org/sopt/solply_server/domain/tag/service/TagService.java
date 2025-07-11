package org.sopt.solply_server.domain.tag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.tag.dto.TagDto;
import org.sopt.solply_server.domain.tag.dto.TagResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;

    public TagResponse findTags(Long parentId) {
        List<Tag> tags;
        if (parentId == null) {
            tags = tagRepository.findByType(TagType.MAIN);
        } else {
            Tag parent = tagRepository.findById(parentId)
                    .orElseThrow(() -> {
                        log.warn("존재하지 않는 태그 ID: parentId={}", parentId);
                        return new BusinessException(ErrorCode.NOT_FOUND_TAG);
                    });
            tags = tagRepository.findByParentId(parent.getId());
        }

        List<TagDto> tagDtos = tags.stream()
                .map(TagDto::of)
                .toList();

        return TagResponse.from(tagDtos);
    }
}