package org.javacs.example;

public class ProcessorImpl implements IProc {
  @Override
  public IProc process(String foo, String bar) {
    return this;
  }
}
