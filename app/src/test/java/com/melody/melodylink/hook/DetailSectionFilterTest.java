package com.melody.melodylink.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DetailSectionFilterTest {
    private static final String CATEGORY = "com.coui.appcompat.preference.COUIPreferenceCategory";
    private static final String MELODY_CATEGORY =
            "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory";

    @Test
    public void suppressesEachUnsupportedDetailCategory() {
        assertTrue(DetailSectionFilter.shouldSuppressCategory(CATEGORY, "\u8bbe\u5907\u7ba1\u7406"));
        assertTrue(DetailSectionFilter.shouldSuppressCategory(CATEGORY, "\u5176\u4ed6\u529f\u80fd"));
        assertTrue(DetailSectionFilter.shouldSuppressCategory(CATEGORY, "\u8033\u673a\u8bbe\u7f6e"));
        assertTrue(DetailSectionFilter.shouldSuppressCategory(CATEGORY, "\u5173\u4e8e\u8033\u673a"));
        assertTrue(DetailSectionFilter.shouldSuppressCategory(MELODY_CATEGORY, "\u8bbe\u5907\u7ba1\u7406"));
    }

    @Test
    public void keepsOtherCategoriesAndRegularPreferences() {
        assertFalse(DetailSectionFilter.shouldSuppressCategory(CATEGORY, "\u964d\u566a\u63a7\u5236"));
        assertFalse(DetailSectionFilter.shouldSuppressCategory(
                "com.coui.appcompat.preference.COUIPreference", "\u8bbe\u5907\u7ba1\u7406"));
        assertFalse(DetailSectionFilter.shouldSuppressCategory(CATEGORY, null));
    }
}
