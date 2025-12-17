package com.fate.nat_traversal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
public class RemoteServerProperties {

    private String protocol;

    private String host;

    private Integer port;
}
