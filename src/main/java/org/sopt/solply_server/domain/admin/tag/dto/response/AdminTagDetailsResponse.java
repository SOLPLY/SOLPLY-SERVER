package org.sopt.solply_server.domain.admin.tag.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagPersonaMapping;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record AdminTagDetailsResponse(
        Long id,
        TagType type,
        String name,
        Long parentId,
        String parentName,
        boolean active,
        List<PersonaMappingDto> personas,
        String tagUsage
) {
    public static AdminTagDetailsResponse from(Tag t) {
        List<PersonaMappingDto> personas = t.getPersonaMappings().stream()
                .map(PersonaMappingDto::from)
                .toList();

        return new AdminTagDetailsResponse(
                t.getId(),
                t.getType(),
                t.getName(),
                t.getParent() == null ? null : t.getParent().getId(),
                t.getParent() == null ? null : t.getParent().getName(),
                t.isActive(),
                personas,
                t.getTagUsage().name()
        );
    }

    public record PersonaMappingDto(UserPersona persona, int weight) {
        public static PersonaMappingDto from(TagPersonaMapping m) {
            return new PersonaMappingDto(m.getPersona(), m.getWeight());
        }
    }
}