# Mason (Neovim) packaging notes

Mason packages are maintained in the `mason-registry` repository, not here. This repo ships the runtime
launchers and classpath jars under `dist/`, so a Mason package should:

- download a GitHub release artifact that contains the full `dist/` directory
- expose `dist/lang_server_{linux|mac|windows}.sh` (or `.cmd` on Windows) as the entrypoint

Recommended release artifact layout:

```
dist/
  classpath/
  lang_server_linux.sh
  lang_server_mac.sh
  lang_server_windows.sh
  lang_server_windows.cmd
  launch_linux.sh
  launch_mac.sh
  launch_windows.sh
  launch_windows.cmd
```

Once a package definition is added to `mason-registry`, you can install it in Neovim with:

```
:MasonInstall jls
```

Until the registry entry is published, you can use the manual `nvim-lspconfig` setup in the README.
