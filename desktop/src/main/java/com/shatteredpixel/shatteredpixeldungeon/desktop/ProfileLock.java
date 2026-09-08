package com.shatteredpixel.shatteredpixeldungeon.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Prevents two desktop runtimes from writing the same profile. */
public final class ProfileLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    public ProfileLock(Path directory) throws IOException {
        Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve(".instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); }
        catch (RuntimeException | IOException error) { channel.close(); throw error; }
        if (acquired == null) { channel.close(); throw new IOException("PROFILE_IN_USE"); }
        lock = acquired;
        // Legacy game cleanup removes empty files. Never leave the held inode empty.
        try {
            channel.write(java.nio.ByteBuffer.wrap("SPD profile instance lock\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0);
            channel.force(true);
        } catch (IOException | RuntimeException error) {
            try { lock.release(); } finally { channel.close(); }
            throw error;
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
