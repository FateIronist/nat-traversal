package com.fate.nat_traversal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * @author fate
 * @date 2025/12/04 15:30
 *
 * 透传服务配置
 */

@Data
@ConfigurationProperties(prefix = "nat-traversal.turn")
@Component
public class TurnProperties {

    private Integer regPort;

    private Integer maxServerProxy;

    private Integer maxClientConnection;
}
