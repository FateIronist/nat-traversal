package com.fate.nat_traversal;

import com.fate.nat_traversal.config.TurnProperties;
import com.fate.nat_traversal.enums.WorkingStatusEnum;
import com.fate.nat_traversal.exception.ProxyServerStartErrorException;
import com.fate.nat_traversal.model.ClientSocket;
import com.fate.nat_traversal.model.CommonSocket;
import com.fate.nat_traversal.model.Backend;
import com.fate.nat_traversal.service.BackendCommunicationService;
import com.fate.nat_traversal.service.BackendTransmitService;
import com.fate.nat_traversal.util.PortUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;


/**
 * @author: Fate
 * @date: 2025/12/10 22:18
 *
 */

// todo socket关闭处理不当，若服务端点未关闭socket，服务器端就会阻塞无法关闭

@Slf4j
@Component
public class Server implements DisposableBean {

    private static final Map<Integer, ServerSocket> PROXY_REFLECTION = new ConcurrentHashMap<>();

    private ThreadPoolExecutor proxyListenerPool;

    // 运行状态
    private WorkingStatusEnum workingStatus = WorkingStatusEnum.INIT;

    @Autowired
    private TurnProperties turnProperties;

    @Autowired
    private BackendCommunicationService backendCommunicationService;

    @Autowired
    private BackendTransmitService backendTransmitService;


    private void init() {
        proxyListenerPool = new ThreadPoolExecutor(
                turnProperties.getMaxServerProxy() / 3,
                turnProperties.getMaxServerProxy(),
                5,
                TimeUnit.MINUTES,
                new LinkedBlockingDeque<>(turnProperties.getMaxServerProxy()),
                new ThreadPoolExecutor.AbortPolicy()
        );

        // 注册回调，避免依赖循环
        backendCommunicationService.closeCallback(port -> {
            try {
                PROXY_REFLECTION.get(port).close();
            } catch (Exception e) {}
            PROXY_REFLECTION.remove(port);
        });

        backendTransmitService.requireSocketCallback((session, num) -> {
            backendCommunicationService.send(session, BackendCommunicationService.REQUIRE_SOCKET + num);
        });

        backendTransmitService.awareSocketCallback((session, port) -> {
            return backendCommunicationService.send(session, BackendCommunicationService.AWARE_SOCKET + port);
        });
    }

    @PostConstruct
    private void run() {
        init();
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(turnProperties.getRegPort()));

            new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    CommonSocket socket = null;

                    try {
                        socket = new CommonSocket(serverSocket.accept());
                    } catch (IOException e) {
                        logError("ServerSocket(port: {}) Accept Connection interrupt;(Exception: )", serverSocket.getLocalPort(), e.getMessage());
                        continue;
                    }

                    String msg = null;
                    try {
                        msg = socket.readString();
                    } catch (IOException e) {
                        log.info("Backend(ip: {}) read message failed. It will be close.", socket.getRemoteSocketAddress().toString());
                        socket.closeUnchecked();
                        continue;
                    }

                    if (msg.equals(BackendCommunicationService.REGISTER_PS)) {
                        if (PROXY_REFLECTION.size() >= turnProperties.getMaxServerProxy()) {
                            socket.writeUnchecked(BackendCommunicationService.REGISTER_PS_ERROR+"Server Full");
                            socket.closeUnchecked();
                            continue;
                        }

                        Backend backend = new Backend(socket.getSocket());

                        if (!registerProxy(backend)) {
                            socket.writeUnchecked(BackendCommunicationService.REGISTER_PS_ERROR+"Register Proxy Error");
                            socket.closeUnchecked();
                            continue;
                        }

                        if (!backendCommunicationService.register(backend, backend.getProxyPort())) {
                            socket.writeUnchecked(BackendCommunicationService.REGISTER_PS_ERROR+"Register Communication Error");
                            continue;
                        }

                        int retry = 3;
                        while (retry > 0) {
                            try {
                                socket.write(BackendCommunicationService.REGISTER_PS_SUCCESS + backend.getSession() + ";;port:" + backend.getProxyPort());
                            } catch (Exception e) {
                                continue;
                            }
                            break;
                        }

                        if (retry <= 0) {
                            socket.closeUnchecked();
                            log.info("Backend(session: {}) register PS success, But write session failed. It will be close.", backend.getSession());
                        }

                    } else if (msg.startsWith(BackendCommunicationService.REGISTER_TRANSMIT_SOCKET)) {
                        String sessionPort = msg.substring(BackendCommunicationService.REGISTER_TRANSMIT_SOCKET.length());
                        String[] info = sessionPort.split(";;port:");
                        String session = info[0];
                        int port = Integer.parseInt(info[1]);
                        if (!backendTransmitService.registerTransmit(session, port, socket.getSocket())) {
                            socket.writeUnchecked(BackendCommunicationService.REGISTER_TRANSMIT_SOCKET_ERROR);
                            socket.closeUnchecked();
                        }
                    } else {
                        continue;
                    }

                }
                shutdown();
                log.info("Server closed...");
            }).start();
        } catch (IOException e) {
            e.printStackTrace();
            throw new ProxyServerStartErrorException("Server Start Error");
        }

        workingStatus = WorkingStatusEnum.WORKING;
    }

    private void shutdown() {
        workingStatus = WorkingStatusEnum.CLOSING;
        log.info("Shutdown Server...");

        backendTransmitService.shutdown();
        backendCommunicationService.shutdown();

        workingStatus = WorkingStatusEnum.CLOSED;
        log.info("Server shutdown gracefully");
    }

    // 注册代理
    private boolean registerProxy(Backend backend) {
        if (unworkable()) return false;

        ServerSocket proxySocket = null;
        Integer proxyPort = null;

        synchronized (Server.class) {
            proxyPort = PortUtil.getPort();
            if (proxyPort == null) {
                return false;
            }

            try {
                proxySocket = new ServerSocket();
                proxySocket.setReuseAddress(true);
                proxySocket.bind(new InetSocketAddress(proxyPort));
            } catch (IOException e) {
                return false;
            }
        }

        PROXY_REFLECTION.put(proxyPort, proxySocket);

        backend.setProxyPort(proxyPort);
        if (!submitProxyListenerTask(backend.getSession(), proxySocket)) {
            try {
                PROXY_REFLECTION.remove(proxyPort);
                proxySocket.close();
            } catch (IOException e) {}
            return false;
        }

        return true;
    }

    @Override
    public void destroy() throws Exception {
        shutdown();
    }

    private boolean submitProxyListenerTask(String session, ServerSocket proxySocket) {
        try {
            proxyListenerPool.submit(() -> {
                while (!proxySocket.isClosed()) {
                    ClientSocket cSocket = null;
                    try {
                        cSocket = new ClientSocket(proxySocket.accept());
                    } catch (IOException e) {
                        log.warn("ProxySocket(port: {}) Accept Connection interrupt;(Exception: )", proxySocket.getLocalPort(), e.getMessage());
                        continue;
                    }
                    cSocket.init(proxySocket.getLocalPort(), session);

                    log.info("ProxySocket(port: {}) Accept ClientSocket(session: {}, ip: {})", proxySocket.getLocalPort(), cSocket.getSession(), cSocket.getRemoteSocketAddress().toString());
                    if (!backendTransmitService.transmitClient(cSocket)) {
                        log.info("ProxySocket(port: {}) transmitClient failed. It will be close.", proxySocket.getLocalPort());
                        cSocket.closeUnchecked();
                        continue;
                    }
                    log.info("BackendTransmitSocket(client session: {}) Transmitting", cSocket.getSession());
                }
            });
        } catch (RejectedExecutionException e) {
            return false;
        }
        return true;
    }

    private String bytesToString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void logError(String  message, Object... args) {
        if (!unworkable()) {
            log.error(message, args);
        }
    }

    private boolean unworkable() {
        return workingStatus != WorkingStatusEnum.WORKING;
    }
}
