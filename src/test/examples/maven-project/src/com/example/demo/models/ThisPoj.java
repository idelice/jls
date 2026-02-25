package com.example.demo.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class ThisPoj {
    @JsonProperty("Aasd")
    private String foo;

    public void something() {
        this.setFoo("asd");
    }
}
