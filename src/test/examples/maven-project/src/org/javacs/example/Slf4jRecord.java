package org.javacs.example;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public record Slf4jRecord(String foo) {
  public Slf4jRecord {
    // log field should be available from @Slf4j annotation
    log.info("Creating record with foo: {}", foo);
  }
}
