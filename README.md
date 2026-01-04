# JLS (Java Language Server) using the [Java compiler API](https://docs.oracle.com/javase/10/docs/api/jdk.compiler-summary.html)

This server is based on the original implementation at `https://github.com/georgewfraser/java-language-server`,
with updated functionality and ongoing improvements.

A Java language server based on v3.0 of the protocol and implemented using the Java compiler API.

## Enhancements in this fork

This fork keeps the original behavior while adding practical improvements for day-to-day work:

- Lombok support (annotation processing + `-javaagent` wiring).
- Faster startup and navigation with workspace caches.
- Persistent workspace index cache to speed up restarts.
- Parallel indexing for large projects.
- Smarter compile scoping to reduce work on main vs. test sources.
- Completion prioritizes already-imported types.
- Optional timing/debug logs via `~/.config/jls/logging.properties`.
- Unused import warnings in diagnostics.
- Inlay hints for parameter names (configurable).
- Configurable cache directory via init options.


## IMPORTANT
I may often introduce **breaking changes** until i'm satisfied with a stable version so prepare your horses for a wild ride


## First-run checklist

- Install a compatible JDK and verify `java -version`.
- (Optional) create `~/.config/jls/runtimes.json` for per-project runtime selection.
- (Optional) add `~/.config/jls/logging.properties` for timing logs.
- Build or download release artifacts so `dist/` exists.

## Installation (other editors)

JLS does not bundle a runtime. Install a compatible JDK and either add it to PATH, set `JAVA_HOME`, or configure `~/.config/jls/runtimes.json`.

### Neovim (recommended)

Use the companion plugin **nvim-jls** which auto-wires the launcher, Lombok, root detection, and cache handling:

```
https://github.com/idelice/nvim-jls
```

Drop it into your plugin manager and set `jls_dir = "/path/to/jls"` (the folder containing `dist/`). No manual `lspconfig` setup is needed.

### Neovim (manual / nvim-lspconfig)

See `docs/lazyvim-java-lsp.md` for a complete LazyVim example. For a minimal `nvim-lspconfig` setup:
Ensure a compatible JDK is installed and available on PATH (or set `JAVA_HOME`).

```lua
local lspconfig = require("lspconfig")
local configs = require("lspconfig.configs")

if not configs.jls then
  configs.jls = {
    default_config = {
      cmd = {
        "/path/to/jls/dist/lang_server_{linux|mac|windows}.sh",
        "-Dorg.javacs.lombokPath=/path/to/lombok.jar",
      },
      filetypes = { "java" },
      root_dir = lspconfig.util.root_pattern("pom.xml", "build.gradle", "build.gradle.kts", ".java-version", ".git"),
      settings = {
        java = {
          classPath = {
            "lib/some-dependency.jar",
          },
          docPath = {
            "lib/some-dependency-sources.jar",
          },
          externalDependencies = {
            "junit:junit:jar:4.12:test",
            "junit:junit:4.12",
          },
          mavenSettings = "/Users/you/.m2/settings.xml",
        },
      },
    },
  }
end

lspconfig.jls.setup({})
```

### Linux (env-based Lombok)
For including lombok support execute
```sh
export LOMBOK_PATH=/path/to/lombok.jar
```
before using language server

## [Issues](https://github.com/idelice/jls/issues)

## Usage

The language server will provide autocomplete and other features using:
* .java files anywhere in your workspace
* Java platform classes
* External dependencies specified using `pom.xml` or [settings](#Settings)
* CodeLens for tests and reference counts (classes and methods)

## Settings

If the language server doesn't detect your external dependencies automatically, you can specify them in your LSP client settings (the JSON examples map directly to `settings.java.*` in most clients). This fork also accepts `settings.jls.*`:

```json
{
    "java.externalDependencies": [
        "junit:junit:jar:4.12:test", // Maven format
        "junit:junit:4.12" // Gradle-style format is also allowed
    ]
}
```

### JLS-specific settings (this fork)

```json
{
  "jls.diagnostics": {
    "enable": true,
    "unusedImports": "warning"
  },
  "jls.features": {
    "inlayHints": true,
    "semanticTokens": true
  }
}
```

Notes:
- `unusedImports` supports `"warning"`, `"error"`, or `"off"`.
- `semanticTokens` is advertised but currently returns empty tokens.
- CodeLens reference counts are capped at `20+` for speed.

### Init options (this fork)

```json
{
  "jls.cache": {
    "dir": "/path/to/jls-cache"
  }
}
```

If all else fails, you can specify the Java class path and the locations of
source jars manually:

```json
{
    "java.classPath": [
        "lib/some-dependency.jar"
    ],
    "java.docPath": [
        "lib/some-dependency-sources.jar"
    ]
}
```

If your project needs a private Maven repository, make sure the language server
uses the same `settings.xml` that contains your credentials:

```json
{
    "java.mavenSettings": "/Users/you/.m2/settings.xml"
}
```

That file is passed to `mvn -s …` when Maven runs dependency goals, so private
artifacts can be resolved just like they are in `jdtls`.

You can generate a list of external dependencies using your build tool:
* Maven: `mvn dependency:list`
* Gradle: `gradle dependencies`

The Java language server will look for the dependencies you specify in `java.externalDependencies` in your Maven and Gradle caches `~/.m2` and `~/.gradle`. You should use your build tool to download the library *and* source jars of all your dependencies so that the Java language server can find them:
* Maven
  * `mvn dependency:resolve` for compilation and autocomplete
  * `mvn dependency:resolve -Dclassifier=sources` for inline Javadoc help
* Gradle
  * `gradle dependencies` for compilation and autocomplete
  * Include `classifier: sources` in your build.gradle for inline Javadoc help, for example:
    ```
    dependencies {
        testCompile group: 'junit', name: 'junit', version: '4.+'
        testCompile group: 'junit', name: 'junit', version: '4.+', classifier: 'sources'
    }
    ```

## Runtime selection

JLS does not bundle a runtime. The launcher picks Java in this order:

1. `~/.config/jls/runtimes.json` (when a matching version is detected for the workspace)
2. `JAVA_LSP_HOST_JAVA` or `JAVA_HOME`
3. `java` on `PATH`

If the server fails to start, confirm the selected Java version is installed and that the path is valid.

## Design

The Java language server uses the Java compiler API to implement language features like linting, autocomplete, and smart navigation, and the language server protocol to communicate with text editors like Neovim.

### Incremental updates

The Java compiler API provides incremental compilation at the level of files: you can create a long-lived instance of the Java compiler, and as the user edits, you only need to recompile files that have changed. The Java language server optimizes this further by *focusing* compilation on the region of interest by erasing irrelevant code. For example, suppose we want to provide autocomplete after `print` in the below code:

```java
class Printer {
    void printFoo() {
        System.out.println("foo");
    }
    void printBar() {
        System.out.println("bar");
    }
    void main() {
        print // Autocomplete here
    }
}
```

None of the code inside `printFoo()` and `printBar()` is relevant to autocompleting `print`. Before servicing the autocomplete request, the Java language server erases the contents of these methods:

```java
class Printer {
    void printFoo() {

    }
    void printBar() {

    }
    void main() {
        print // Autocomplete here
    }
}
```

For most requests, the vast majority of code can be erased, dramatically speeding up compilation.

## Logs

The java service process writes logs to stderr; your editor's LSP client typically exposes this in its LSP/log output view.

You can customize logging via `~/.config/jls/logging.properties`. If the server sees this file and no explicit
`-Djava.util.logging.config.file=...` is set, it will load it on startup.

Example `logging.properties` (enable debug timings):

```
.level=INFO
main.level=FINE
java.util.logging.ConsoleHandler.level=FINE
java.util.logging.ConsoleHandler.formatter=org.javacs.LogFormat
handlers=java.util.logging.ConsoleHandler
```

You can generate the file with:

```
chmod +x ./scripts/write_logging_config.sh
./scripts/write_logging_config.sh --level FINE
```

You can also copy the example from `docs/logging.properties`:

```
cp docs/logging.properties ~/.config/jls/logging.properties
```

## Contributing

### Installing

Before installing locally, you need to install prerequisites: Maven and Protobuf. For example on Mac OS, you can install these using [Brew](https://brew.sh):

    brew install maven protobuf

You also need to have Java 21 installed. Point the `JAVA_HOME` environment variable to it. For example, on Mac OS:

    export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/

Assuming you have these prerequisites, you should be able to install locally using:

    ./scripts/build.sh

### Editing

Please run ./configure before your first commit to install a pre-commit hook that formats the code.

## Troubleshooting

- Server fails to start:
  - Check `JAVA_HOME` or `JAVA_LSP_HOST_JAVA`.
  - Verify the launcher in `dist/` exists and is executable.
- No features in a project:
  - Confirm the workspace root is correct (pom/gradle/.git).
  - Try deleting the workspace cache under `~/.cache/jls/<workspace>/`.
- Need timing logs:
  - Ensure `~/.config/jls/logging.properties` exists or pass `-Djava.util.logging.config.file=...`.
