package com.gitblit.markdown;

/**
 * Created by Yuriy Aizenberg
 */
public class ParameterUtils {

    public static Integer extractInteger(String key, Integer defValue) {
        if (Utils.isEmpty(key)) return defValue;
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    public static Integer extractInteger(String key) {
        return extractInteger(key, null);
    }

    public static Boolean extractBoolean(String key, Boolean defValue) {
        if (Utils.isEmpty(key)) return defValue;
        return Boolean.valueOf(key);
    }

    public static Boolean extractBoolean(String key) {
        return extractBoolean(key, null);
    }


}
