package org.javacs.rewrite;

public class TestRenameClassRef {
    private TestRenameClassOldName ref;
    
    public TestRenameClassRef() {
        ref = new TestRenameClassOldName("test");
    }
    
    public void useRef() {
        if (ref != null) {
            System.out.println(ref.getValue());
        }
    }
}
