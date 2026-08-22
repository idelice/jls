# Java Language Server for Neovim

A Java [Language Server Protocol](https://github.com/Microsoft/vscode-languageserver-protocol) implementation built on the [Java compiler API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.compiler/module-summary.html), optimized for Neovim.

Fork of [georgewfraser/java-language-server](https://github.com/georgewfraser/java-language-server).

## Requirements

- **Neovim 0.10+**
- **Java 25** (runtime — bundled JRE is included in releases)

## Setup

### Option 1: nvim-jls plugin (Recommended)

[nvim-jls](https://github.com/idelice/nvim-jls) handles installation, configuration, and diagnostics automatically.

### Option 2: Mason + lspconfig

```lua
require('mason').setup()
require('mason-lspconfig').setup()
require('lspconfig').jls.setup({})
```

### Option 3: Manual

Build from source:

```bash
./scripts/build.sh
```

Then configure lspconfig:

```lua
require('lspconfig').jls.setup({
    cmd = { '<path-to-jls>/dist/lang_server_mac.sh' }, -- or linux/windows
})
```

## Features

- **Autocomplete** — symbols, members, imports
- **Go-to-definition** — workspace files, dependency JARs (with source), decompiled classes
- **Find references** — all usages across workspace
- **Hover** — type information and Javadoc
- **Diagnostics** — pull-based (real-time linting without keystroke lag)
- **Signature help** — parameter info for method calls
- **Inlay hints** — parameter name hints at call sites
- **Code actions** — refactoring, quick fixes, code generation
- **Rename** — classes, methods, variables across workspace
- **Document symbols** — outline view of classes, methods, fields
- **Folding ranges** — collapse imports, classes, methods, blocks
- **Formatting** — whole-document formatting
- **Lombok** — @Data, @Getter, @Setter, @Builder, @AllArgsConstructor, @Slf4j, etc.
- **Private repositories** — Maven authentication inherited from `~/.m2/settings.xml`
- **JAR navigation** — go-to-definition into dependency source JARs
- **Multi-module Gradle/Maven support** (experimental)

### Code actions

Code actions are computed lazily — the list appears instantly, edits are computed on apply.

**Refactoring (on selection):**
- Surround with try-catch
- Extract to local variable

**Code generation (cursor inside a class):**
- Generate constructor, equals, hashCode, toString
- Generate getters/setters (field picker)
- Override inherited method

**Lombok (cursor inside a class, Lombok on classpath):**
- Add @Data, @Getter, @Setter, @Builder, @Value, @SuperBuilder, @AllArgsConstructor, @NoArgsConstructor, @RequiredArgsConstructor, @ToString, @EqualsAndHashCode, @With, @Slf4j

**Quick fixes:**
- Import unresolved type
- Add `throws`
- Implement abstract methods
- Generate constructor for fields
- Create missing method
- Remove unused variable/field/class/method/throws
- Suppress 'unchecked' warning

## Project Configuration

The server auto-detects dependencies from Maven (`pom.xml`), Gradle (`build.gradle`), or Bazel (`BUILD`).

### `.java-language-server.json`

Place in your project root for project-specific settings that travel with the repo:

```json
{
    "addExports": [
        "jdk.compiler/com.sun.tools.javac.api",
        "jdk.compiler/com.sun.tools.javac.tree"
    ],
    "extraCompilerArgs": [
        "--release 21"
    ],
    "externalDependencies": [
        "junit:junit:4.12"
    ],
    "classPath": [
        "lib/some-dependency.jar"
    ],
    "docPath": [
        "lib/some-dependency-sources.jar"
    ]
}
```

| Field | Purpose |
|-------|---------|
| `addExports` | `--add-exports` flags passed to javac (e.g. for internal JDK APIs) |
| `extraCompilerArgs` | Additional javac arguments |
| `externalDependencies` | Maven coordinates to resolve |
| `classPath` | Explicit JAR paths |
| `docPath` | Source JAR paths for hover/navigation |

### Private repositories

Ensure Maven credentials are in `~/.m2/settings.xml`, then:

```bash
mvn dependency:sources
```

The server inherits your environment and uses Maven credentials automatically.

## Memory Management

Default JVM flags:
```
-Xmx2g -Xms512m -XX:MaxHeapFreeRatio=50 -XX:MinHeapFreeRatio=20 -XX:+UseStringDeduplication
```

Override via `JLS_JVM_OPTS`:
```bash
export JLS_JVM_OPTS="-Xmx1g -Xms256m"
```

The nvim-jls client exposes a `jvm_args` config field that sets this automatically.

## Multi-Module Support (Experimental)

JLS supports multi-module Maven and Gradle projects. Modules are resolved lazily — only when you open a file or navigate to a reference in another module.

### How it works

1. On startup, JLS reads the project structure (module graph, source directories, inter-module dependencies)
2. When you open a file, the server resolves that module's classpath on-demand
3. When you use find-references or go-to-definition across modules, referenced modules are resolved in parallel

### Prerequisites

Multi-module projects require that dependency artifacts are available locally. The server resolves classpaths via `mvn dependency:list` or Gradle tooling — it does **not** compile your project.

**For Maven multi-module projects**, you must build/install the reactor first:

```bash
mvn install -DskipTests
```

Without this, modules that depend on sibling modules will fail to resolve (the server cannot find their compiled artifacts).

**For Gradle multi-module projects**, ensure the project builds successfully:

```bash
./gradlew classes
```

### Maven Daemon (recommended for Maven)

For large reactors (50+ modules), install the [Maven Daemon](https://github.com/apache/maven-mvnd):

```bash
sdk install mvnd
```

JLS auto-detects `mvnd` on PATH and uses it instead of `mvn`/`mvnw`. The daemon keeps Maven warm in memory, reducing module resolution from seconds to milliseconds after the first run.

### Limitations

- Module resolution happens on the LSP thread — the server may be briefly unresponsive during first-time resolution of many modules
- Modules whose dependency resolution fails are skipped for that session (restart the server after fixing build issues)
- The server does not eagerly resolve all modules — only those you navigate to
- Very large reactors (500+ modules) are supported but first find-references on widely-used types may take 10-40s while modules resolve in parallel

## Debugging

### Prerequisites

Install a debug adapter client:

Using `vim.pack.add`:
```lua
vim.pack.add({
    { src = "https://github.com/mfussenegger/nvim-dap" },
    { src = "https://github.com/nvim-neotest/nvim-nio" },
    { src = "https://github.com/rcarriga/nvim-dap-ui" },
})
```

Using lazy.nvim:
```lua
{
    "mfussenegger/nvim-dap",
    dependencies = {
        "nvim-neotest/nvim-nio",
        "rcarriga/nvim-dap-ui",
    },
}
```

### Configuration

```lua
require('dapui').setup()
local dap = require('dap')
dap.adapters.java = {
    type = 'executable',
    command = 'absolute path to jls/dist/debug_adapter_mac.sh', -- or linux/windows
}
dap.configurations.java = {
    {
        type = 'java',
        request = 'attach',
        name = "Debug (Attach) - Remote",
        hostName = "127.0.0.1",
        port = 5005,
        sourceRoots = { os.getenv("SOURCE_ROOT") },
    },
}
```

### Usage

1. Compile with debug info: `javac -g ...`
2. Run with debug agent:
   ```
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar your_app.jar
   ```
3. Set `SOURCE_ROOT` to your `src/main/java` path
4. In Neovim: `:DapNew` → select the attach configuration
5. Set breakpoints and use `:lua require('dapui').open()`

## Architecture

Features are split across two resolution strategies:

- **Compile-based** (javac attribution): go-to-definition, hover, diagnostics, code actions, find-references
- **Index-based** (parse + workspace index): autocomplete, signature help

This avoids full compilation on high-frequency triggers like `(` and keystroke-driven completions.

## Building from source

Prerequisites: Java 25, Maven.

```bash
./scripts/build.sh
```

Output: `dist/lang_server_{linux|mac|windows}.sh`

## Logs

The server logs to stderr. Startup prints the active JDK version.

## Troubleshooting

### Find references returns incomplete results

If find-references shows fewer results than expected, check the server logs for warnings like:

```
[maven] compiler_failed module=:some-module reason=Maven dependency resolution failed
[ref] skip_candidate file=SomeFile.java reason=Maven dependency resolution previously failed
```

This means a module's dependencies couldn't be resolved. Fix: run `mvn install -DskipTests` in the project root, then restart the server.

### Server unresponsive after opening a file

On first open in a multi-module project, the server resolves the module's classpath via Maven/Gradle. This blocks the LSP thread for a few seconds. Subsequent opens of files in the same module are instant (cached).

If the server stays unresponsive for more than 30s, check if Maven/Gradle is hanging (network issues, misconfigured repositories, or missing local artifacts).

### Multi-module: "dependency resolution failed"

The server runs `mvn dependency:list -pl <module> -am` to resolve each module. This fails if:

- Sibling module artifacts aren't installed locally → run `mvn install -DskipTests`
- A remote repository cached a "not found" result → run `mvn -U install -DskipTests` to force-update
- The module has a broken pom.xml → fix the pom and restart the server

After fixing the issue, save the pom.xml and the server will retry previously-failed modules on the next request (hover, definition, or references). For other fixes, restart the server.

### Diagnostics show errors that don't exist

If the server reports compilation errors that your build doesn't, the classpath is likely incomplete. Ensure:

- Maven: `mvn install -DskipTests` has been run
- Gradle: `./gradlew classes` has been run
- Check that the module's dependencies are all available in your local repository
