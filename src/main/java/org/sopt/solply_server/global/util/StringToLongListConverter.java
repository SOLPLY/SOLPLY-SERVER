package org.sopt.solply_server.global.util;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StringToLongListConverter implements Converter<String, List<Long>> {

    @Override
    public List<Long> convert(String source) {
        if (source == null || source.trim().isEmpty()) {
            return List.of();
        }

        try {
            return Arrays.stream(source.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format in list: " + source, e);
        }
    }
}