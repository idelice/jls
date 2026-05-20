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
- **Inlay hints** - Parameter name hints at call sites (workspace files only; Lombok-generated calls are suppressed)
- **Code actions** - Refactoring, quick fixes, and code generation (see [Code Actions](#code-actions))
- **Rename** - Rename classes, methods, and variables across the workspace
- **Lombok support** - Synthetic members from Lombok annotations (@Data, @Getter, @Setter, @Builder, @AllArgsConstructor, @Slf4j, etc.)
- **Private repository support** - Seamless integration with Maven repositories requiring authentication
- **JAR navigation** - Go-to-definition works on dependency JARs with source files

## Code Actions

Code actions are computed lazily — the list of available actions appears instantly, and edits are only computed when you apply one.

### Refactoring (on selection)

| Action | Trigger |
|--------|---------|
| Surround with try-catch | Select code inside a method |
| Extract to local variable | Select an expression inside a method |

### Code generation (cursor inside a class)

| Action | Notes |
|--------|-------|
| Generate constructor | |
| Generate equals | |
| Generate hashCode | |
| Generate toString | |
| Generate getters (pick fields) | Opens a field picker |
| Generate setters (pick fields) | Opens a field picker; excludes `final` fields |
| Override inherited method | One entry per overridable method |

### Lombok (cursor inside a class, Lombok on classpath)

`Add @Data`, `Add @Getter`, `Add @Setter`, `Add @Builder`, `Add @Value`, `Add @SuperBuilder`, `Add @AllArgsConstructor`, `Add @NoArgsConstructor`, `Add @RequiredArgsConstructor`, `Add @ToString`, `Add @EqualsAndHashCode`, `Add @With`, `Add @Slf4j`

### Quick fixes (triggered by diagnostics)

| Diagnostic | Fix |
|------------|-----|
| Unresolved type | Import `'com.example.Foo'` |
| Unreported exception | Add `throws` |
| Does not override abstract | Implement abstract methods |
| Missing constructor for fields | Generate constructor |
| Missing method call | Create missing method |
| Unused local variable | Convert to statement |
| Unused field | Convert to block |
| Unused class | Remove class |
| Unused method | Remove method |
| Unused `throws` clause | Remove exception |
| Unchecked call warning | Suppress 'unchecked' warning |

## Installation

### Prerequisites

- Java 25
- Maven
- protobuf
- **Neovim 0.10+** (required for pull diagnostics — see [Client Requirements](#client-requirements))

### Build

```bash
./scripts/build.sh
```

The language server will be built to `dist/lang_server_{linux|mac|windows}.sh`

## Client Requirements

This server uses the **pull diagnostics** model ([LSP 3.17](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_diagnostic)): it does not push errors to the client after save. Instead, the client requests diagnostics on demand via `textDocument/diagnostic`.

- **Neovim 0.10+**: Pull diagnostics are supported natively. No extra configuration is needed — `nvim-lspconfig` will enable them automatically.
- **Neovim 0.9 and older**: Not supported. Diagnostics will not appear.
- **Vim (not Neovim)**: Not supported. Vim has no built-in LSP client, and common Vim LSP plugins (`coc.nvim`, `vim-lsp`, `ale`) do not implement pull diagnostics.

## Setup with Neovim

### Option 1: Using [nvim-jls](https://github.com/idelice/nvim-jls) (Recommended)

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

### Enabling private repository support

Ensure your Maven credentials are configured in `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>my-private-repo</id>
        <username>your-username</username>
        <password>your-password</password>
    </server>
</servers>
```

Then download source JARs:

```bash
mvn dependency:sources
```

The language server will inherit the environment and use your Maven credentials for private repositories.

## Memory Management

By default the server launches with:
```
-Xmx2g -Xms512m -XX:MaxHeapFreeRatio=50 -XX:MinHeapFreeRatio=20 -XX:+UseStringDeduplication
```

Override via `JLS_JVM_OPTS` environment variable:
```bash
export JLS_JVM_OPTS="-Xmx1g -Xms256m"
```

The nvim-jls client also exposes a `jvm_args` config field that sets this env var automatically (see [nvim-jls](https://github.com/idelice/nvim-jls) docs).

## Usage

The language server provides autocomplete and other features using:
- `.java` files in your workspace
- Java platform classes
- External dependencies from Maven, Gradle, Bazel, or manual configuration

## Design

The Java language server uses the [Java compiler API](https://docs.oracle.com/javase/10/docs/api/jdk.compiler-summary.html) to implement language features like linting, autocomplete, and smart navigation, and the [language server protocol](https://github.com/Microsoft/vscode-languageserver-protocol) to communicate with text editors like Neovim.

### Compile vs index architecture

Features are split across two resolution strategies:

- **Compile-based** (javac semantic attribution): go-to-definition, hover, diagnostics, code actions. Uses `compileFast` / `compileFastWithProcessors` for full type accuracy.
- **Index-based** (parse + workspace index): autocomplete, find-references, signature help. Uses `compiler.parse()` with `ParseTypeResolver` + `TypeIndexRouter` for ~50x faster response times.

This avoids full compilation on high-frequency triggers like `(` and reference scans.

### Incremental updates

The Java compiler API provides incremental compilation at the level of files: you can create a long-lived instance of the Java compiler, and as the user edits, you only need to recompile files that have changed. The Java language server optimizes this further by *focusing* compilation on the region of interest by erasing irrelevant code.

## Contributing

### Installing

Before installing locally, you need to install prerequisites: maven, protobuf.

You also need to have Java 25 installed if you are building without `./scripts/build.sh`.

Assuming you have these prerequisites, you should be able to install locally using:

```bash
./scripts/build.sh
```

## Logs

The Java service process logs to stderr and prints the active runtime JDK details at startup.

## Debugging in editor

### Configuration

For using nvim as a client for debug adapter we have to install three plugins

```
vim.pack.add({
    { src = "https://github.com/mfussenegger/nvim-dap" },
    { src = "https://github.com/nvim-neotest/nvim-nio" },
    { src = "https://github.com/rcarriga/nvim-dap-ui" },
})
```
`nvim-dap` is a client for debug adapter, `nvim-dap-ui` nicely reflects debug info, `nvim-io` is required dependency for the later.

Configure them in `init.lua` as
```
require('dapui').setup()
local dap = require('dap')
dap.adapters.java = {
  type = 'executable';
  command = 'absolute path to jls/dist/debug_adapter_linux.sh';
}
dap.configurations.java = {
  {
    type = 'java';
    request = 'attach';
    name = "Debug (Attach) - Remote";
    hostName = "127.0.0.1";
    port = 5005;
    sourceRoots = {os.getenv("SOURCE_ROOT")};
  },
}
```
Change the name `debug_adapter_linux.sh` according to to your OS

### Usage

- Compile sources with debug option for viewing variables
- Run app needed debug with agentlib option, for example as

``
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005  -jar your_jar_name_here.jar
``

- Define OS variableiable `SOURCE_ROOT` as absolute path to Java sources under debug. Typically it is the path to `src/main/java`
- In another termial java file and execute vim command `:Dap<Tab>`
- In menu appeared choose `DapNew`
- After that you can set break points and continue with debug command through Dap menu
- or open `dapui` with vim command `:lua require('dapui').open()`
