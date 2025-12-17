package com.fate.nat_traversal.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.net.Socket;

/**
 * @author fate
 * @date 2025/12/09 20:30
 *
 * 客户端Socket
 */

@EqualsAndHashCode(callSuper = true)
public class ClientSocket extends CommonSocket{

    /**
     * 客户端标识符
     * 结构：address-timestamp
     */
    @Getter
    @Setter
    private String session;

    @Getter
    @Setter
    private int requestPort;

    @Getter
    @Setter
    private String BackendSession;

    public ClientSocket(Socket socket) {
        super(socket);
    }

    public void init(int requestPort, String BackendSession) {
        this.requestPort = requestPort;
        this.BackendSession = BackendSession;
        session = generateSession();
    }

    public String generateSession() {
        return "Client-"+getSocket().getRemoteSocketAddress().toString() + "-" + System.currentTimeMillis();
    }

}
