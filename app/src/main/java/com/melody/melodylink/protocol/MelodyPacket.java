package com.melody.melodylink.protocol;

import java.util.Arrays;

/** Immutable representation of the Melody SDK packet framing documented in the analysis notes. */
public final class MelodyPacket {
    private static final int HEADER_SIZE = 5;
    private final int command;
    private final int transferId;
    private final byte[] payload;

    public MelodyPacket(int command, int transferId, byte[] payload) {
        if ((command & ~0xFFFF) != 0) throw new IllegalArgumentException("command must fit uint16");
        if ((transferId & ~0xFF) != 0) throw new IllegalArgumentException("transferId must fit uint8");
        if (payload == null || payload.length > 0xFFFF) throw new IllegalArgumentException("payload length must fit uint16");
        this.command = command;
        this.transferId = transferId;
        this.payload = payload.clone();
    }

    public int command() { return command; }
    public int transferId() { return transferId; }
    public byte[] payload() { return payload.clone(); }
    public boolean isResponse() { return (command & 0x8000) != 0; }
    public int baseCommand() { return command & 0x7FFF; }

    public byte[] encode() {
        byte[] result = new byte[HEADER_SIZE + payload.length];
        result[0] = (byte) command;
        result[1] = (byte) (command >>> 8);
        result[2] = (byte) transferId;
        result[3] = (byte) payload.length;
        result[4] = (byte) (payload.length >>> 8);
        System.arraycopy(payload, 0, result, HEADER_SIZE, payload.length);
        return result;
    }

    public static MelodyPacket decode(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) throw new IllegalArgumentException("packet is shorter than header");
        int command = u16(data, 0);
        int transferId = data[2] & 0xFF;
        int length = u16(data, 3);
        if (length != data.length - HEADER_SIZE) throw new IllegalArgumentException("payload length does not match frame");
        return new MelodyPacket(command, transferId, Arrays.copyOfRange(data, HEADER_SIZE, data.length));
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
