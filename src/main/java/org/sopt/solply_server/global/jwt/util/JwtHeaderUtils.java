package org.sopt.solply_server.global.jwt.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

import java.util.Base64;

public final class JwtHeaderUtils {

    private static final ObjectMapper OM = new ObjectMapper();

    private JwtHeaderUtils() {}

    public static String extractKid(final String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) throw new BusinessException(ErrorCode.INVALID_TOKEN);

            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode node = OM.readTree(headerJson);

            JsonNode kidNode = node.get("kid");
            if (kidNode == null || kidNode.asText().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            }
            return kidNode.asText();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }
}