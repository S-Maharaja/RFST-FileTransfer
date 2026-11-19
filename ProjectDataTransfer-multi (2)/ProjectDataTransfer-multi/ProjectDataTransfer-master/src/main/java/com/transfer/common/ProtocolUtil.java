package com.transfer.common;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ProtocolUtil {

    // 4 MB chunks reduce per-chunk overhead (fewer checksums, Redis writes, frame headers)
    public static final int CHUNK_SIZE = 4 * 1024 * 1024;

    /**
     * Receiver -> sender message (a single 8-byte long) meaning
     * "the whole file has been received, verified and saved".
     * Resume replies are always >= -1, so this value can never be confused with them.
     */
    public static final long ACK_DONE = -2L;

    // Reuse MessageDigest per thread to avoid the cost of getInstance() on every chunk
    private static final ThreadLocal<MessageDigest> SHA256_MD = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });

    public static void writeChunk(ByteBuf out, ChunkMessage msg) {
        byte[] fileIdBytes = msg.fileId.getBytes(StandardCharsets.UTF_8);

        // Compute payload size upfront so we can write the 4-byte length prefix directly,
        // eliminating the temporary buffer and the extra copy it required.
        int payloadLength = 4 + fileIdBytes.length
                + 8 + 8
                + 4
                + 4 + msg.checksum.length
                + msg.dataLength;

        out.writeInt(payloadLength);
        out.writeInt(fileIdBytes.length);
        out.writeBytes(fileIdBytes);
        out.writeLong(msg.chunkIndex);
        out.writeLong(msg.totalChunks);
        out.writeInt(msg.dataLength);
        out.writeInt(msg.checksum.length);
        out.writeBytes(msg.checksum);
        out.writeBytes(msg.data, 0, msg.dataLength);
    }


    public static ChunkMessage readChunk(ByteBuf in) {
        ChunkMessage msg = new ChunkMessage();

        int fileIdLen = in.readInt();
        if (fileIdLen < 0 || fileIdLen > 1024 || fileIdLen > in.readableBytes()) {
            throw new IllegalArgumentException("Corrupt chunk frame (fileId length " + fileIdLen + ")");
        }
        byte[] fileIdBytes = new byte[fileIdLen];
        in.readBytes(fileIdBytes);
        msg.fileId = new String(fileIdBytes, StandardCharsets.UTF_8);

        msg.chunkIndex = in.readLong();
        msg.totalChunks = in.readLong();

        msg.dataLength = in.readInt();
        int checksumLen = in.readInt();

        // Validate lengths BEFORE allocating so a corrupt frame cannot trigger a huge allocation.
        if (checksumLen < 0 || checksumLen > 64
                || msg.dataLength < 0 || msg.dataLength > CHUNK_SIZE
                || (long) checksumLen + msg.dataLength > in.readableBytes()) {
            throw new IllegalArgumentException("Corrupt chunk frame (lengths " + checksumLen + "/" + msg.dataLength + ")");
        }

        msg.checksum = new byte[checksumLen];
        in.readBytes(msg.checksum);

        msg.data = new byte[msg.dataLength];
        in.readBytes(msg.data);

        return msg;
    }

    public static byte[] sha256(byte[] data, int len) {
        MessageDigest md = SHA256_MD.get();
        md.reset();
        md.update(data, 0, len);
        return md.digest();
    }
}
