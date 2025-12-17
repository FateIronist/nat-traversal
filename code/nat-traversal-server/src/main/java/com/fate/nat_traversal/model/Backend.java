package com.fate.nat_traversal.model;

import com.fate.nat_traversal.util.ConcurrentWriteSocket;
import lombok.Data;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * @author fate
 * @date 2025/12/04 11:30
 *
 * 服务端点注册对象封装，包括被分配后的session、公网ip、端口、以及在线信息
 */

@Data
public class Backend {

    private static final long ONLINE_TIMEOUT = 3000;
    private static final long COMMUNICATION_FREQUENT_TIME = 500;
    /**
     * 会话id
     */
    private volatile String session;

    /**
     * 服务端点通信Socket
     * 单线程读，多线程写，因此必须使用写安全类型
     */
    private ConcurrentWriteSocket communicationSocket;

    /**
     * 服务端点ip
     */
    private String address;

    /**
     * 服务端点host
     */
    private String host;

    /**
     * 服务端点端口
     */
    private Integer port;

    /**
     * 透传代理端口
     */
    private Integer proxyPort;

    /**
     * 在线状态
     */
    private Long lastPingTime = System.currentTimeMillis();


    public Backend(Socket socket) {
        this.communicationSocket = new ConcurrentWriteSocket(socket);
        this.address = socket.getRemoteSocketAddress().toString();
        this.host = ((InetSocketAddress)socket.getRemoteSocketAddress()).getHostString();
        this.port = ((InetSocketAddress)socket.getRemoteSocketAddress()).getPort();
        this.lastPingTime = System.currentTimeMillis();
    }

    public String getSession() {
        if (session == null) {
            synchronized (this) {
                if (session == null) generateSession();
            }
        }
        return session;
    }

    public String generateSession() {
        return this.session = "Backend-" + this.address + "::" + proxyPort + "-" + System.currentTimeMillis();
    }

    public boolean isOnline() {
        return System.currentTimeMillis() - lastPingTime <= ONLINE_TIMEOUT;
    }

    // todo
    public boolean communicationTooFrequent() {
        return false;
    }

    public void refreshOnlineTime() {
        lastPingTime = System.currentTimeMillis();
    }

    public boolean proxiedTo(Integer port) {
        return this.proxyPort != null && this.proxyPort == port;
    }

    public boolean send(String message) {
        try {
            communicationSocket.writeString(message);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public String read() throws IOException {
        return communicationSocket.readString();
    }

    public boolean isClosed() {
        return communicationSocket.isClosed();
    }

    public void close() {
        if (!communicationSocket.isClosed()) communicationSocket.close();
    }
}
