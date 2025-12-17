package com.fate.nat_traversal.util;

import lombok.Getter;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author fate
 * @date 2025/12/07 13:00
 *
 * 写线程安全的Socket
 */
public class ConcurrentWriteSocket {

    @Getter
    private final Socket SOCKET;

    private final ReentrantLock LOCK;

    public ConcurrentWriteSocket(Socket socket) {
        this.SOCKET = socket;
        this.LOCK = new ReentrantLock();
    }

    public byte[] read() throws IOException {
        int readLen = 0;
        byte[] readBuffer = new byte[1024];
        readLen = SOCKET.getInputStream().read(readBuffer);
        readBuffer = readLen == -1 ? null : Arrays.copyOf(readBuffer, readLen);
        return readBuffer;
    }

    public void write(byte[] bytes) throws IOException {
        LOCK.lock();
        try {
            SOCKET.getOutputStream().write(bytes);
            SOCKET.getOutputStream().flush();
        } catch (IOException e) {
            throw e;
        } finally {
            LOCK.unlock();
        }
    }

    public String readString() throws IOException {
        byte[] read = read();
        if (read == null) return null;
        return new String(read, StandardCharsets.UTF_8);
    }

    public void writeString(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isClosed() {
        return SOCKET.isClosed();
    }

    public void close() {
        try {
            SOCKET.close();
        } catch (IOException e) {

        }
    }

    public boolean isConnected() {
        return SOCKET.isConnected();
    }
}
