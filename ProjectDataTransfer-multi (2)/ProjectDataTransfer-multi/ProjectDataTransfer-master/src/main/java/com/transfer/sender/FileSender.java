package com.transfer.sender;

import com.transfer.common.ChunkMessage;
import com.transfer.common.ProtocolUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.FixedLengthFrameDecoder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

/**
 * Sends one or more files to a receiver, several of them at the same time
 * (one TCP connection per file, at most --parallel connections at once).
 *
 * Usage: java -jar large-file-transfer.jar sender --host RECEIVER_IP [--port 9000] [--parallel 4] file_or_folder...
 */
public class FileSender {

    private static final int DEFAULT_PORT = 9000;

    public static void main(String[] args) throws Exception {

        String host = null;
        int port = DEFAULT_PORT;
        int parallel = 4;
        List<String> paths = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = value(args, ++i, "--host");
                case "--port" -> port = Integer.parseInt(value(args, ++i, "--port"));
                case "--parallel" -> parallel = Integer.parseInt(value(args, ++i, "--parallel"));
                default -> {
                    if (args[i].startsWith("--")) {
                        usage("Unknown option: " + args[i]);
                    }
                    paths.add(args[i]);
                }
            }
        }
        if (host == null) usage("--host is required");
        if (paths.isEmpty()) usage("Give at least one file or folder to send");
        if (parallel < 1) usage("--parallel must be at least 1");

        List<File> files = collectFiles(paths);
        if (files.isEmpty()) usage("No files found to send");

        System.out.println("Sending " + files.size() + " file(s) to " + host + ":" + port
                + " (up to " + parallel + " at the same time)");

        EventLoopGroup group = new NioEventLoopGroup();
        Semaphore slots = new Semaphore(parallel);
        CountDownLatch allDone = new CountDownLatch(files.size());
        List<SenderHandler> handlers = new ArrayList<>();
        long startNs = System.nanoTime();

        try {
            for (File file : files) {
                slots.acquire();   // wait for a free connection slot

                long fileSize = file.length();
                long totalChunks = (fileSize + ProtocolUtil.CHUNK_SIZE - 1) / ProtocolUtil.CHUNK_SIZE;
                SenderHandler handler = new SenderHandler(file, file.getName(), totalChunks);
                handlers.add(handler);

                Bootstrap b = new Bootstrap();
                b.group(group)
                        .channel(NioSocketChannel.class)
                        // Disable Nagle's algorithm for lower latency on large writes
                        .option(ChannelOption.TCP_NODELAY, true)
                        // Large send buffer to keep the NIC fed between flushes
                        .option(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024)
                        .option(ChannelOption.SO_RCVBUF, 1 * 1024 * 1024)
                        // Watermark controls when isWritable() flips; keeps 2-4 chunks buffered
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                                new WriteBufferWaterMark(
                                        2 * ProtocolUtil.CHUNK_SIZE,
                                        4 * ProtocolUtil.CHUNK_SIZE))
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                // Receiver -> sender messages are always exactly one 8-byte long
                                ch.pipeline().addLast(new FixedLengthFrameDecoder(8));
                                ch.pipeline().addLast(handler);
                            }
                        });

                ChannelFuture cf = b.connect(host, port);
                cf.addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        f.channel().closeFuture().addListener(c -> {
                            slots.release();
                            allDone.countDown();
                        });
                    } else {
                        handler.error = "could not connect: " + f.cause();
                        slots.release();
                        allDone.countDown();
                    }
                });
            }

            allDone.await();
        } finally {
            group.shutdownGracefully().sync();
        }

        double seconds = (System.nanoTime() - startNs) / 1e9;
        int ok = 0;
        long bytes = 0;
        System.out.println();
        System.out.println("================ SUMMARY ================");
        for (SenderHandler h : handlers) {
            if (h.success) {
                ok++;
                bytes += h.file.length();
                System.out.println("  OK      " + h.fileId);
            } else {
                System.out.println("  FAILED  " + h.fileId + "  (" + h.error + ")");
            }
        }
        System.out.printf("%d of %d file(s) transferred in %.1f s (%.1f MB/s overall)%n",
                ok, handlers.size(), seconds, bytes / 1048576.0 / Math.max(seconds, 0.001));
        if (ok != handlers.size()) {
            System.out.println("Run the same command again: failed files resume where they stopped.");
            System.exit(2);
        }
    }

    private static String value(String[] args, int i, String opt) {
        if (i >= args.length) usage("Missing value for " + opt);
        return args[i];
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: sender --host RECEIVER_IP [--port 9000] [--parallel 4] <file or folder> [more files/folders...]");
        System.exit(1);
    }

    /** Expands folders (their files, not sub-folders) and checks that every name is unique. */
    private static List<File> collectFiles(List<String> paths) {
        List<File> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String p : paths) {
            File f = new File(p);
            List<File> found = new ArrayList<>();
            if (f.isDirectory()) {
                File[] list = f.listFiles(File::isFile);
                if (list != null) {
                    Arrays.sort(list);
                    found.addAll(Arrays.asList(list));
                }
            } else if (f.isFile()) {
                found.add(f);
            } else {
                usage("Not found: " + p);
            }
            for (File x : found) {
                if (!names.add(x.getName())) {
                    usage("Two files have the same name '" + x.getName()
                            + "' - the receiver saves files by name, so rename one of them.");
                }
                result.add(x);
            }
        }
        return result;
    }

    static class SenderHandler extends ChannelInboundHandlerAdapter {

        final File file;
        final String fileId;          // the file name; also the resume key on the receiver
        private final long totalChunks;

        volatile boolean success;
        volatile String error;

        private boolean handshakeDone;
        private boolean eofReached;
        private long chunkIndex;
        private long startNs;

        // FileChannel + a heap ByteBuffer wrapper allow efficient positional reads
        // without copying data; the same underlying byte[] is reused across chunks.
        private RandomAccessFile raf;
        private FileChannel fileChannel;
        private final byte[] buffer = new byte[ProtocolUtil.CHUNK_SIZE];
        private final ByteBuffer readBuf = ByteBuffer.wrap(buffer);

        SenderHandler(File file, String fileId, long totalChunks) {
            this.file = file;
            this.fileId = fileId;
            this.totalChunks = totalChunks;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            startNs = System.nanoTime();
            byte[] idBytes = fileId.getBytes(StandardCharsets.UTF_8);

            // Handshake frame: [frameLen][fileIdLen][fileId][totalChunks]
            ByteBuf out = ctx.alloc().buffer(4 + 4 + idBytes.length + 8);
            out.writeInt(4 + idBytes.length + 8);
            out.writeInt(idBytes.length);
            out.writeBytes(idBytes);
            out.writeLong(totalChunks);
            ctx.writeAndFlush(out);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf in = (ByteBuf) msg;
            try {
                long v = in.readLong();
                if (!handshakeDone) {
                    // First answer = last chunk the receiver already has (-1 = none)
                    handshakeDone = true;
                    long startChunk = v + 1;
                    System.out.println("[" + fileId + "] " + totalChunks + " chunks"
                            + (startChunk > 0 ? ", resuming from chunk " + startChunk : ""));
                    openAndStart(ctx, startChunk);
                } else if (v == ProtocolUtil.ACK_DONE) {
                    // Receiver confirmed: everything verified and saved.
                    success = true;
                    double s = (System.nanoTime() - startNs) / 1e9;
                    System.out.printf("[%s] DONE in %.1f s%n", fileId, s);
                    ctx.close();
                }
            } finally {
                in.release();
            }
        }

        private void openAndStart(ChannelHandlerContext ctx, long startChunk) throws Exception {
            raf = new RandomAccessFile(file, "r");
            fileChannel = raf.getChannel();

            // FileChannel.position() is a single syscall; much faster than skipping bytes.
            if (startChunk > 0) {
                fileChannel.position(startChunk * (long) ProtocolUtil.CHUNK_SIZE);
            }
            chunkIndex = startChunk;
            sendNextChunks(ctx);
        }

        /** Resumes sending when the write buffer has drained below the low-water mark. */
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            if (ctx.channel().isWritable()) {
                sendNextChunks(ctx);
            }
            ctx.fireChannelWritabilityChanged();
        }

        /**
         * Sends as many chunks as the channel's write buffer accepts, then flushes once.
         * When the buffer is full (isWritable() == false) we stop; channelWritabilityChanged
         * resumes us. At end of file we do NOT close: we wait for the receiver's confirmation,
         * because closing right after flush() could discard data that is still queued.
         */
        private void sendNextChunks(ChannelHandlerContext ctx) {
            if (!handshakeDone || eofReached || fileChannel == null) return;

            byte[] fileIdBytes = fileId.getBytes(StandardCharsets.UTF_8);
            try {
                boolean didWrite = false;
                while (ctx.channel().isWritable()) {
                    // Fill the whole buffer: a short read in the middle of the file would
                    // break the "chunkIndex * CHUNK_SIZE" offset math at the receiver.
                    readBuf.clear();
                    int read = 0;
                    while (readBuf.hasRemaining()) {
                        int n = fileChannel.read(readBuf);
                        if (n < 0) break;
                        read += n;
                    }

                    if (read == 0) {   // end of file
                        eofReached = true;
                        raf.close();
                        fileChannel = null;
                        break;
                    }

                    byte[] checksum = ProtocolUtil.sha256(buffer, read);
                    ChunkMessage cm = new ChunkMessage(
                            fileId, chunkIndex, totalChunks, read, checksum, buffer
                    );

                    int capacity = 4 + 4 + fileIdBytes.length + 8 + 8 + 4 + 4 + checksum.length + read;
                    ByteBuf out = ctx.alloc().buffer(capacity);
                    ProtocolUtil.writeChunk(out, cm);   // copies the bytes, so reusing 'buffer' is safe
                    ctx.write(out);
                    didWrite = true;

                    if (chunkIndex % 100 == 0) {
                        System.out.println("[" + fileId + "] sent chunk " + chunkIndex + "/" + totalChunks);
                    }
                    chunkIndex++;
                }
                if (didWrite) ctx.flush();
            } catch (Exception e) {
                error = "read/send error: " + e;
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (raf != null) {
                raf.close();
                raf = null;
            }
            if (!success && error == null) {
                error = "connection closed before the receiver confirmed the file";
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            error = String.valueOf(cause);
            ctx.close();
        }
    }
}
