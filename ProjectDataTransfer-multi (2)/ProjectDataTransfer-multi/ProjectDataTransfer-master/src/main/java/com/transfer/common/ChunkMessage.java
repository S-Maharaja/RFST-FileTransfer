package com.transfer.common;

public class ChunkMessage {

    public String fileId;
    public long chunkIndex;
    public long totalChunks;
    public int dataLength;
    public byte[] checksum;
    public byte[] data;

    public ChunkMessage() {}

    public ChunkMessage(String fileId,
                        long chunkIndex,
                        long totalChunks,
                        int dataLength,
                        byte[] checksum,
                        byte[] data) {
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.dataLength = dataLength;
        this.checksum = checksum;
        this.data = data;
    }
}
