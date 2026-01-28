package org.javacs.example;

public interface Bio {
  <T> T mapDo(String foo, Class<T> type);
}
