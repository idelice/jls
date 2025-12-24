# LazyVim Java LSP Config (Simple Runtimes File)

This approach keeps Neovim config minimal. Put your JDK list in a single JSON file and the launcher will pick the right
JDK automatically based on the project (pom/gradle/.java-version).
Make sure the JDKs listed are installed on your system; no bundled runtime is provided.

## 1) Create a runtimes file

Create `~/.config/jls/runtimes.json` (or generate it):

```json
[
  { "version": 8, "path": "/Users/youruser/.sdkman/candidates/java/8.0.392-tem" },
  { "version": 17, "path": "/Users/youruser/.sdkman/candidates/java/17.0.14-amzn" },
  { "version": 21, "path": "/Users/youruser/.sdkman/candidates/java/21.0.5-amzn" }
]
```

Generate automatically (SDKMAN/asdf/macOS JDKs):

```sh
./scripts/gen_runtimes.sh
```

## 2) Minimal LazyVim config

```lua
local lspconfig = require("lspconfig")
local configs = require("lspconfig.configs")

local JAVA_LSP_LAUNCHER = "/Users/youruser/projects/jls/dist/lang_server_mac.sh"
local LOMBOK_JAR_PATH_ARG = "-Dorg.javacs.lombokPath=/Users/youruser/downloads/lombok.jar"

local cmd = {
  JAVA_LSP_LAUNCHER,
  LOMBOK_JAR_PATH_ARG,
}

if not configs.manual_java_lsp then
  configs.manual_java_lsp = {
    default_config = {
      cmd = cmd,
      filetypes = { "java" },
      root_dir = lspconfig.util.root_pattern("pom.xml", "build.gradle", "build.gradle.kts", ".java-version", ".git"),
      settings = {},
    },
  }
end
```

Notes:
- The launcher reads `~/.config/jls/runtimes.json` by default.
- It uses the current working directory as the workspace root when selecting a JDK.
- Auto-detection checks `.java-version`, `.tool-versions`, `pom.xml`, `build.gradle`, `build.gradle.kts`.
- If no runtime matches, it falls back to the bundled runtime (or `JAVA_LSP_HOST_JAVA`/`JAVA_HOME` when Lombok is used).
