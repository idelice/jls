package org.javacs.lsp;

import com.google.gson.annotations.SerializedName;

public class WorkDoneProgressBegin {
    public String kind = "begin";
    public String title;

    public WorkDoneProgressBegin(String title) {
        this.title = title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
