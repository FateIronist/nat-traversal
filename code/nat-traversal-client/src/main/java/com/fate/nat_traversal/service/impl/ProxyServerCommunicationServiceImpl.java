package com.fate.nat_traversal.service.impl;

import com.fate.nat_traversal.config.NatTraversalProperties;
import com.fate.nat_traversal.config.RemoteServerProperties;
import com.fate.nat_traversal.enums.WorkingStatusEnum;
import com.fate.nat_traversal.service.ProxyServerCommunicationService;
import com.fate.nat_traversal.service.ServerSideTransmitService;
import com.fate.nat_traversal.service.TaskSchedulerService;
import com.fate.nat_traversal.util.ConcurrentWriteSocket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * @author fate
 * @date 2025/12/11 20:30
 *
 * 与代理端通信服务
 */
@Slf4j
@Service
public class ProxyServerCommunicationServiceImpl implements ProxyServerCommunicationService {

    private static final Long SERVER_KEEP_ALIVE_TIME = 3000L;

    private ConcurrentWriteSocket communicationSocket;

    // 运行状态
    private WorkingStatusEnum workingStatus = WorkingStatusEnum.INIT;

    private long lastPongTime = 0;

    private String session;

    @Autowired
    private ServerSideTransmitService serverSideTransmitService;

    @Autowired
    private NatTraversalProperties natTraversalProperties;

    @Autowired
    private TaskSchedulerService taskScheduler;

    @Override
    public boolean start() {
        RemoteServerProperties remoteServerProperties = natTraversalProperties.getServer();
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(true);
            socket.connect(new InetSocketAddress(remoteServerProperties.getHost(), remoteServerProperties.getPort()));
        } catch (IOException e) {
            log.error("Connect to proxy server error;(Exception: {})", e.getMessage());
            return false;
        }

        log.info("Connect to proxy server success, local communication port: {}", socket.getLocalPort());

        ConcurrentWriteSocket communicationSocket = new ConcurrentWriteSocket(socket);
        this.communicationSocket = communicationSocket;
        this.lastPongTime = System.currentTimeMillis();

        try {
            communicationSocket.writeString(REGISTER_PS);
            String msg = communicationSocket.readString();
            if (log.isDebugEnabled()) log.debug("Receive register PS success msg: ({})", msg);

            if (msg.startsWith(REGISTER_PS_SUCCESS)) {
                String[] args = msg.substring(REGISTER_PS_SUCCESS.length()).split(";;port:");
                session = args[0];
                log.info("==========----------Register PS Success, be proxied to port: {}----------==========", args[1]);
            }else {
                logError("Register PS Error");
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }


        // 定时发送心跳
        taskScheduler.submit(() -> {
            communicationSocket.writeStringUnchecked(PING+session);
        }, 0, 1, TimeUnit.SECONDS);

        new Thread(() -> {
            int readErrTimes = 0;
            while (!communicationSocket.isClosed()) {

                String msg = null;
                try {
                    msg = communicationSocket.readString();
                } catch (IOException e) {
                    readErrTimes++;
                    log.warn("CommunicationSocket read error;(Exception: {})", e.getMessage());

                    if (readErrTimes > 10) {
                        communicationSocket.close();
                        break;
                    }

                    continue;
                }

                if (msg == null) {
                    logError("ProxyServer has closed!");
                    break;
                }

                if (msg.equals(PONG)) {
                    lastPongTime = System.currentTimeMillis();
                } else if (msg == null) {
                    break;
                } else {
                    if (log.isDebugEnabled()) log.debug("Receive message: {}", msg);

                    if (msg.startsWith(REQUIRE_SOCKET)) {
                        int num = Integer.parseInt(msg.substring(REQUIRE_SOCKET.length()));
                        int maxRetry = 5;
                        while (num > 0) {
                            if (!serverSideTransmitService.createServerSideSocket(session)) {
                                if (maxRetry-- <= 0) {
                                    communicationSocket.writeStringUnchecked(REQUIRE_SOCKET_ERROR);
                                    break;
                                }
                                continue;
                            }
                            num--;
                        }

                        if (maxRetry <= 0) {
                            log.warn("Create server side socket error");
                        }
                        // todo
                    } else if (msg.startsWith(AWARE_SOCKET)) {
                        int port = Integer.parseInt(msg.substring(AWARE_SOCKET.length()));

                        if (!serverSideTransmitService.awareServerSideSocket(port)) {
                        }
                    }
                }


            }

            communicationSocket.writeStringUnchecked(PS_CLOSED);
            log.info("Proxy server communication socket closed");
            shutdown();
        }).start();

        workingStatus = WorkingStatusEnum.WORKING;
        return true;
    }

    @Override
    public boolean sendMessage(String message) {
        if (unworkable()) return false;
        try {
            communicationSocket.writeString(message);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public void shutdown() {
        if (workingStatus == WorkingStatusEnum.CLOSED || workingStatus == WorkingStatusEnum.CLOSING) return;
        communicationSocket.close();
    }

    private boolean serverTimeout() {
        return System.currentTimeMillis() - lastPongTime > SERVER_KEEP_ALIVE_TIME;
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
