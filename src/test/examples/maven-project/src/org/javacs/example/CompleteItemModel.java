package org.javacs.example;

import java.util.List;

public class CompleteItemModel {
    public String getSku() { return ""; }
    public int getQuantity() { return 0; }
    public CompleteItemModel getParent() { return this; }

    public static List<CompleteItemModel> fetchItems() {
        return List.of();
    }
}
