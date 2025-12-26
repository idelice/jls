package org.javacs;

final class LombokSupport {
    private LombokSupport() {}

    static String lombokPath() {
        var path = System.getProperty("lombokPath");
        if (path != null && !path.isBlank()) {
            return path;
        }
        return null;
    }

    static boolean isEnabled() {
        return lombokPath() != null;
    }
}
