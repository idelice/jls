package org.javacs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BenchmarkParser {

    @State(Scope.Benchmark)
    public static class CompilerState {
        public Path file = Paths.get("src/main/java/org/javacs/JavaLanguageServer.java").normalize();
    }

    @Benchmark
    public void parse(CompilerState state) {
        Parser.parseFile(state.file);
    }

    public static void main(String[] args) {
        var state = new CompilerState();
        while (true) {
            Parser.parseFile(state.file);
        }
    }
}
