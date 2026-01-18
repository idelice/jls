package org.javacs.example;

import lombok.Getter;
import lombok.ToString;

/**
 * Example with @ToString annotation.
 * Tests toString generation.
 */
@Getter
@ToString
public class LombokToStringExample {
    private String firstName;
    private String lastName;
    private int age;
}
