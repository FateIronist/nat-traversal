package com.fate.nat_traversal;


import com.fate.nat_traversal.service.ProxyServerCommunicationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * @author fate
 * @date 2025/12/12 20:30
 *
 * 客户端
 */
@Slf4j
@Component
public class Client {

    @Autowired
    private ProxyServerCommunicationService proxyServerCommunicationService;

    @PostConstruct
    public void start() {
        if (!proxyServerCommunicationService.start()) {
            log.error("Client Start Failed!");
        }
    }

}
