import java.util.List;
import java.util.Map;

public class Collections {
    public static void main(String[] args) {
        var xs = List.of(1, 2);
        var ys = Map.of(1, "one", 2, "two");
        System.out.println(xs + ", " + ys);
    }
}