package org.javacs.lsp;

public class WorkDoneProgressParams {
    public Object token;
    public Object value;

    public WorkDoneProgressParams(Object token, Object value) {
        this.token = token;
        this.value = value;
    }

    public void setToken(Object token) {
        this.token = token;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
