package com.fate.nat_traversal.util;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;

/**
 * @author fate
 * @date 2025/12/06 20:30
 *
 * 端口获取服务
 */

public class PortUtil {
    private static final Integer MIN_FREE_PORT = 49152;
    private static final Integer MAX_FREE_PORT = 65535;
    private static final Random random = new Random();

    public static Integer getPort() {
        return getPort(MAX_FREE_PORT - MIN_FREE_PORT, MIN_FREE_PORT, MAX_FREE_PORT);
    }
    public static Integer getPort(Integer tryTimes) {
        return getPort(tryTimes, MIN_FREE_PORT, MAX_FREE_PORT);
    }

    public static Integer getPort(Integer tryTimes, Integer minPort, Integer maxPort) {
        Integer size = maxPort - minPort;
        for (int i = 0; i < tryTimes; ++i) {
            Integer port = MIN_FREE_PORT + (int)(size * random.nextDouble(0,1));
            ServerSocket socket = null;
            try {
                socket = new ServerSocket(port);
                socket.close();
                return port;
            } catch (IOException e) {
                continue;
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        continue;
                    }
                }
            }
        }

        return null;
    }
}
