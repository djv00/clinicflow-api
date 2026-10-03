package com.jiangyudai.clinicflow.common.query;

/** Literal substring searches for queries that declare {@code escape '!'}. */
public final class SearchPatterns {
    private SearchPatterns() {
    }

    public static String containing(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
