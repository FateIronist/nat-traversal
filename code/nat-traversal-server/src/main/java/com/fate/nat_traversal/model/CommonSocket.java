package com.fate.nat_traversal.model;

import lombok.Getter;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * @author fate
 * @date 2025/12/9 21:30
 *
 * Socket通用操作封装
 */
public class CommonSocket{

    @Getter
    private final Socket socket;

    public CommonSocket(Socket socket) {
        this.socket = socket;
    }

    @Getter
    private boolean positiveClosed = false;

    public byte[] read() throws IOException {
        int readLen = 0;
        byte[] readBuffer = new byte[1024];
        readLen = socket.getInputStream().read(readBuffer);
        readBuffer = readLen == -1 ? null : Arrays.copyOf(readBuffer, readLen);
        return readBuffer;
    }

    public String readString() throws IOException {
        byte[] bytes = read();
        return bytes  == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    public void write(byte[]  bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    public boolean writeUnchecked(byte[]  bytes) {
        try {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public boolean writeUnchecked(String string) {
        try {
            write(string.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public boolean closeUnchecked() {
        if (!socket.isClosed()) {
            try {
                close();
            } catch (IOException e) {
                return false;
            }
        }

        positiveClosed = true;
        return true;
    }

    public void close() throws IOException {
        socket.close();
        positiveClosed = true;
    }

    public boolean isClosed() {
        return socket.isClosed();
    }


    public boolean isConnected() {
        return socket.isConnected();
    }

    public SocketAddress getRemoteSocketAddress() {
        return socket.getRemoteSocketAddress();
    }

    public Integer getLocalPort(){
        return socket.getLocalPort();
    }

}
