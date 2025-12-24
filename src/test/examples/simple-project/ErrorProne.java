import java.util.HashSet;
import java.util.Set;

class ErrorProne {
    void collectionIncompatibleType() {
        Set<Long> values = new HashSet<>();
        if (values.contains(42)) System.out.println("Impossible!");
    }
}