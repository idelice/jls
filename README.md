# Java Language Server for Neovim

A Java [Language Server Protocol](https://github.com/Microsoft/vscode-languageserver-protocol) implementation built on the [Java compiler API](https://docs.oracle.com/javase/10/docs/api/jdk.compiler-summary.html), optimized for Neovim.

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
- **Document highlight** — occurrences of symbol under cursor
- **Diagnostics** — pull-based (real-time linting without keystroke lag)
- **Signature help** — parameter info for method calls
- **Inlay hints** — parameter name hints at call sites
- **Code actions** — refactoring, quick fixes, code generation
- **Rename** — classes, methods, variables across workspace
- **Lombok** — @Data, @Getter, @Setter, @Builder, @AllArgsConstructor, @Slf4j, etc.
- **Private repositories** — Maven authentication inherited from `~/.m2/settings.xml`
- **JAR navigation** — go-to-definition into dependency source JARs

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

- **Compile-based** (javac attribution): go-to-definition, hover, diagnostics, code actions
- **Index-based** (parse + workspace index): autocomplete, find-references, signature help

This avoids full compilation on high-frequency triggers like `(` and reference scans.

## Building from source

Prerequisites: Java 25, Maven, protobuf.

```bash
./scripts/build.sh
```

Output: `dist/lang_server_{linux|mac|windows}.sh`

## Logs

The server logs to stderr. Startup prints the active JDK version.
