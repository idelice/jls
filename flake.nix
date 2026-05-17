{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      ...
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        jdk = pkgs.jdk25_headless;
        jls = pkgs.callPackage ./default.nix { inherit jdk; };
      in
      {
        packages = {
          inherit jls;
          default = jls;
        };
        devShells.default = pkgs.mkShell {
          inputsFrom = [ jls ];
          packages = [
            jdk
            pkgs.maven
          ];
        };
      }
    )
    // {
      overlays.default = final: _: {
        inherit (self.packages.${final.system}) jls;
      };
    };
}
