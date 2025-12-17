package com.fate.nat_traversal.enums;

public enum WorkingStatusEnum {
    INIT(0),
    WORKING(1),
    CLOSING(2),
    CLOSED(3);

    private int code;

    WorkingStatusEnum(int code) {
        this.code = code;
    }
}
