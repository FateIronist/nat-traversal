package com.fate.nat_traversal.util;

@FunctionalInterface
public interface Consumer2<A, B> {
    void accept(A a, B b);
}
