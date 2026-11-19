package com.transfer.receiver;

import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers, per file, the index of the last chunk that was verified and written (-1 = none).
 * Used for resuming interrupted transfers. Implementations are thread-safe, because several
 * files are received at the same time.
 */
public interface ProgressStore {

    long get(String fileId);

    void set(String fileId, long lastChunk);

    void clear(String fileId);

    String describe();

    /**
     * @param redisSpec "host:port", "none" (never use Redis) or null (try localhost:6379)
     * @param dir       folder for the fallback "<file>.progress" files
     */
    static ProgressStore create(String redisSpec, Path dir) {
        if (redisSpec != null && redisSpec.equalsIgnoreCase("none")) {
            return new LocalFiles(dir);
        }
        String host = "localhost";
        int port = 6379;
        if (redisSpec != null) {
            int i = redisSpec.lastIndexOf(':');
            if (i < 0) {
                host = redisSpec;
            } else {
                host = redisSpec.substring(0, i);
                port = Integer.parseInt(redisSpec.substring(i + 1));
            }
        }
        try {
            return new Redis(host, port);
        } catch (Exception e) {
            System.out.println("WARNING: Redis not reachable at " + host + ":" + port
                    + " -> using progress files in the output folder instead (resume still works).");
            return new LocalFiles(dir);
        }
    }

    /** Resume state in Redis (key "recv:<fileId>"). */
    final class Redis implements ProgressStore {
        private final JedisPooled jedis;
        private final String where;

        Redis(String host, int port) {
            this.jedis = new JedisPooled(host, port);
            this.jedis.ping(); // fail fast if Redis is not running
            this.where = host + ":" + port;
        }

        @Override
        public long get(String fileId) {
            String v = jedis.get("recv:" + fileId);
            return v == null ? -1 : Long.parseLong(v);
        }

        @Override
        public void set(String fileId, long lastChunk) {
            jedis.set("recv:" + fileId, Long.toString(lastChunk));
        }

        @Override
        public void clear(String fileId) {
            jedis.del("recv:" + fileId);
        }

        @Override
        public String describe() {
            return "Redis at " + where;
        }
    }

    /** Resume state in a small "<fileId>.progress" text file. No extra software needed. */
    final class LocalFiles implements ProgressStore {
        private final Path dir;

        LocalFiles(Path dir) {
            this.dir = dir;
        }

        private Path file(String fileId) {
            return dir.resolve(fileId + ".progress");
        }

        @Override
        public long get(String fileId) {
            try {
                Path p = file(fileId);
                if (!Files.isRegularFile(p)) return -1;
                return Long.parseLong(Files.readString(p).trim());
            } catch (IOException | NumberFormatException e) {
                return -1; // unreadable state -> safest is to start over
            }
        }

        @Override
        public void set(String fileId, long lastChunk) {
            try {
                Files.writeString(file(fileId), Long.toString(lastChunk));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void clear(String fileId) {
            try {
                Files.deleteIfExists(file(fileId));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public String describe() {
            return "progress files (*.progress) in " + dir.toAbsolutePath();
        }
    }
}
