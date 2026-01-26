package org.javacs.example;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString(callSuper = true)
@AllArgsConstructor
public class AsyncEvent {
  private Map<String, String> headers;
  private Map<String, String> mymap;
  private String foo;
  private String asd;
}
