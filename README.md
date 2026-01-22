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
- **Private repository support** - Seamless integration with Maven repositories requiring authentication
- **JAR navigation** - Go-to-definition works on dependency JARs with source files

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

## Usage

The language server provides autocomplete and other features using:
- `.java` files in your workspace
- Java platform classes
- External dependencies from Maven, Gradle, Bazel, or manual configuration

## Design

The Java language server uses the [Java compiler API](https://docs.oracle.com/javase/10/docs/api/jdk.compiler-summary.html) to implement language features like linting, autocomplete, and smart navigation, and the [language server protocol](https://github.com/Microsoft/vscode-languageserver-protocol) to communicate with text editors like Neovim.

### Incremental updates

The Java compiler API provides incremental compilation at the level of files: you can create a long-lived instance of the Java compiler, and as the user edits, you only need to recompile files that have changed. The Java language server optimizes this further by *focusing* compilation on the region of interest by erasing irrelevant code.

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
