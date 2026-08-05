package com.melody.melodylink.observer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MethodCallObserverTest {
    @Test public void describesValuesWithoutExposingRawStrings() {
        assertEquals("String(hash=" + Integer.toHexString("WF-1000XM3".hashCode()) + ")",
                MethodCallObserver.describe("WF-1000XM3"));
        assertEquals("collection[2]", MethodCallObserver.compact(java.util.Arrays.asList("a", "b")));
    }
}
