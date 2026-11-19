package com.transfer;

import com.transfer.receiver.FileReceiver;
import com.transfer.sender.FileSender;

import java.util.Arrays;

/** Entry point of the runnable jar: "receiver ..." or "sender ...". */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "receiver" -> FileReceiver.main(rest);
            case "sender" -> FileSender.main(rest);
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  java -jar large-file-transfer.jar receiver [--port 9000] [--out received] [--redis host:port|none]");
        System.err.println("  java -jar large-file-transfer.jar sender --host RECEIVER_IP [--port 9000] [--parallel 4] <file or folder>...");
        System.exit(1);
    }
}
