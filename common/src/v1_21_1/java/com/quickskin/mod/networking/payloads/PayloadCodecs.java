package com.quickskin.mod.networking.payloads;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Helper class for encoding/decoding data to/from ByteBuf for CustomPacketPayloads
 * Provides methods similar to FriendlyByteBuf but for raw ByteBuf
 */
public class PayloadCodecs {

    private static final int MAX_STRING_LENGTH = 32767;

    /**
     * Write a string to the buffer
     */
    public static void writeString(ByteBuf buf, String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_LENGTH) {
            throw new EncoderException("String too big (was " + bytes.length + " bytes encoded, max " + MAX_STRING_LENGTH + ")");
        }
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * Read a string from the buffer
     */
    public static String readString(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length > MAX_STRING_LENGTH * 4) {
            throw new DecoderException("The received encoded string buffer length is longer than maximum allowed (" + length + " > " + MAX_STRING_LENGTH * 4 + ")");
        }
        if (length < 0) {
            throw new DecoderException("The received encoded string buffer length is less than zero! Weird string!");
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        String string = new String(bytes, StandardCharsets.UTF_8);
        if (string.length() > MAX_STRING_LENGTH) {
            throw new DecoderException("The received string length is longer than maximum allowed (" + length + " > " + MAX_STRING_LENGTH + ")");
        }
        return string;
    }

    /**
     * Write a UUID to the buffer
     */
    public static void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    /**
     * Read a UUID from the buffer
     */
    public static UUID readUUID(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    /**
     * Write a variable-length integer
     */
    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /**
     * Read a variable-length integer
     */
    private static int readVarInt(ByteBuf buf) {
        int i = 0;
        int j = 0;
        byte b;
        do {
            b = buf.readByte();
            i |= (b & 127) << j++ * 7;
            if (j > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((b & 128) == 128);
        return i;
    }
}
