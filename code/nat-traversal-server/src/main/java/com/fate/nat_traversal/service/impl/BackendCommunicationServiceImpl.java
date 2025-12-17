package com.fate.nat_traversal.service.impl;

import com.fate.nat_traversal.config.TurnProperties;
import com.fate.nat_traversal.enums.WorkingStatusEnum;
import com.fate.nat_traversal.model.Backend;
import com.fate.nat_traversal.service.BackendCommunicationService;
import com.fate.nat_traversal.service.BackendTransmitService;
import com.fate.nat_traversal.service.TaskSchedulerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * @author: Fate
 * @date: 2025/12/6 13:00
 *
 * PS（被代理）端点<---->服务器TURN端自拟协议:
 *  编码格式 UTF-8；“”内小写单词为变量名
 *  1. 服务端点注册
 *      服务端点与服务器端口进行socket连接，并立刻发送“Register PS”，服务器返回“Register PS Success session:port”，完成通信socket、代理端口注册
 *  2. 服务端点定时向服务器端发送 “Ping:session” 心跳请求，服务器返回“Pong”
 *  3. 服务端点增加透传socket
 *      服务器端向服务端点发送“Require Socket:1”，服务端点立刻新建socket连接服务器端口，发送“Register Transmit Socket Session:session;;port:port”(第二个port为变量)，注册文服务端点透传socket
 *  4. 客户端连接后，通过透传socket发送“Client Connection:session”，正式建立连接。
 *  5. 客户端关闭，则透过传socket，返回“Client Closed”消息，防止PS端读阻塞；同理被代理的服务socket关闭，返回"Server Closed"消息，防止代理端读阻塞；
 *  6. 被代理端点关闭时， 发送“PS Closed”
 *
 */
@Slf4j
@Service
public class BackendCommunicationServiceImpl implements BackendCommunicationService {

    // 注册表
    // 被代理端口-session
    private static final Map<Integer, String> PORT_REFLECTION = new ConcurrentHashMap<>();
    // session-Backend
    private static final Map<String, Backend> REGISTER_INFO = new ConcurrentHashMap<>();

    // 黑名单
    private static final Set<Backend> BLACK_LIST = ConcurrentHashMap.newKeySet();

    // 通信线程池
    private ThreadPoolExecutor communicationPool;

    // 运行状态
    private WorkingStatusEnum workingStatus = WorkingStatusEnum.INIT;

    private Consumer<Integer> closeCallback = null;

    @Autowired
    private TurnProperties turnProperties;

    @Autowired
    private TaskSchedulerService taskScheduler;

    @Autowired
    private BackendTransmitService transmitService;

    @PostConstruct
    private void init() {
        communicationPool = new ThreadPoolExecutor(
                turnProperties.getMaxServerProxy()/2,
                turnProperties.getMaxServerProxy(),
                5,
                TimeUnit.MINUTES,
                new LinkedBlockingDeque<>(turnProperties.getMaxServerProxy()),
                new ThreadPoolExecutor.AbortPolicy()
                );


        // 定时清理
        taskScheduler.submit(() -> {
            List<Integer> toRemove = new ArrayList<>();
            PORT_REFLECTION.forEach((port, session) -> {
                Backend ps = REGISTER_INFO.get(session);
                if (ps.isClosed() || !ps.isOnline()) {
                    toRemove.add(port);
                }
            });

            if (log.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                toRemove.forEach(port -> {
                    sb.append("proxy port: " + port + "; session: " + PORT_REFLECTION.get(port) + "、");
                });
                sb.deleteCharAt(sb.length()-1);

                log.debug("BackendCommunicationService remove useless Backend: ({})", sb.toString());
            }

            toRemove.forEach(this::close);
        }, 5, 5, TimeUnit.MINUTES);

        // 更新状态
        workingStatus = WorkingStatusEnum.WORKING;
    }

    @Override
    public boolean register(Backend backend, int proxyPort) {
        if (unworkable()) return false;
        if (PORT_REFLECTION.size() >= turnProperties.getMaxServerProxy()) return false;
        if (!doRegisterFilter(backend.getCommunicationSocket().getSOCKET())) return false;

        PORT_REFLECTION.put(proxyPort, backend.getSession());
        REGISTER_INFO.put(backend.getSession(), backend);

        // 启动通信线程
        communicationPool.submit(() -> {
            int readErrTimes = 0;

            while (!backend.isClosed() && backend.isOnline()) {
                String msg = null;
                try {
                    msg = backend.read();
                } catch (IOException e) {
                    readErrTimes++;
                    logError("Backend(session: {}) Read Error(Exception: {})", backend.getSession(), e.getMessage());

                    if (readErrTimes > 5) {
                        backend.close();
                        break;
                    }

                    continue;
                }

                if (msg == null) break;

                // 处理不同信息
                if (msg.startsWith(PING)) {
                    String session = msg.substring(PING.length());
                    Backend ps = REGISTER_INFO.get(session);
                    if (ps == null || ps.communicationTooFrequent()) {
                        if (ps != null) {
                            ps.close();
                            if (ps.communicationTooFrequent()) {
                                log.warn("Backend(session: {}) communication too frequent, maybe Attack!", session);
                            }
                        }
                        REGISTER_INFO.remove(session);
                    } else {
                        ps.refreshOnlineTime();
                        ps.send(PONG);
                    }
                } else {
                    if (log.isDebugEnabled())
                        log.debug("Backend(session: {}) Receive message: {}", backend.getSession(), msg);

                    // todo 处理不同信息

                    if (msg.equals(PS_CLOSED)) {
                        break;
                    }
                }
            }

            close(proxyPort);
        });

        log.info("Backend(session: {}) Register Success", backend.getSession());
        return true;
    }

    @Override
    public void requireSocket(String session, int num) {
        if (unworkable()) return;
        REGISTER_INFO.get(session).send(REQUIRE_SOCKET+num);
    }

    @Override
    public Backend getBackend(int proxyPort) {
        return REGISTER_INFO.get(PORT_REFLECTION.get(proxyPort));
    }

    @Override
    public void close(int port) {
        if (workingStatus == WorkingStatusEnum.CLOSED) return;

        String session = PORT_REFLECTION.get(port);
        transmitService.close(session);
        PORT_REFLECTION.remove(port);
        Backend backend = REGISTER_INFO.get(session);

        if (backend != null) {
            backend.close();
            REGISTER_INFO.remove(backend.getSession());
        }

        if (closeCallback != null) closeCallback.accept(port);
        log.info("Backend(session: {}) Closed", session);
    }

    @Override
    public void shutdown() {
        if (workingStatus == WorkingStatusEnum.CLOSED || workingStatus == WorkingStatusEnum.CLOSING) return;

        log.info("Shutdown BackendCommunicationService...");
        List<Integer> ports = new ArrayList<>();
        PORT_REFLECTION.forEach((port, session) -> {
            ports.add(port);
        });
        ports.forEach(this::close);

        workingStatus = WorkingStatusEnum.CLOSED;
        log.info("Shutdown BackendCommunicationService Gracefully");
    }

    @Override
    public boolean send(String session, String message) {
        if (unworkable()) return false;
        if (log.isDebugEnabled()) log.debug(" Send message to Backend(session: {}): {}", session, message);
        return REGISTER_INFO.get(session).send(message);
    }

    @Override
    public void closeCallback(Consumer<Integer> consumer) {
        this.closeCallback = consumer;
    }

    // 注册过滤器
    private boolean doRegisterFilter(Socket socket) {
        if (BLACK_LIST.contains(socket)) {
            return false;
        }
        return true;
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
