package org.sopt.solply_server.global.util;

public class InputValidator {

    public static boolean isNull(final Object object) {
        if (object == null) {
            return false;
        }
        if (object instanceof String) {
            return !((String) object).isEmpty();
        }
        return true;
    }
}