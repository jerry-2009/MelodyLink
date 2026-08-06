package com.melody.melodylink.hook;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Identifies Melody detail-page sections that are not applicable to Sony profiles. */
final class DetailSectionFilter {
    private static final Set<String> SUPPRESSED_TITLES = new HashSet<>(Arrays.asList(
            "\u8bbe\u5907\u7ba1\u7406",
            "\u5176\u4ed6\u529f\u80fd",
            "\u8033\u673a\u8bbe\u7f6e",
            "\u5173\u4e8e\u8033\u673a"
    ));

    private DetailSectionFilter() {
    }

    static boolean shouldSuppressCategory(String preferenceClassName, CharSequence title) {
        return isPreferenceCategory(preferenceClassName)
                && shouldSuppressTitle(title);
    }

    static boolean shouldSuppressTitle(CharSequence title) {
        return title != null && SUPPRESSED_TITLES.contains(title.toString().trim());
    }

    private static boolean isPreferenceCategory(String className) {
        return "androidx.preference.PreferenceCategory".equals(className)
                || "com.coui.appcompat.preference.COUIPreferenceCategory".equals(className)
                || "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory".equals(className);
    }
}
