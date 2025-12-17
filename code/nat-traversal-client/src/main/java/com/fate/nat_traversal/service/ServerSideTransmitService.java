package com.fate.nat_traversal.service;

import org.springframework.beans.factory.DisposableBean;

import java.net.Socket;

/**
 * @author fate
 * @date 2025/12/11 21:14
 *
 * 被代理服务端透传服务
 */
public interface ServerSideTransmitService extends DisposableBean {

    boolean createServerSideSocket(String session);

    boolean awareServerSideSocket(int port);

    void shutdown();

    @Override
    default void destroy() throws Exception {
        shutdown();
    }
}
