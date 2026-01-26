package org.javacs.example;

import java.util.HashMap;
import java.util.Map;

public class AsyncEventUser {
  public void testAsyncEvent() {
    Map<String, String> headers = new HashMap<>();
    Map<String, String> mymap = new HashMap<>();
    String foo = "test";
    String asd = "value";

    // This should work - @AllArgsConstructor generates constructor with all fields
    var event = new AsyncEvent(headers, mymap, foo, asd);
  }
}
