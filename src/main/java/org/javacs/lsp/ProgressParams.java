package org.javacs.lsp;

public class ProgressParams {
    public Object token;
    public ProgressValue value;

    public static class ProgressValue {
        public String kind; // "begin" | "report" | "end"
        public String title;
        public String message;
        public Integer percentage;
    }
}
