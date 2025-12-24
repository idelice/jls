package org.javacs.example;

public class ReferenceConstructor {
    public static ReferenceConstructor instance = new ReferenceConstructor();
    
    public ReferenceConstructor() { }

    {
        new ReferenceConstructor();
    }
}