package com.fate.nat_traversal.util;

@FunctionalInterface
public interface Function2 <A, B, C>{
    C apply(A a, B b);
}
