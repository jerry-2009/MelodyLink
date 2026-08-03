package com.melody.melodylink.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MelodyPacketTest {
    @Test public void roundTripPreservesHeaderAndPayload() {
        MelodyPacket packet = new MelodyPacket(0x0404, 255, new byte[] { 2, 3, (byte) 0xFF });
        MelodyPacket decoded = MelodyPacket.decode(packet.encode());
        assertEquals(0x0404, decoded.command());
        assertEquals(255, decoded.transferId());
        assertArrayEquals(new byte[] { 2, 3, (byte) 0xFF }, decoded.payload());
        assertTrue(!decoded.isResponse());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedFrame() { MelodyPacket.decode(new byte[] { 1, 0, 0, 0 }); }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLengthMismatch() { MelodyPacket.decode(new byte[] { 1, 0, 0, 2, 0, 9 }); }
}
