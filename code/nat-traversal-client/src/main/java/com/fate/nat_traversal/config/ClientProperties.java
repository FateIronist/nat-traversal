package com.fate.nat_traversal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
public class ClientProperties {

    private Integer serverPort;

    private Integer maxConnection = 10;
}
