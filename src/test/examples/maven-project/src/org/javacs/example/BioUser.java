package org.javacs.example;

public class BioUser {
  private Bio bio;  // autowired in real code

  public void testBio() {
    // This should be a reference to Bio.mapDo
    var result = bio.mapDo("test", String.class);
  }
}
