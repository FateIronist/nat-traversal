package com.fate.nat_traversal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "nat-traversal")
public class NatTraversalProperties {

    private ClientProperties client;

    private RemoteServerProperties server;
}
