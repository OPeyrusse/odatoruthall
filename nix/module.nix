{
  config,
  lib,
  pkgs,
  ...
}:
let
  inherit (lib)
    concatStringsSep
    escapeShellArg
    filterAttrs
    mapAttrsToList
    mkEnableOption
    mkIf
    mkOption
    types
    ;

  cfg = config.services.odatoruthall;

  # Where the value of a variable comes from. A plain string is a literal value.
  source = types.submodule {
    options = {
      value = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = ''
          Literal value. It is written in the Nix store: keep it for what is not a secret.
          systemd specifiers are expanded (`%h`, `%S`, ...): write `%%` for a literal `%`.
        '';
      };
      file = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = ''
          File holding the value, read when the service starts. Trailing newlines are dropped.
          Fits secret managers writing files, such as sops-nix or agenix.
        '';
      };
      command = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = ''
          Shell command printing the value, run when the service starts with the PATH of the service.
          The service fails to start if the command fails. E.g. `pass show work/github-token`.
        '';
      };
    };
  };

  variable = types.nullOr (types.coercedTo types.str (value: { inherit value; }) source);

  mkVariable =
    description: default:
    mkOption {
      type = variable;
      inherit default;
      description = "${description} Unset when null.";
    };

  definedVariables = filterAttrs (_: v: v != null) cfg.environment;

  checkSource =
    name: v:
    let
      count = lib.count (x: x != null) [
        v.value
        v.file
        v.command
      ];
    in
    {
      assertion = count == 1;
      message = "services.odatoruthall.environment.${name}: set exactly one of `value`, `file` or `command`.";
    };

  # systemd splits unquoted `Environment=` values on spaces, so each assignment is quoted
  env = name: value: ''"${name}=${value}"'';

  literals = filterAttrs (_: v: v.value != null) definedVariables;
  resolved = filterAttrs (_: v: v.value == null) definedVariables;

  # Assigned then exported, so that a failing command stops the start
  resolve =
    name: v:
    if v.file != null then
      ''
        ${name}="$(< ${escapeShellArg v.file})"
        export ${name}
      ''
    else
      ''
        ${name}="$(
        ${v.command}
        )"
        export ${name}
      '';

  start = pkgs.writeShellScript "odatoruthall-start" ''
    set -eu
    ${concatStringsSep "\n" (mapAttrsToList resolve resolved)}
    exec ${lib.getExe cfg.package}
  '';

  # Programs the server may run: the Claude CLI for owldesk, and the commands resolving the variables
  servicePath = concatStringsSep ":" (
    [
      "${config.home.profileDirectory}/bin"
      "/nix/var/nix/profiles/default/bin"
      (lib.makeBinPath [
        pkgs.coreutils
        pkgs.git
      ])
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

    environment = mkOption {
      description = ''
        Variables passed to the server, as described in the README.
        Each one is a literal string, or `{ value | file | command = ...; }`.
        Other variables can be added the same way.
      '';
      default = { };
      example = lib.literalExpression ''
        {
          GITHUB_TOKEN.command = "pass show work/github-token";
          JIRA_BASE_URL = "https://acme.atlassian.net";
          JIRA_EMAIL = "me@acme.com";
          JIRA_TOKEN.file = config.sops.secrets.jira-token.path;
        }
      '';
      type = types.submodule {
        freeformType = types.attrsOf variable;
        options = {
          PORT = mkVariable "Port the server listens on." "7777";
          ODATO_DEMO = mkVariable "`1` runs against scripted data, using no credential." null;

          GITHUB_TOKEN = mkVariable "GitHub token of the operator." null;
          GITHUB_API_URL = mkVariable "GitHub API, for GitHub Enterprise." null;

          JIRA_BASE_URL = mkVariable "Jira Cloud site, e.g. `https://acme.atlassian.net`." null;
          JIRA_EMAIL = mkVariable "Account owning the Jira token." null;
          JIRA_TOKEN = mkVariable "Jira API token." null;

          TURSO_URL = mkVariable "Turso database keeping the worklog, as a `libsql://` URL." null;
          TURSO_TOKEN = mkVariable "Turso database token." null;
          TURSO_TABLE = mkVariable "Table of the Turso database." null;

          ODATO_DB_FILE = mkVariable ''
            File keeping the worklog when Turso is not configured.
            The default is in the state directory of the service, `~/.local/state/odatoruthall`.
          '' "%S/odatoruthall/odatoruthall.edn";

          OWLLIE_FIRE_URL = mkVariable "Fire URL of the Claude scheduled task running Owllie." null;
          OWLLIE_FIRE_TOKEN = mkVariable "Token of the Owllie API trigger." null;
          OWLLIE_CLI_DIR = mkVariable "Clone of Owllie, to launch the reviews with the Claude CLI." null;
          OWLLIE_CLAUDE_COMMAND = mkVariable "Command running the Claude CLI, `claude` by default." null;
          OWLLIE_RESULTS_TOKEN = mkVariable "Token deleting the old results branches of Owllie." null;
        };
      };
    };
  };

  config = mkIf cfg.enable {
    assertions = mapAttrsToList checkSource definedVariables;

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
        ++ mapAttrsToList (name: v: env name v.value) literals;
        ExecStart = "${start}";
        Restart = "on-failure";
        RestartSec = "10s";
      };
      Install = {
        WantedBy = [ "default.target" ];
      };
    };
  };
}
