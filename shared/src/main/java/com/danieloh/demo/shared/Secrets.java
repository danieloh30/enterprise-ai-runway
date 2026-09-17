package com.danieloh.demo.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Secrets {
    private Secrets() {}
    public static boolean matches(String supplied, String expected) {
        return supplied != null && expected != null && expected.length() >= 24
            && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
