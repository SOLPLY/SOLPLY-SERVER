package org.sopt.solply_server.domain.bookmark.util;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.springframework.stereotype.Component;

@Component
public class BookmarkTargetValidatorRegistry {
    private final Map<BookmarkTargetType, BookmarkTargetValidator> map;

    public BookmarkTargetValidatorRegistry(List<BookmarkTargetValidator> validators) {
        this.map = validators.stream().collect(Collectors.toMap(
                BookmarkTargetValidator::supports, v -> v
        ));
    }

    public BookmarkTargetValidator validator(BookmarkTargetType type) {
        BookmarkTargetValidator v = map.get(type);
        if (v == null) throw new IllegalArgumentException("No validator for " + type);
        return v;
    }
}