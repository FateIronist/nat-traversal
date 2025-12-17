package com.fate.nat_traversal.service;

import com.fate.nat_traversal.model.ClientSocket;
import com.fate.nat_traversal.util.Consumer2;
import com.fate.nat_traversal.util.Function2;
import org.springframework.beans.factory.DisposableBean;

import java.net.Socket;

/**
 * @author fate
 * @date 2025/12/09 20:00
 *
 * 被代理端点透传服务
 */
public interface BackendTransmitService extends DisposableBean {

    boolean registerTransmit(String session, int originalPort, Socket socket);

    boolean transmitClient(ClientSocket cSocket);

    void requireSocketCallback(Consumer2<String , Integer> consumer);

    void awareSocketCallback(Function2<String , Integer, Boolean> consumer);

    void close(String session);

    void shutdown();

    @Override
    default void destroy() throws Exception {
        shutdown();
    }
}
