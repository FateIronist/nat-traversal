package com.fate.nat_traversal.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author: Fate
 * @date: 2025/12/04 10:00
 * @description:
 */

@Controller
@RequestMapping("/api")
public class IpApi {

    // 返回请求者Ip信息
    @GetMapping(value = "/ip",produces = "application/json")
    @ResponseBody
    public RequestIpInfo getRequestIp(HttpServletRequest request) {
        return RequestIpInfo.builder()
                .addr(request.getRemoteAddr())
                .host(request.getRemoteHost())
                .port(request.getRemotePort())
                .build();
    }

    @Builder
    @Data
    private static class RequestIpInfo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String addr;
        private String host;
        private Integer port;
    }
}
