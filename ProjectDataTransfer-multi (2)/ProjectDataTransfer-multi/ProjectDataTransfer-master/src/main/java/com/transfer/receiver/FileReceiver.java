package com.transfer.receiver;

import com.transfer.common.ChunkMessage;
import com.transfer.common.ProtocolUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives any number of files at the same time (one TCP connection per file).
 *
 * Usage: java -jar large-file-transfer.jar receiver [--port 9000] [--out received] [--redis host:port|none]
 */
public class FileReceiver {

    private static final int DEFAULT_PORT = 9000;
    // Max frame = chunk payload + protocol header overhead (fileId, indices, checksum, etc.)
    private static final int MAX_FRAME_LENGTH = ProtocolUtil.CHUNK_SIZE + 1024 * 1024;

    // File names currently being received; stops two connections writing the same file.
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {

        int port = DEFAULT_PORT;
        String out = "received";
        String redis = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(value(args, ++i, "--port"));
                case "--out" -> out = value(args, ++i, "--out");
                case "--redis" -> redis = value(args, ++i, "--redis");
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.err.println("Usage: receiver [--port 9000] [--out received] [--redis host:port|none]");
                    System.exit(1);
                }
            }
        }

        final Path outDir = Paths.get(out).toAbsolutePath();
        Files.createDirectories(outDir);
        final ProgressStore store = ProgressStore.create(redis, outDir);

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();

            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    // Large receive buffer to handle bursts of incoming chunk data
                    .childOption(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                    .childOption(ChannelOption.SO_SNDBUF, 1 * 1024 * 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(512 * 1024, 2 * 1024 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4));
                            ch.pipeline().addLast(new ReceiverHandler(store, outDir));
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            System.out.println("Receiver listening on port " + port);
            System.out.println("Saving files to : " + outDir);
            System.out.println("Resume info in  : " + store.describe());
            printAddresses();
            f.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    private static String value(String[] args, int i, String opt) {
        if (i >= args.length) {
            System.err.println("Missing value for " + opt);
            System.exit(1);
        }
        return args[i];
    }

    /** Helps the user find the IP address to give to the sender. */
    private static void printAddresses() {
        try {
            System.out.println("Sender should connect to one of these addresses:");
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        System.out.println("   " + a.getHostAddress() + "   (" + ni.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception ignored) {
            // purely informational
        }
    }

    static class ReceiverHandler extends ChannelInboundHandlerAdapter {

        private final ProgressStore store;
        private final Path outDir;

        private String fileId;          // null until the handshake frame arrives
        private boolean claimed;        // true once fileId was added to ACTIVE
        private long totalChunks;
        private long nextChunk;         // the only chunk index we accept next
        private boolean finished;

        private RandomAccessFile raf;
        private FileChannel fileChannel;
        private Path partPath;
        private Path finalPath;

        ReceiverHandler(ProgressStore store, Path outDir) {
            this.store = store;
            this.outDir = outDir;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf in = (ByteBuf) msg;
            try {
                if (fileId == null) {
                    handshake(ctx, in);
                } else if (!finished) {
                    receiveChunk(ctx, in);
                }
            } finally {
                in.release();
            }
        }

        /** First frame: fileIdLen, fileId (= file name), totalChunks. Answers with the resume point. */
        private void handshake(ChannelHandlerContext ctx, ByteBuf in) throws IOException {
            int idLen = in.readInt();
            if (idLen <= 0 || idLen > 1024 || in.readableBytes() < idLen + 8) {
                throw new IOException("Bad handshake frame");
            }
            byte[] b = new byte[idLen];
            in.readBytes(b);
            String name = new String(b, StandardCharsets.UTF_8);
            long total = in.readLong();

            // The name becomes a file name on this machine: never allow paths.
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                    || name.indexOf('\0') >= 0 || name.equals(".") || name.equals("..") || total < 0) {
                throw new IOException("Rejected unsafe file name: " + name);
            }
            if (!ACTIVE.add(name)) {
                throw new IOException("'" + name + "' is already being received on another connection");
            }
            claimed = true;
            fileId = name;
            totalChunks = total;

            partPath = outDir.resolve(name + ".recv");
            finalPath = outDir.resolve(name);

            long last = store.get(name);

            // Trust the saved progress only if the partial file on disk really matches it.
            if (last >= 0) {
                boolean valid = Files.isRegularFile(partPath)
                        && last < total
                        && (last == total - 1
                            || Files.size(partPath) >= (last + 1) * (long) ProtocolUtil.CHUNK_SIZE);
                if (!valid) {
                    System.out.println("[" + name + "] saved progress does not match the partial file -> starting over");
                    store.clear(name);
                    last = -1;
                }
            }

            raf = new RandomAccessFile(partPath.toFile(), "rw");
            fileChannel = raf.getChannel();
            if (last < 0) {
                raf.setLength(0);   // drop leftovers of an older, different transfer
            }
            nextChunk = last + 1;

            System.out.println("[" + name + "] receiving " + total + " chunks"
                    + (last >= 0 ? " (resuming after chunk " + last + ")" : ""));

            ByteBuf out = ctx.alloc().buffer(8);
            out.writeLong(last);
            ctx.writeAndFlush(out);

            if (nextChunk >= totalChunks) {   // empty file, or everything was already received
                finish(ctx);
            }
        }

        private void receiveChunk(ChannelHandlerContext ctx, ByteBuf in) throws IOException {
            ChunkMessage cm = ProtocolUtil.readChunk(in);

            if (!cm.fileId.equals(fileId) || cm.totalChunks != totalChunks) {
                throw new IOException("Chunk does not belong to " + fileId);
            }
            if (cm.chunkIndex != nextChunk) {
                throw new IOException("Out-of-order chunk: expected " + nextChunk + " but got " + cm.chunkIndex);
            }

            byte[] calc = ProtocolUtil.sha256(cm.data, cm.dataLength);
            if (!Arrays.equals(calc, cm.checksum)) {
                // Do NOT skip it: closing the connection keeps the saved progress at the last good chunk.
                throw new IOException("Checksum mismatch at chunk " + cm.chunkIndex);
            }

            long position = cm.chunkIndex * (long) ProtocolUtil.CHUNK_SIZE;
            ByteBuffer bb = ByteBuffer.wrap(cm.data, 0, cm.dataLength);
            while (bb.hasRemaining()) {
                position += fileChannel.write(bb, position);
            }

            store.set(fileId, cm.chunkIndex);
            nextChunk++;

            // Log every 100 chunks instead of every chunk to reduce I/O overhead
            if (cm.chunkIndex % 100 == 0) {
                System.out.println("[" + fileId + "] received chunk " + cm.chunkIndex + "/" + totalChunks);
            }

            if (nextChunk >= totalChunks) {
                finish(ctx);
            }
        }

        /** All chunks are on disk: make the file final and tell the sender. */
        private void finish(ChannelHandlerContext ctx) throws IOException {
            fileChannel.force(true);
            raf.close();
            raf = null;
            fileChannel = null;

            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            store.clear(fileId);
            finished = true;

            System.out.println("[" + fileId + "] COMPLETE -> " + finalPath);

            ByteBuf ack = ctx.alloc().buffer(8);
            ack.writeLong(ProtocolUtil.ACK_DONE);
            ctx.writeAndFlush(ack);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("ERROR" + (fileId != null ? " [" + fileId + "]" : "") + ": " + cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (raf != null) {
                raf.close();
                raf = null;
            }
            if (fileId != null && !finished) {
                System.err.println("[" + fileId + "] connection ended after chunk " + (nextChunk - 1)
                        + " - send it again to resume");
            }
            if (claimed) {
                ACTIVE.remove(fileId);
            }
        }
    }
}
