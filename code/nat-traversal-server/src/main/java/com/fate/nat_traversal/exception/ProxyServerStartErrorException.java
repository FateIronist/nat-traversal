package com.fate.nat_traversal.exception;

public class ProxyServerStartErrorException extends RuntimeException {
    public ProxyServerStartErrorException() {
        super("ProxyServer start error");
    }
    public ProxyServerStartErrorException(String message) {
        super(message);
    }
}
