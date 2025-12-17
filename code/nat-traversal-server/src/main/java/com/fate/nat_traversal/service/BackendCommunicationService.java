package com.fate.nat_traversal.service;


import com.fate.nat_traversal.model.Backend;
import org.springframework.beans.factory.DisposableBean;

import java.util.function.Consumer;

/**
 * @author fate
 * @date 2025/12/09 20:00
 *
 * 被代理端点通信服务
 */
public interface BackendCommunicationService extends DisposableBean {

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


    boolean register(Backend backend, int proxyPort);

    void requireSocket(String session, int num);

    boolean send(String session, String message);

    Backend getBackend(int proxyPort);

    /**
     * 关闭回调
     * @param consumer 关闭回调，意在Server调用close后释放Server资源
     */
    void closeCallback(Consumer<Integer> consumer);

    void close(int port);

    void shutdown();

    @Override
    default void destroy() throws Exception {
        shutdown();
    }
}
