package org.sopt.solply_server.global.util;

import java.util.Collection;

public class InputValidator {

    public static boolean isBlank(final Object object) {
        if (object == null) {
            return true;
        }
        if (object instanceof String) {
            return ((String) object).isEmpty();
        }
        if (object instanceof Collection) {
            return ((Collection<?>) object).isEmpty();
        }
        return false;
    }

    public static boolean isNull(final Object object) {
        return object == null;
    }
}