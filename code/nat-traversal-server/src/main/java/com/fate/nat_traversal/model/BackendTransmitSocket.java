package com.fate.nat_traversal.model;

import lombok.*;

import java.io.IOException;
import java.net.Socket;


/**
 * @author fate
 * @date 2025/12/09 20:30
 *
 * 被代理端点透传Socket
 */

@EqualsAndHashCode(callSuper = true)
public class BackendTransmitSocket extends CommonSocket{

    @Getter
    @Setter
    private String BackendSession;

    @Getter
    @Setter
    // 被代理端的原始端口，非NAT分配端口
    private Integer originalPort;

    private static final Long KEEP_ALIVE_TIME = 1000 * 60 * 5L;

    private Long lastActiveTime = System.currentTimeMillis();

    public BackendTransmitSocket(Socket socket) {
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
