{
  description = "odatoruthall, one dashboard to rule them all";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-26.05";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      clj-nix,
    }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (pkgsFor system));
      pkgsFor = system: nixpkgs.legacyPackages.${system}.extend clj-nix.overlays.default;

      # Same scheme as projects/server/build.clj: 0.1.<revisions>, here dated instead,
      # as a flake source carries no history to count
      gitSha = self.shortRev or self.dirtyShortRev or "dirty";
      version = "0.1.0-${builtins.substring 0 8 self.lastModifiedDate}-${gitSha}";
    in
    {
      packages = forAllSystems (pkgs: rec {
        odatoruthall = pkgs.callPackage ./nix/package.nix {
          src = self;
          inherit version;
        };
        default = odatoruthall;
        # Generates deps-lock.json, with the same clj-nix version as the build
        deps-lock = pkgs.deps-lock;
      });

      homeManagerModules.default =
        { pkgs, lib, ... }:
        {
          imports = [ ./nix/module.nix ];
          services.odatoruthall.package =
            lib.mkDefault
              self.packages.${pkgs.stdenv.hostPlatform.system}.default;
        };

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [
            pkgs.jdk25_headless
            pkgs.clojure
            pkgs.nodejs_22
          ];
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}
