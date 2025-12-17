package com.fate.nat_traversal.service;

import org.springframework.beans.factory.DisposableBean;

import java.net.Socket;

/**
 * @author fate
 * @date 2025/12/11 21:12
 * 与代理服务器通信服务
 */
public interface ProxyServerCommunicationService extends DisposableBean {

    String PROTOCOL_PREFIX = "JTURN-F/1.0 ";

    String REGISTER_PS = PROTOCOL_PREFIX + "Register PS";
    String REGISTER_PS_SUCCESS = PROTOCOL_PREFIX + "Register PS Success:";
    String REGISTER_PS_ERROR = PROTOCOL_PREFIX + "Register PS Error:";

    String PS_CLOSED = PROTOCOL_PREFIX + "PS Closed";

    String REQUIRE_SOCKET = PROTOCOL_PREFIX + "Require Socket:";
    String REQUIRE_SOCKET_ERROR = PROTOCOL_PREFIX + "Require Socket Error:";

    String AWARE_SOCKET = PROTOCOL_PREFIX + "Aware Socket:";

    String REGISTER_TRANSMIT_SOCKET = PROTOCOL_PREFIX + "Register Transmit Socket Session:";
    String REGISTER_TRANSMIT_SOCKET_SUCCESS = PROTOCOL_PREFIX + "Register Transmit Socket Success";
    String REGISTER_TRANSMIT_SOCKET_ERROR = PROTOCOL_PREFIX + "Register Transmit Socket ERROR";

    String PING = PROTOCOL_PREFIX + "Ping:";
    String PONG = PROTOCOL_PREFIX + "Pong";

    String TSPING = PROTOCOL_PREFIX + "Ping";
    String TSPONG = PROTOCOL_PREFIX + "Pong";

    String CLIENT_CONNECTION = PROTOCOL_PREFIX + "Client Connection:";
    String CLIENT_CLOSED = PROTOCOL_PREFIX + "Client Closed";

    String SERVER_CLOSED = PROTOCOL_PREFIX + "Server Closed";

    boolean start();

    boolean sendMessage(String message);

    void shutdown();

    @Override
    default void destroy() throws Exception {
        shutdown();
    }

}
