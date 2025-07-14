package org.sopt.solply_server.domain.recommend.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.springframework.stereotype.Component;

@Component
public class PersonaTagMappingStrategy {

    private final Map<UserPersona, List<TagName>> personaTagMap;

    public PersonaTagMappingStrategy() {
        this.personaTagMap = initializePersonaTagMapping();
    }

    private Map<UserPersona, List<TagName>> initializePersonaTagMapping() {
        Map<UserPersona, List<TagName>> mapping = new EnumMap<>(UserPersona.class);

        // ANYTHING: 특별히 선호하는 공간은 없어요
        mapping.put(UserPersona.ANYTHING, Arrays.asList(
                TagName.CAFE, TagName.BOOKSTORE, TagName.SHOPPING,
                TagName.WALKING, TagName.UNIQUE_SPACE, TagName.HEALING,
                TagName.SUNLIGHT, TagName.MANY_PLUG, TagName.NO_TIME_LIMIT,
                TagName.SHOPPING, TagName.WALKING, TagName.UNIQUE_SPACE,
                TagName.LIFESTYLE_SHOP, TagName.VINTAGE_SHOP, TagName.POPUP_MARKET,
                TagName.ART, TagName.WORKSHOP,
                TagName.CAFE, TagName.UNIQUE_SPACE, TagName.BAR,
                TagName.SIGNATURE_MENU, TagName.MOOD_INTERIOR,
                TagName.ART, TagName.WORKSHOP, TagName.VINTAGE_SHOP,
                TagName.WALKING, TagName.CAFE, TagName.UNIQUE_SPACE,
                TagName.HEALING, TagName.SUNLIGHT, TagName.BAR_TABLE
        ));

        // REST: 조용한 공간에 오래 머물고 싶어요
        mapping.put(UserPersona.REST, Arrays.asList(
                TagName.CAFE, TagName.BOOKSTORE,
                TagName.READING, TagName.HEALING, TagName.WORK,
                TagName.SUNLIGHT, TagName.MANY_PLUG, TagName.NO_TIME_LIMIT
        ));

        // EXPLORER: 이곳저곳 가볍게 둘러보고 싶어요
        mapping.put(UserPersona.EXPLORER, Arrays.asList(
                TagName.SHOPPING, TagName.WALKING, TagName.UNIQUE_SPACE,
                TagName.LIFESTYLE_SHOP, TagName.VINTAGE_SHOP, TagName.POPUP_MARKET,
                TagName.ART, TagName.WORKSHOP
        ));

        // MOODING: 내 취향에 맞는 공간을 찾고싶어요
        mapping.put(UserPersona.MOODING, Arrays.asList(
                TagName.CAFE, TagName.UNIQUE_SPACE, TagName.BAR,
                TagName.SIGNATURE_MENU, TagName.MOOD_INTERIOR,
                TagName.ART, TagName.WORKSHOP, TagName.VINTAGE_SHOP
        ));

        // NATURAL: 풍경을 감상하며 쉬고 싶어요
        mapping.put(UserPersona.NATURAL, Arrays.asList(
                TagName.WALKING, TagName.CAFE, TagName.UNIQUE_SPACE,
                TagName.HEALING, TagName.SUNLIGHT, TagName.BAR_TABLE
        ));

        return mapping;
    }

    public List<TagName> getTagsByPersona(UserPersona persona) {
        return personaTagMap.getOrDefault(persona, Collections.emptyList());
    }
}