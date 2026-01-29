# Java Language Server for Neovim

A Java [Language Server Protocol](https://github.com/Microsoft/vscode-languageserver-protocol) implementation built on the [Java compiler API](https://docs.oracle.com/javase/10/docs/api/jdk.compiler-summary.html), optimized for Neovim.

This is a fork and continuation of [georgewfraser/java-language-server](https://github.com/georgewfraser/java-language-server). I'm deeply grateful for the original author's amazing work and am continuing development with a focus solely on Neovim LSP integration.

## Features

- **Autocomplete** - Symbols, members, imports with auto-completion
- **Go-to-definition** - Navigate to class definitions in workspace and JARs (including private repositories)
- **Hover information** - Type information and Javadoc documentation with proper formatting
- **Find references** - Find all usages of symbols
- **Diagnostics** - Real-time linting and error reporting
- **Signature help** - Parameter information for method calls
- **Lombok support** - Synthetic members from Lombok annotations (@Data, @Getter, @Setter, @Builder, @AllArgsConstructor, @Slf4j, etc.)
  - Nested Lombok type completion (e.g., `obj.getLombokField().get` shows all members)
  - **Note**: Lombok support covers standard use cases but cannot handle all edge cases due to Lombok's advanced metaprogramming features
- **Record support** - Full IDE support for Java records (16+)
  - Find references on record parameters and accessor methods
  - Go-to-definition on record accessor calls navigates to parameter definition
- **Automatic Java version detection** - Detects your project's target Java version from build files and compiles accordingly
  - Reads `<java.version>`, `<maven.compiler.source>` from `pom.xml`
  - Reads `sourceCompatibility` from `build.gradle` or `gradle.properties`
  - Uses `--release` flag to enforce correct Java semantics (8, 11, 17, 21, etc.)
  - No manual configuration needed - works automatically
- **JAR navigation** - Go-to-definition works on dependency JARs with source files (public repositories)

## Installation

### Prerequisites

- Java 20+
- Maven
- npm
- protobuf

### Build

```bash
./scripts/build.sh
```

The language server will be built to `dist/lang_server_{linux|mac|windows}.sh`

## Setup with Neovim

### Option 1: Using [nvim-jls](https://github.com/user/nvim-jls) (Recommended)

Install the Neovim client plugin and follow its documentation.

### Option 2: Manual setup with lspconfig

Add to your `init.lua`:

```lua
local lspconfig = require('lspconfig')

lspconfig.jls.setup({
    cmd = { '<path-to-jls>/dist/lang_server_linux.sh' }, -- or mac/windows
    root_dir = lspconfig.util.root_pattern('pom.xml', 'build.gradle', '.git'),
})
```

## Configuration

### Automatic Java Version Detection

JLS automatically detects your project's target Java version from build files and compiles with the correct Java semantics. **No configuration needed.**

**How it works:**
1. Detects Java version from your project's build files:
   - Maven: `<java.version>`, `<maven.compiler.source>`, `<maven.compiler.target>`, or `<source>` in `pom.xml`
   - Gradle: `sourceCompatibility` or `targetCompatibility` in `build.gradle` or `gradle.properties`
2. Passes `--release <version>` to the Java compiler
3. Enforces correct language features and standard library APIs for your target version

**Example:** If your `pom.xml` has `<java.version>17</java.version>`, JLS compiles with `--release 17`, ensuring:
- Only Java 17 language features are allowed
- Only Java 17 standard library APIs are available
- Java 18+ features are rejected with compile errors

**Supported versions:** Any version up to the bundled JDK version (currently Java 21)

**Note:** If your project targets a Java version higher than the bundled JDK (e.g., Java 22+ with bundled Java 21), the `--release` flag will be skipped and compilation will use the bundled JDK's version semantics. To properly support newer Java versions, update the bundled JDK by running `./scripts/download_jdk.sh` or rebuilding with a newer JDK.

### Dependency Resolution

The language server will automatically detect dependencies from:
- Maven (`pom.xml`)
- Gradle (`build.gradle`)
- Bazel (`BUILD` files)

### Manual dependency specification

If automatic detection doesn't work, you can specify dependencies in your project root in a `.java-language-server.json` file:

```json
{
    "externalDependencies": [
        "junit:junit:jar:4.12:test",
        "junit:junit:4.12"
    ]
}
```

Or specify them directly:

```json
{
    "classPath": [
        "lib/some-dependency.jar"
    ],
    "docPath": [
        "lib/some-dependency-sources.jar"
    ]
}
```

### Known Limitation: Private Repository Support

Private Maven repositories are currently not supported. Go-to-definition on dependencies from private repositories will not work. Only public repositories (Maven Central, etc.) are supported at this time.

**Technical Details**: The Maven subprocess launched by JLS to resolve dependencies does not inherit the parent process's environment variables. This means:
- Maven credentials stored in `~/.m2/settings.xml` are not available to the subprocess
- The subprocess cannot authenticate with private repositories
- `mvn dependency:sources` fails silently when encountering private repository artifacts
- Source JARs for private dependencies are never downloaded, making go-to-definition impossible

**Workaround**: For private dependencies, you can manually download source JARs and configure them:

```json
{
    "classPath": [
        "/path/to/private-dep.jar"
    ],
    "docPath": [
        "/path/to/private-dep-sources.jar"
    ]
}
```


## Usage

The language server provides autocomplete and other features using:
- `.java` files in your workspace
- Java platform classes
- External dependencies from Maven, Gradle, Bazel, or manual configuration

## Design

The Java language server uses the [Java compiler API](https://docs.oracle.com/javase/10/docs/api/jdk.compiler-summary.html) to implement language features like linting, autocomplete, and smart navigation, and the [language server protocol](https://github.com/Microsoft/vscode-languageserver-protocol) to communicate with text editors like Neovim.

### Incremental updates

The Java compiler API provides incremental compilation at the level of files: you can create a long-lived instance of the Java compiler, and as the user edits, you only need to recompile files that have changed. The Java language server optimizes this further by *focusing* compilation on the region of interest by erasing irrelevant code.

## Recent Enhancements

### Automatic Java Version Detection

The language server now automatically detects your project's target Java version from build files (`pom.xml`, `build.gradle`, etc.) and uses the `--release` flag to ensure correct compilation semantics. This means:

- Java 17 projects reject Java 18+ features automatically
- No manual runtime configuration needed
- Compilation behavior matches Maven/Gradle exactly
- Works with any Java version up to the bundled JDK version (currently Java 21)

**Limitation:** Projects targeting Java versions newer than the bundled JDK (e.g., Java 22+) will compile with the bundled JDK's semantics instead. The `--release` flag cannot target versions higher than the compiler itself.

Previously, the server required manual runtime configuration and didn't enforce version constraints. Now it's completely automatic and accurate (within the bundled JDK's capabilities).

### Java Records Support (Java 16+)

Full IDE support for Java records, including:

- **Find References**: Click on a record parameter (e.g., `foo` in `record MyRec(String foo)`) and use "Find References" to locate all uses of the generated accessor method
- **Go-to-Definition**: Click on a record accessor call (e.g., `p.foo()`) and navigate directly to the parameter definition in the record declaration

### Nested Lombok Type Completion

Improved completion for nested Lombok-generated types. When you call a Lombok-generated getter that returns another Lombok type, completion now correctly shows all members:

```java
@Data
class Container {
    private Item item;  // Lombok generates getItem()
}

@Data
class Item {
    private String value;  // Lombok generates getValue()
}

// Completion on container.getItem(). now shows: getValue(), setValue(), etc.
container.getItem().get
```

This works through semantic type resolution using the Java compiler API's type system, ensuring 100% accuracy with zero performance impact.

### Limitations of Lombok Support

Lombok support is designed to cover the most common use cases (@Data, @Getter, @Setter, @Builder, @AllArgsConstructor, @Slf4j, etc.). However, due to Lombok's advanced metaprogramming and AST manipulation capabilities, not all edge cases are supported:

- **Custom annotation handlers**: Lombok plugins with custom annotations may not be recognized
- **Complex builder patterns**: Some advanced @Builder configurations with custom methods
- **Conditional field generation**: Annotations with conditions that affect which fields are generated
- **Nested/inherited Lombok classes**: Complex inheritance hierarchies with mixed Lombok and non-Lombok classes
- **Record-specific Lombok annotations**: Future Lombok features designed specifically for records

## Contributing

### Installing

Before installing locally, you need to install prerequisites: maven, protobuf.

You also need to have Java 20+ installed. Point the `JAVA_HOME` environment variable to it.

Assuming you have these prerequisites, you should be able to install locally using:

```bash
./scripts/build.sh
```

## Logs

The Java service process will output a log file to stderr.
