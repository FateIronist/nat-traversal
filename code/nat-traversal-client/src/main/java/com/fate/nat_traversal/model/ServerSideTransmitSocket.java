package com.fate.nat_traversal.model;

import lombok.EqualsAndHashCode;

import java.io.IOException;
import java.net.Socket;

/**
 * @author fate
 * @date 2025/12/11 20:30
 *
 * 本地服务端点透传Socket
 */
@EqualsAndHashCode(callSuper = true)
public class ServerSideTransmitSocket extends CommonSocket{

    private static final Long KEEP_ALIVE_TIME = 1000 * 60 * 5L;

    private Long lastActiveTime = System.currentTimeMillis();

    public ServerSideTransmitSocket(Socket socket) {
        super(socket);
    }

    public boolean isAlive() {
        return System.currentTimeMillis() - lastActiveTime < KEEP_ALIVE_TIME;
    }

    public void refreshActiveTime() {
        lastActiveTime = System.currentTimeMillis();
    }

    @Override
    public byte[] read() throws IOException {
        refreshActiveTime();
        return super.read();
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        refreshActiveTime();
        super.write(bytes);
    }

}
