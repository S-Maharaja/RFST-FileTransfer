# ProjectDataTransfer — multi-file transfer over Ethernet

Java 17 + Netty. Sends **many files at the same time** (one TCP connection per file),
verifies every 4 MB chunk with SHA-256, and resumes interrupted files automatically.

## Build (once, on each PC)

    mvn clean package

Result: `target/large-file-transfer.jar` (contains all libraries).

## Run

Receiver (start first):

    java -jar target/large-file-transfer.jar receiver [--port 9000] [--out received] [--redis host:port|none]

Sender:

    java -jar target/large-file-transfer.jar sender --host RECEIVER_IP [--port 9000] [--parallel 4] <file or folder> [more...]

* Folders: every file directly inside is sent (sub-folders are not).
* `--parallel N`: how many files are sent simultaneously (default 4); the rest wait for a free slot.
* Files are saved by name in the receiver's `--out` folder. A file is first written as `<name>.recv`
  and renamed to `<name>` only after every chunk is verified.
* Resume: run the same sender command again. Progress is kept in Redis (`recv:<name>`) if Redis is
  running on the receiver, otherwise in `<name>.progress` files inside the output folder.
* Exit code of the sender is 0 only if every file was confirmed by the receiver.

## Protocol

Sender -> receiver, length-prefixed frames:

* Handshake: `int frameLen, int fileIdLen, fileId(UTF-8 name), long totalChunks`
* Chunk: `int frameLen, int fileIdLen, fileId, long chunkIndex, long totalChunks, int dataLen, int checksumLen, checksum, data`

Receiver -> sender, always one 8-byte `long`:

* 1st message: index of the last chunk already stored (`-1` = none) -> sender resumes at `+1`
* 2nd message: `-2` (ACK_DONE) = file complete, verified and saved

## Rules enforced by the receiver

* Chunks must arrive in order; a wrong checksum or out-of-order chunk closes that connection
  (other files keep going) and progress stays at the last good chunk.
* File names containing `/ \ :` are rejected. Two connections cannot write the same name at once.
* Saved progress is ignored if the partial `.recv` file on disk is shorter than it claims.

## Limitations

* Plain TCP, no encryption or authentication — use only on a trusted direct link.
* Two files with the same name overwrite each other on the receiver (the sender refuses to send
  two files with the same name in one run).
* If a source file changes between a failed run and the retry, delete the receiver's `<name>.recv`
  and its progress entry (or `<name>.progress`) first.
* All files share one network link, so parallel transfers split the bandwidth; the gain is mostly
  for many small/medium files.
