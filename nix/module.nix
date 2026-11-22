{
  config,
  lib,
  pkgs,
  ...
}:
let
  inherit (lib)
    concatStringsSep
    filterAttrs
    mapAttrsToList
    mkEnableOption
    mkIf
    mkOption
    types
    ;

  cfg = config.services.odatoruthall;

  mkVariable =
    description: default:
    mkOption {
      type = types.nullOr types.str;
      inherit default;
      description = "${description} Unset when null.";
    };

  # systemd splits unquoted `Environment=` values on spaces, so each assignment is quoted
  env = name: value: ''"${name}=${value}"'';

  # Programs the server may run, such as the Claude CLI for owldesk
  servicePath = concatStringsSep ":" (
    [
      "${config.home.profileDirectory}/bin"
      "/nix/var/nix/profiles/default/bin"
      (lib.makeBinPath [ pkgs.git ])
    ]
    ++ cfg.extraPath
    ++ [
      "/usr/local/bin"
      "/usr/bin"
      "/bin"
    ]
  );
in
{
  options.services.odatoruthall = {
    enable = mkEnableOption "odatoruthall server";

    package = mkOption {
      type = types.package;
      description = "odatoruthall package, providing the `odatoruthall` server.";
    };

    javaOptions = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "-Xmx512m" ];
      description = "Options passed to the JVM running the server.";
    };

    extraPath = mkOption {
      type = types.listOf types.str;
      default = [ ];
      description = ''
        Extra directories added to the PATH of the service.
        The home-manager profile and the system directories are already in it.
      '';
    };

    environmentFiles = mkOption {
      type = types.listOf types.str;
      default = [ "-%h/.config/odatoruthall/env" ];
      example = [ "%t/secrets/odatoruthall.env" ];
      description = ''
        Files of `NAME=value` lines read by systemd when the service starts: where the secrets go,
        such as `GITHUB_TOKEN`, `JIRA_TOKEN`, `TURSO_TOKEN`, `OWLLIE_FIRE_TOKEN`, `OWLLIE_TUNE_FIRE_TOKEN`, `OWLLIE_RESULTS_TOKEN` or `GITHUB_ASSIGN_TOKEN`.
        They stay out of the Nix store, and are written by whatever manages the secrets.
        systemd specifiers are expanded (`%h` the home directory, `%t` the runtime directory, ...).
        A path prefixed by `-` may be missing. Their values override those of `environment`.
      '';
    };

    environment = mkOption {
      description = ''
        Variables passed to the server, as described in the README. They are written in the Nix
        store: put the secrets in `environmentFiles` instead. systemd specifiers are expanded
        (`%h`, `%S`, ...): write `%%` for a literal `%`. Other variables can be added the same way.
      '';
      default = { };
      example = {
        JIRA_BASE_URL = "https://acme.atlassian.net";
        JIRA_EMAIL = "me@acme.com";
      };
      type = types.submodule {
        freeformType = types.attrsOf types.str;
        options = {
          PORT = mkVariable "Port the server listens on." "7777";
          ODATO_DEMO = mkVariable "`1` runs against scripted data, using no credential." null;

          GITHUB_API_URL = mkVariable "GitHub API, for GitHub Enterprise." null;

          JIRA_BASE_URL = mkVariable "Jira Cloud site, e.g. `https://acme.atlassian.net`." null;
          JIRA_EMAIL = mkVariable "Account owning the Jira token." null;

          TURSO_URL = mkVariable "Turso database keeping the worklog, as a `libsql://` URL." null;
          TURSO_TABLE = mkVariable "Table of the Turso database." null;

          ODATO_DB_FILE = mkVariable ''
            File keeping the worklog when Turso is not configured.
            The default is in the state directory of the service, `~/.local/state/odatoruthall`.
          '' "%S/odatoruthall/odatoruthall.edn";

          OWLLIE_FIRE_URL = mkVariable "Fire URL of the Claude scheduled task running Owllie." null;
          OWLLIE_TUNE_FIRE_URL = mkVariable "Fire URL of the Claude scheduled task running Owllie's tuning." null;
          OWLLIE_CLI_DIR = mkVariable "Clone of Owllie, to launch the reviews with the Claude CLI." null;
          OWLLIE_CLAUDE_COMMAND = mkVariable "Command running the Claude CLI, `claude` by default." null;
        };
      };
    };
  };

  config = mkIf cfg.enable {
    home.packages = [ cfg.package ];

    systemd.user.services.odatoruthall = {
      Unit = {
        Description = "odatoruthall, one dashboard to rule them all";
        After = "network.target";
      };
      Service = {
        Type = "simple";
        StateDirectory = "odatoruthall";
        Environment = [
          (env "PATH" servicePath)
          (env "JAVA_OPTS" (concatStringsSep " " cfg.javaOptions))
        ]
        ++ mapAttrsToList env (filterAttrs (_: v: v != null) cfg.environment);
        EnvironmentFile = cfg.environmentFiles;
        ExecStart = lib.getExe cfg.package;
        Restart = "on-failure";
        RestartSec = "10s";
      };
      Install = {
        WantedBy = [ "default.target" ];
      };
    };
  };
}
