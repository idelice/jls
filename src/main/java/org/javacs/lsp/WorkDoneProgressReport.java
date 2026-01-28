package org.javacs.lsp;

public class WorkDoneProgressReport {
    public String kind = "report";
    public String message;

    public WorkDoneProgressReport() {}

    public WorkDoneProgressReport(String message) {
        this.message = message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
