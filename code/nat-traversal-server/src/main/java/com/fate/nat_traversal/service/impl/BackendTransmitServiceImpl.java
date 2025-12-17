package com.fate.nat_traversal.service.impl;

import com.fate.nat_traversal.config.TurnProperties;
import com.fate.nat_traversal.enums.WorkingStatusEnum;
import com.fate.nat_traversal.model.ClientSocket;
import com.fate.nat_traversal.model.BackendTransmitSocket;
import com.fate.nat_traversal.service.BackendCommunicationService;
import com.fate.nat_traversal.service.BackendTransmitService;
import com.fate.nat_traversal.service.TaskSchedulerService;
import com.fate.nat_traversal.util.Consumer2;
import com.fate.nat_traversal.util.Function2;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author fate
 * @date 2025/12/9 21:30
 *
 * 被代理端点透传服务
 */

@Slf4j
@Service
public class BackendTransmitServiceImpl implements BackendTransmitService {

    // Socket池(维护俩列表方便空闲资源清理、优雅关闭)
    private static final Map<String, LinkedBlockingQueue<BackendTransmitSocket>> SPARE_TRANSMIT_SOCKETS = new ConcurrentHashMap<>();
    private static final Map<String, LinkedList<BackendTransmitSocket>> BUSY_TRANSMIT_SOCKETS = new ConcurrentHashMap<>();

    // 客户端数据传输线程池
    private ThreadPoolExecutor transmitPool;

    // 运行状态
    private WorkingStatusEnum workingStatus = WorkingStatusEnum.INIT;

    private Consumer2<String, Integer> requireSocketCallback;

    private Function2<String, Integer, Boolean> awareSocketCallback;


    @Autowired
    private TurnProperties turnProperties;

    @Autowired
    private TaskSchedulerService taskScheduler;

    @PostConstruct
    private void init() {
        transmitPool= new ThreadPoolExecutor(
                turnProperties.getMaxClientConnection()*2/3,
                turnProperties.getMaxClientConnection()*2,
                5,
                TimeUnit.MINUTES,
                new LinkedBlockingDeque<>(turnProperties.getMaxClientConnection()*2),
                new ThreadPoolExecutor.AbortPolicy()
        );

        // 定时清理
        taskScheduler.submit(() -> {
            if (unworkable()) return;

            SPARE_TRANSMIT_SOCKETS.forEach((session, socketQueue) -> {
                List<BackendTransmitSocket> toRemove = new ArrayList<>();
                socketQueue.forEach(socket -> {
                    if (!socket.isAlive()) {
                        socket.closeUnchecked();
                        toRemove.add(socket);
                    } else if (socket.isClosed()) {
                        toRemove.add(socket);
                    }

                });


                if (log.isDebugEnabled() && toRemove.size() > 0) {
                    String[] ips = new String[toRemove.size()];

                    for (int i = 0; i < toRemove.size(); ++i) {
                        ips[i] = toRemove.get(i).getRemoteSocketAddress().toString();
                    }

                    log.debug("BackendTransmitService remove spare transmit socket(session: {}) ip: {}, size: {}", session, String.join("、", ips), toRemove.size());
                }
                socketQueue.removeAll(toRemove);
            });
        }, 5, 5, TimeUnit.MINUTES);

        // 更新状态
        workingStatus = WorkingStatusEnum.WORKING;
    }

    @Override
    public boolean registerTransmit(String session, int originalPort, Socket socket) {
        if (unworkable()) return false;

        BackendTransmitSocket tSocket = new BackendTransmitSocket(socket);
        tSocket.setBackendSession(session);
        tSocket.setOriginalPort(originalPort);

        // 懒汉单例 初始化
        if (SPARE_TRANSMIT_SOCKETS.get(session) == null) {
            synchronized (SPARE_TRANSMIT_SOCKETS) {
                if (SPARE_TRANSMIT_SOCKETS.get(session) == null) {
                    SPARE_TRANSMIT_SOCKETS.put(session, new LinkedBlockingQueue<BackendTransmitSocket>());
                    BUSY_TRANSMIT_SOCKETS.put(session, new LinkedList<BackendTransmitSocket>());
                }
            }
        }

        log.info("BackendTransmitSocket(PS session: {}; socket ip: {}; originalPort: {}) Registered", session, socket.getRemoteSocketAddress().toString(), tSocket.getOriginalPort());
        SPARE_TRANSMIT_SOCKETS.get(session).add(tSocket);

        return true;
    }

    @Override
    public boolean transmitClient(ClientSocket cSocket) {
        if (unworkable()) return false;
        BackendTransmitSocket tSocket = getTransmitSocket(cSocket.getBackendSession());

       if (tSocket == null) {
           return false;
       }

       return submitC2PSTransmitTask(cSocket, tSocket) && submitPS2CTransmitTask(tSocket, cSocket);
    }

    @Override
    public void close(String session) {
        if (workingStatus == WorkingStatusEnum.CLOSED) return;

        LinkedBlockingQueue<BackendTransmitSocket> spareSocketQueue = SPARE_TRANSMIT_SOCKETS.get(session);
        if (spareSocketQueue != null) {
            spareSocketQueue.forEach(tSocket -> tSocket.closeUnchecked());
            SPARE_TRANSMIT_SOCKETS.remove(session);
        }

        LinkedList<BackendTransmitSocket> busySocketQueue = BUSY_TRANSMIT_SOCKETS.get(session);
        if (busySocketQueue != null) {
            busySocketQueue.forEach(tSocket -> tSocket.closeUnchecked());
            BUSY_TRANSMIT_SOCKETS.remove(session);
        }

        log.info("Close BackendTransmitService(session: {})", session);
    }

    @Override
    public void shutdown() {
        if (workingStatus == WorkingStatusEnum.CLOSED || workingStatus == WorkingStatusEnum.CLOSING) return;

        log.info("Shutdown BackendTransmitService...");
        workingStatus = WorkingStatusEnum.CLOSING;

        SPARE_TRANSMIT_SOCKETS.forEach((session, socketQueue) -> {
            socketQueue.forEach(tSocket -> tSocket.closeUnchecked());
        });
        SPARE_TRANSMIT_SOCKETS.clear();

        BUSY_TRANSMIT_SOCKETS.forEach((session, socketQueue) -> {
            socketQueue.forEach(tSocket -> tSocket.closeUnchecked());
        });
        BUSY_TRANSMIT_SOCKETS.clear();

        transmitPool.shutdown();

        workingStatus = WorkingStatusEnum.CLOSED;
        log.info("Shutdown BackendTransmitService gracefully");
    }

    @Override
    public void requireSocketCallback(Consumer2<String, Integer> consumer) {
        this.requireSocketCallback = consumer;
    }

    @Override
    public void awareSocketCallback(Function2<String, Integer, Boolean> consumer) {
        this.awareSocketCallback = consumer;
    }

    private void requireSocket(String session, int num) {
        if (unworkable()) return;
        requireSocketCallback.accept(session, num);
    }

    private boolean awareSocket(String session, int port) {
        if (unworkable()) return false;
        return awareSocketCallback.apply(session, port);
    }

    // 获取Socket
    private BackendTransmitSocket getTransmitSocket(String session) {
        if (unworkable()) return null;

        int retry = 0;
        BackendTransmitSocket tSocket = null;
        while (retry++ < 3 && (tSocket == null || (tSocket != null && tSocket.isClosed())) && !unworkable()) {

            if (SPARE_TRANSMIT_SOCKETS.get(session) == null) {
                synchronized (SPARE_TRANSMIT_SOCKETS) {
                    if (SPARE_TRANSMIT_SOCKETS.get(session) == null) {
                        SPARE_TRANSMIT_SOCKETS.put(session, new LinkedBlockingQueue<BackendTransmitSocket>());
                        BUSY_TRANSMIT_SOCKETS.put(session, new LinkedList<BackendTransmitSocket>());
                    }
                }
            }

            if (SPARE_TRANSMIT_SOCKETS.get(session) != null && SPARE_TRANSMIT_SOCKETS.get(session).isEmpty()) {
                requireSocket(session, 1);
                try {
                    tSocket = SPARE_TRANSMIT_SOCKETS.get(session).poll(500*retry, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {}
                continue;
            } else {
                tSocket = SPARE_TRANSMIT_SOCKETS.get(session).poll();
            }

            if (tSocket != null && (tSocket.isClosed() || !tSocket.isAlive())) {
                retry--;
                tSocket = null;
            }
        }

        if (tSocket == null && retry == 3) {
            log.warn("BackendTransmitSocket(session: {}) not available!", session);
        }

        if (!awareSocket(session, tSocket.getOriginalPort())) {
            tSocket.closeUnchecked();
        }

        tSocket.writeUnchecked(BackendCommunicationService.TSPING);

        try {
            if (!tSocket.readString().equals(BackendCommunicationService.TSPONG)) {
                return getTransmitSocket(session);
            }
        } catch (IOException e) {
            logError("Pong not received Error");
            e.printStackTrace();
            return getTransmitSocket(session);
        }

        if (tSocket != null) {
            BUSY_TRANSMIT_SOCKETS.get(session).add(tSocket);
        }

        return tSocket;
    }

    // 归还Socket
    private void returnTransmitSocket(BackendTransmitSocket tSocket) {
        BUSY_TRANSMIT_SOCKETS.get(tSocket.getBackendSession()).remove(tSocket);

        if (unworkable() || !tSocket.isAlive()) {
            tSocket.closeUnchecked();
            return;
        }

        if (tSocket != null && !tSocket.isClosed() && tSocket.isAlive()) {
            if (log.isDebugEnabled()) log.debug("BackendTransmitSocket(session: {}, ip: {}) recircled", tSocket.getBackendSession(), tSocket.getRemoteSocketAddress().toString());
            SPARE_TRANSMIT_SOCKETS.get(tSocket.getBackendSession()).add(tSocket);
        }
    }

    private boolean submitC2PSTransmitTask(ClientSocket cSocket, BackendTransmitSocket psSocket) {
        try {
            transmitPool.submit(() -> {
                if (log.isDebugEnabled()) log.debug("ClientSocket-C2PS(session: {}; ip: {}) Start transmit in (port: {}; remote ip: {})", cSocket.getSession(), cSocket.getRemoteSocketAddress().toString(), psSocket.getLocalPort(), psSocket.getRemoteSocketAddress().toString());
                while (!cSocket.isClosed() && !psSocket.isClosed()) {
                    byte[] buffer = null;
                    try {
                        buffer = cSocket.read();
                    } catch (IOException e) {
                        log.info("ClientSocket-C2PS(session: {}; ip: {}) Connection interrupt; (Error: {})", cSocket.getSession(), cSocket.getRemoteSocketAddress().toString(), e.getMessage());
                        break;
                    }

                    if (log.isDebugEnabled()) {
                        String msg = bytesToString(buffer);
                        log.debug("ClientSocket-C2PS(session: {}; ip: {}) Received: \n {}", cSocket.getSession(), cSocket.getRemoteSocketAddress().toString(), msg);
                    }


                    if (buffer == null) {
                        break;
                    }

                    if (!psSocket.writeUnchecked(buffer) && !unworkable()) {
                        if (!psSocket.isPositiveClosed())
                            logError("BackendTransmitSocket-C2PS(session: {}; ip: {}) Connection interrupt;", psSocket.getBackendSession(), psSocket.getRemoteSocketAddress().toString());
                        break;
                    }
                }

                cSocket.closeUnchecked();
                psSocket.writeUnchecked(BackendCommunicationService.CLIENT_CLOSED);
                returnTransmitSocket(psSocket);
                log.info("ClientSocket-C2PS(session: {}; ip: {}) Connection closed", cSocket.getSession(), cSocket.getRemoteSocketAddress().toString());
            });
        }catch (RejectedExecutionException e) {
            return false;
        }
        return true;
    }

    private boolean submitPS2CTransmitTask(BackendTransmitSocket psSocket, ClientSocket cSocket) {
        try {
            transmitPool.submit(() -> {
              while (!cSocket.isClosed() && !psSocket.isClosed()) {
                    byte[] buffer = null;
                    try {
                        buffer = psSocket.read();
                    } catch (IOException e) {
                        if (!psSocket.isPositiveClosed())
                            logError("BackendTransmitSocket-PS2C(session: {}; ip: {}) Connection interrupt; (Error: {})", psSocket.getBackendSession(), psSocket.getRemoteSocketAddress().toString(), e.getMessage());
                        break;
                    }

                    if (log.isDebugEnabled()) {
                        String msg = bytesToString(buffer);
                        log.debug("ClientSocket-PS2C(session: {}; ip: {}) Returned: \n {}", cSocket.getSession(), cSocket.getRemoteSocketAddress().toString(), msg);
                    }

                    if (bytesToString(buffer).equals(BackendCommunicationService.SERVER_CLOSED)) {
                        break;
                    }

                    try {
                        cSocket.write(buffer);
                    } catch (IOException e) {
                        if (log.isDebugEnabled())
                            log.debug("ClientSocket-PS2C(session: {}; ip: {}) Closed", cSocket.getSession(), cSocket.getRemoteSocketAddress().toString());
                        break;
                    }
                }

                cSocket.closeUnchecked();
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
