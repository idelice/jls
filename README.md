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
- **Find type** — find type definition
- **Find implementation** — find implementations
- **Hover** — type information and Javadoc
- **Diagnostics** — pull-based (real-time linting without keystroke lag)
- **Signature help** — parameter info for method calls
- **Inlay hints** — parameter name hints at call sites
- **Code actions** — refactoring, quick fixes, code generation
- **Rename** — classes, methods, variables across workspace
- **Document symbols** — outline view of classes, methods, fields
- **Folding ranges** — collapse imports, classes, methods, blocks
- **Formatting** — whole-document formatting
- **Lombok** — modeled from source, including @Builder/@SuperBuilder, @Singular, @Accessors and `lombok.config`
- **Private repositories** — Maven authentication inherited from `~/.m2/settings.xml`
- **JAR navigation** — go-to-definition into dependency source JARs
- **Multi-module Gradle/Maven support** (experimental)

### Lombok

Lombok members are modeled from your source, without running Lombok's annotation processor and
without reading previously compiled classes. No build step is needed.

Modeled: `@Data`, `@Getter`, `@Setter`, `@Value` (including its implicit `private final` fields and
`final` class), `@With`, `@ToString`, `@EqualsAndHashCode`, `@AllArgsConstructor`,
`@NoArgsConstructor`, `@RequiredArgsConstructor`, `@Accessors` (fluent, chained), `@Builder`
including its arguments (`builderMethodName`, `buildMethodName`, `builderClassName`, `setterPrefix`,
`toBuilder`), `@Singular` collections and maps, `@SuperBuilder`, and the logger annotations
(`@Slf4j`, `@Log`, `@Log4j`, `@Log4j2`, `@CommonsLog`, `@Flogger`, `@JBossLog`, `@XSlf4j`,
`@CustomLog`).

`lombok.config` is read per directory, walking up until `config.stopBubbling = true`. These keys are
honoured: `lombok.accessors.prefix`, `lombok.accessors.fluent`, `lombok.accessors.chain`,
`lombok.accessors.capitalization`, `lombok.log.fieldName` and `lombok.log.custom.declaration`. Other
keys are ignored, and the server must be restarted to pick up an edited `lombok.config`.

Known boundaries — members here resolve and give correct diagnostics, but autocomplete is thinner:

- For `@SuperBuilder`, completion part-way through a builder chain does not list the parent's
  setters, because the parse-only resolver cannot follow the generated self-type. The code still
  compiles and go-to-definition works.
- `@Accessors(prefix = ...)` on an individual class is not read; only the `lombok.config` prefix is.

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

None beyond resolvable dependencies. The server reads your build's model (module graph, source
directories, inter-module dependencies, external artifacts) and analyzes everything else from
source. It never compiles your project, so an unbuilt or freshly cloned reactor works.

External artifacts still have to be downloadable or already cached. If an artifact is missing, the
affected module is analyzed with a partial classpath instead of failing.

### Maven Daemon (recommended for Maven)

For large reactors (50+ modules), install the [Maven Daemon](https://github.com/apache/maven-mvnd):

```bash
sdk install mvnd
```

JLS auto-detects `mvnd` on PATH and uses it instead of `mvn`/`mvnw`. The daemon keeps Maven warm in memory, reducing module resolution from seconds to milliseconds after the first run.

### Limitations

- Module resolution happens on the LSP thread — the server may be briefly unresponsive during first-time resolution of many modules
- A module whose dependencies resolve only partially is still analyzed; unresolved external types are reported as errors
- The server does not eagerly resolve all modules — only those you navigate to
- Very large reactors (500+ modules) are supported, but the first find-references on a widely-used type may take 10-40s while modules resolve

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

### Source-only analysis

Your code is always read from source; only dependencies are read as bytecode.

- Annotation processors never run (`-proc:none`), and the server emits no `.class` files.
- Workspace build output (`target/classes`, `build/classes`, reactor jars, and their local-repository
  copies) is excluded from every classpath, index and navigation lookup. A stale or missing build
  cannot affect results.
- Sibling modules resolve through their source directories, so an unbuilt reactor behaves the same
  as a built one, and unsaved edits in one module are visible to another immediately.
- External dependencies and the JDK are still read as bytecode, with source jars used for hover and
  navigation.
- Lombok members are modeled from source (see below) instead of being read back from compiled
  output.
- Generated sources are indexed as ordinary sources when they exist on disk. Code that only exists
  after a generator runs is unavailable until you run your normal build once.
- `module-info.java` is not part of analysis. A reactor usually spans many named modules, and one
  javac task can only compile one of them from source, so the workspace is analysed as unnamed code.
  Everything resolves and navigates normally; the cost is that module-visibility errors (a package
  that a module does not export) are not reported.

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
[module] partial_dependencies id=:some-module cause=...
```

A module whose external dependencies resolved only partially can miss references that depend on
those types. Fix the dependency (network, repository, or credentials), then save the build file to
re-resolve.

### Server unresponsive after opening a file

On first open in a multi-module project, the server resolves the module's classpath via Maven/Gradle. This blocks the LSP thread for a few seconds. Subsequent opens of files in the same module are instant (cached).

If the server stays unresponsive for more than 30s, check if Maven/Gradle is hanging (network issues, misconfigured repositories, or missing local artifacts).

### Diagnostics show errors that don't exist

The server analyzes your sources with `-proc:none`, so a type that only exists after annotation
processing has no declaration to resolve. Common causes:

- A code generator (MapStruct, Dagger, Immutables, protobuf) has never run. Run your normal build
  once so the generated `.java` files exist; they are indexed as ordinary sources.
- A generator emits only `.class` files. Those declarations cannot be recovered from source.
- An external dependency failed to resolve. Check the log for `partial_dependencies`.

Lombok is modeled directly from source and needs no build. See the Lombok section for the forms
that are not modeled.
