package org.sopt.solply_server.global.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.sopt.solply_server.domain.tag.entity.Tag;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TagViewUtils {
    public static String getActiveNameOrNull(Tag tag) {
        return (tag != null && tag.isActive()) ? tag.getName() : null;
    }
}