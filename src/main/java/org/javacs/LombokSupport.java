package org.javacs;

final class LombokSupport {
    private LombokSupport() {}

    static boolean isEnabled() {
        return System.getProperty("org.javacs.lombokPath") != null;
    }
}
