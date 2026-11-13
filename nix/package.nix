{
  lib,
  stdenvNoCC,
  makeWrapper,
  mkCljBin,
  jdk25_headless,
  temurin-jre-bin-25,
  src,
  version,
}:
let
  # Uberjar built by projects/server/build.clj, with the dependencies pinned by deps-lock.json.
  # The front-end assets are committed, so the build needs neither Node nor a network.
  server = mkCljBin {
    projectSrc = src;
    name = "odatoruthall/server";
    inherit version;
    main-ns = "odato.server.main";
    jdkRunner = jdk25_headless;
    buildCommand = ''
      export ODATO_VERSION=${lib.escapeShellArg version}
      (cd projects/server && clojure -T:build uber)
    '';
    preInstall = ''
      jarPath=projects/server/target/odatoruthall.jar
    '';
  };
in
stdenvNoCC.mkDerivation {
  pname = "odatoruthall";
  inherit version;

  nativeBuildInputs = [ makeWrapper ];

  dontUnpack = true;
  dontConfigure = true;
  dontBuild = true;

  # JAVA_OPTS is read at start, as in the image
  installPhase = ''
    runHook preInstall

    mkdir -p $out/apps $out/bin
    cp "$(cat ${server}/nix-support/jar-path)" $out/apps/odatoruthall.jar

    makeWrapper ${temurin-jre-bin-25}/bin/java $out/bin/odatoruthall \
      --add-flags "\''${JAVA_OPTS:-}" \
      --add-flags "-jar $out/apps/odatoruthall.jar"

    runHook postInstall
  '';

  passthru = { inherit server; };

  meta = {
    homepage = "https://github.com/OPeyrusse/odatoruthall";
    description = "Pull request dashboard, Jira worklog and Owllie reviews in one server";
    mainProgram = "odatoruthall";
    platforms = lib.platforms.linux;
  };
}
