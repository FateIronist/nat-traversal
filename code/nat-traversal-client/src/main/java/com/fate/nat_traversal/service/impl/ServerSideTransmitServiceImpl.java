package com.fate.nat_traversal.service.impl;

import com.fate.nat_traversal.config.ClientProperties;
import com.fate.nat_traversal.config.NatTraversalProperties;
import com.fate.nat_traversal.config.RemoteServerProperties;
import com.fate.nat_traversal.enums.WorkingStatusEnum;
import com.fate.nat_traversal.model.CommonSocket;
import com.fate.nat_traversal.model.ServerSideTransmitSocket;
import com.fate.nat_traversal.service.ProxyServerCommunicationService;
import com.fate.nat_traversal.service.ServerSideTransmitService;
import com.fate.nat_traversal.service.TaskSchedulerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author fate
 * @date 2025/12/11 20:30
 *
 * 本地服务端点与代理服务器透传服务
 */
@Slf4j
@Service
public class ServerSideTransmitServiceImpl implements ServerSideTransmitService {

    // Socket池(维护俩列表方便空闲资源清理、优雅关闭)
    private static final Map<Integer, ServerSideTransmitSocket> SPARE_TRANSMIT_SOCKETS = new ConcurrentHashMap<>();
    private static final LinkedList<ServerSideTransmitSocket> BUSY_TRANSMIT_SOCKETS = new LinkedList<>();

    // 客户端数据传输线程池
    private ThreadPoolExecutor transmitPool;

    // 运行状态
    private WorkingStatusEnum workingStatus = WorkingStatusEnum.INIT;

    private ClientProperties clientProperties;

    private RemoteServerProperties remoteServerProperties;

    @Autowired
    private NatTraversalProperties natTraversalProperties;

    @Autowired
    private TaskSchedulerService taskScheduler;

    @PostConstruct
    private void init() {
        clientProperties = natTraversalProperties.getClient();
        remoteServerProperties = natTraversalProperties.getServer();
        transmitPool= new ThreadPoolExecutor(
                clientProperties.getMaxConnection()*2/3,
                clientProperties.getMaxConnection()*2,
                5,
                TimeUnit.MINUTES,
                new LinkedBlockingDeque<>(clientProperties.getMaxConnection()*2),
                new ThreadPoolExecutor.AbortPolicy()
        );

        // 定时清理
        taskScheduler.submit(() -> {
            if (unworkable()) return;

            List<ServerSideTransmitSocket> toRemove = new ArrayList<>();

            SPARE_TRANSMIT_SOCKETS.forEach((port, socket) -> {
                if (!socket.isAlive()) {
                    socket.closeUnchecked();
                    toRemove.add(socket);
                }
                if (socket.isClosed()) {
                    toRemove.add(socket);
                }
            });

            if (log.isDebugEnabled() && toRemove.size() > 0) {
                String[] ports = new String[toRemove.size()];

                for (int i = 0; i < toRemove.size(); ++i) {
                    ports[i] = toRemove.get(i).getLocalPort() + "";
                }

                log.debug("ServerSideTransmitService remove spare transmit socket port: {}, size: {}", String.join("、", ports), toRemove.size());
            }

            toRemove.forEach(socket -> SPARE_TRANSMIT_SOCKETS.remove(socket.getLocalPort()));


        }, 5, 5, TimeUnit.MINUTES);

        // 更新状态
        workingStatus = WorkingStatusEnum.WORKING;
    }

    @Override
    public boolean createServerSideSocket(String session) {
        if (unworkable()) return false;

        ServerSideTransmitSocket tSocket = new ServerSideTransmitSocket(new Socket());
        try {
            tSocket.getSocket().setReuseAddress(true);
            tSocket.getSocket().connect(new InetSocketAddress(remoteServerProperties.getHost(), remoteServerProperties.getPort()));
        } catch (IOException e) {
            logError("Create ServerSideTransmitSocket failed: {}", e.getMessage());
            tSocket.closeUnchecked();
            return false;
        }

        // 先写入，防止并发时唤醒socket获得为null
        SPARE_TRANSMIT_SOCKETS.put(tSocket.getLocalPort(), tSocket);

        int retry = 3;
        while (retry-- > 0) {
            try {
                tSocket.write(ProxyServerCommunicationService.REGISTER_TRANSMIT_SOCKET+session+";;port:"+tSocket.getLocalPort());
            } catch (IOException e) {
                continue;
            }
            break;
        }

        if (retry <= 0) {
            SPARE_TRANSMIT_SOCKETS.remove(tSocket.getLocalPort());
            tSocket.closeUnchecked();
            log.warn("Register PS Error");
            return false;
        }


        if (log.isDebugEnabled()) log.debug("ServerSideTransmitSocket(port: {}) created", tSocket.getLocalPort());
        return true;
    }

    @Override
    public boolean awareServerSideSocket(int port) {
        ServerSideTransmitSocket tSocket = getSpareTransmitSocket(port);
        if (tSocket == null || tSocket.isClosed() || !tSocket.isAlive()) {
            log.warn("ServerSideTransmitSocket(port: {}) not exists", port);
            return false;
        }

        if (!bindServerSideSocket(tSocket)) {
            tSocket.closeUnchecked();
            log.warn("Bind ServerSideTransmitSocket failed");
            return false;
        }

        try {
            if (!tSocket.readString().equals(ProxyServerCommunicationService.TSPING)) {
                return false;
            }
            tSocket.write(ProxyServerCommunicationService.TSPONG);
        } catch (IOException e) {
            log.warn("ServerSideTransmitSocket(port: {}) aware error;(Exception: {})", tSocket.getLocalPort(), e.getMessage());
            tSocket.closeUnchecked();
            return false;
        }

        log.info("ServerSideTransmitSocket(port: {}) binded", tSocket.getLocalPort());
        BUSY_TRANSMIT_SOCKETS.add(tSocket);
        return true;
    }

    private ServerSideTransmitSocket getSpareTransmitSocket(int port) {
        AtomicReference<ServerSideTransmitSocket> transmitSocket = new AtomicReference<>();
        SPARE_TRANSMIT_SOCKETS.compute(port, (key, value) -> {
            transmitSocket.set(value);
            return null;
        });
        return transmitSocket.get();
    }

    @Override
    public void shutdown() {
        if (workingStatus == WorkingStatusEnum.CLOSED || workingStatus == WorkingStatusEnum.CLOSING) return;

        log.info("Shutdown ServerSideTransmitService...");
        workingStatus = WorkingStatusEnum.CLOSING;

        SPARE_TRANSMIT_SOCKETS.forEach((port, tSocket) -> tSocket.closeUnchecked());

        SPARE_TRANSMIT_SOCKETS.clear();

        BUSY_TRANSMIT_SOCKETS.forEach(tSocket -> tSocket.closeUnchecked());

        transmitPool.shutdown();

        workingStatus = WorkingStatusEnum.CLOSED;
        log.info("ServerSideTransmitService shutdown gracefully");
    }

    private boolean bindServerSideSocket(ServerSideTransmitSocket tSocket) {
        if (unworkable()) return false;
        if (tSocket.isClosed() || !tSocket.isAlive()) {
            log.warn("ServerSideTransmitSocket is closed or not alive");
            return false;
        }

        CommonSocket ssSocket = null;
        try {
            ssSocket = new CommonSocket(new Socket());
            ssSocket.getSocket().setReuseAddress(true);
            ssSocket.getSocket().connect(new InetSocketAddress(clientProperties.getServerPort()));
        } catch (IOException e) {
            logError("Bind ServerSideTransmitSocket failed: {}", e.getMessage());
            return false;
        }

        return submitSS2PSTransmitTask(ssSocket, tSocket) && submitPS2SSTransmitTask(tSocket, ssSocket);
    }


//    private ServerSideTransmitSocket getServerSideTransmitSocket() {
//        if (unworkable()) return null;
//        ServerSideTransmitSocket tSocket = null;
//        while ((!unworkable()) && (tSocket == null || tSocket.isClosed() || !tSocket.isAlive())) {
//            if (SPARE_TRANSMIT_SOCKETS.isEmpty()) {
//                ServerSideTransmitSocket newTSocket = new ServerSideTransmitSocket(new Socket());
//                try {
//                    tSocket.getSocket().setReuseAddress(true);
//                    tSocket.getSocket().connect(new InetSocketAddress(remoteServerProperties.getHost(), remoteServerProperties.getPort()));
//                } catch (IOException e) {
//                    logError("Create ServerSideTransmitSocket failed: {}", e.getMessage());
//                    tSocket.closeUnchecked();
//                }
//                SPARE_TRANSMIT_SOCKETS.put(tSocket.getLocalPort(), tSocket);
//            }
//
//            tSocket = SPARE_TRANSMIT_SOCKETS.poll();
//        }
//
//        if (!bindServerSideSocket(tSocket)) {
//            returnTransmitSocket(tSocket);
//            return null;
//        }
//
//        return tSocket;
//    }

    // 归还Socket
    private void returnTransmitSocket(ServerSideTransmitSocket tSocket) {
        BUSY_TRANSMIT_SOCKETS.remove(tSocket);

        if (unworkable() || !tSocket.isAlive()) {
            tSocket.closeUnchecked();
            return;
        }

        if (tSocket != null && !tSocket.isClosed() && tSocket.isAlive()) {
            if (log.isDebugEnabled()) log.debug("ProxySideTransmitSocket(port: {}) recircled", tSocket.getLocalPort());
            SPARE_TRANSMIT_SOCKETS.put(tSocket.getLocalPort(), tSocket);
        }
    }

    private boolean submitSS2PSTransmitTask(CommonSocket ssSocket, ServerSideTransmitSocket psSocket) {
        try {
            transmitPool.submit(() -> {
                if (log.isDebugEnabled()) log.debug("ProxySideTransmitSocket-SS2PS(port: {}) start transmit in (port: {}; remote ip: {})", ssSocket.getLocalPort(), psSocket.getLocalPort(), psSocket.getRemoteSocketAddress().toString());
                while (!ssSocket.isClosed() && !psSocket.isClosed()) {
                    byte[] buffer = null;
                    try {
                        buffer = ssSocket.read();
                    } catch (IOException e) {
                        log.info("ServerSideSocket-SS2PS(port: {}) Connection interrupt; (Error: {})", ssSocket.getLocalPort(), e.getMessage());
                        break;
                    }

                    if (buffer == null) {
                        break;
                    }

                    if (!psSocket.writeUnchecked(buffer) && !unworkable()) {
                        if (!psSocket.isPositiveClosed()) logError("ProxySideTransmitSocket-SS2PS(port: {}) Connection interrupt;", psSocket.getLocalPort());
                        break;
                    }
                }

                ssSocket.closeUnchecked();
                psSocket.writeUnchecked(ProxyServerCommunicationServiceImpl.SERVER_CLOSED);
                log.info("ServerSideSocket-SS2PS(port: {}) Connection closed", ssSocket.getLocalPort());
            });
        }catch (RejectedExecutionException e) {
            return false;
        }
        return true;
    }

    private boolean submitPS2SSTransmitTask(ServerSideTransmitSocket psSocket, CommonSocket ssSocket) {
        try {
            transmitPool.submit(() -> {
                while (!ssSocket.isClosed() && !psSocket.isClosed()) {
                    byte[] buffer = null;
                    try {
                        buffer = psSocket.read();
                    } catch (IOException e) {
                        if (!psSocket.isPositiveClosed()) logError("ProxySideTransmitSocket-PS2SS(port: {}) Connection interrupt; (Error: {})",  psSocket.getLocalPort(), e.getMessage());
                        break;
                    }

                    if (buffer == null || bytesToString(buffer).equals(ProxyServerCommunicationServiceImpl.CLIENT_CLOSED)) {
                        break;
                    }

                    try {
                        ssSocket.write(buffer);
                    } catch (IOException e) {
                        if (log.isDebugEnabled())
                            log.debug("ServerSideSocket-PS2C(port: {}) Closed", ssSocket.getLocalPort());
                        break;
                    }
                }

                ssSocket.closeUnchecked();
                returnTransmitSocket(psSocket);
            });
        }catch (RejectedExecutionException e) {
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
