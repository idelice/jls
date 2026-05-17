{
  lib,
  jdk,
  maven,
  lombok,
  protobuf_25,
  lombokSupport ? true,
  makeWrapper,
}:
let
  jlinkVmOptions =
    map
      (option: ''
        --add-flags "${option}" \
      '')
      [
        "--add-modules jdk.jdeps"
        "--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED"
        "--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
        "--add-opens jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"
      ];
in
maven.buildMavenPackage {
  pname = "jls";
  version = "0.4.1";

  src = ./.;

  mvnHash = "sha256-XKpVCSGCkvtWE7lmr67tLMf+TGpigvXvbVh/3nsMRFE=";
  mvnJdk = jdk;
  mvnParameters = "-DskipTests";

  nativeBuildInputs = [
    makeWrapper
    protobuf_25
  ];

  preBuild = ''
    bash ./scripts/gen_proto.sh
  '';

  installPhase =
    let
      wrapperFlags = ''
        ${lib.concatStringsSep " " jlinkVmOptions} \
        --add-flags "\$JLS_JVM_OPTS" \
        --add-flags "-Djava.util.logging.config.file=$out/share/jls/logging.properties" \
        ${lib.optionalString lombokSupport ''--add-flags "-Dorg.javacs.lombokPath=${lombok}/share/lombok.jar"''} \
        --add-flags "-classpath '$out/share/jls/classpath/*'" \
        --set-default JLS_JVM_OPTS "-Xmx2g -Xms512m -XX:MaxHeapFreeRatio=50 -XX:MinHeapFreeRatio=20 -XX:+UseStringDeduplication" \
      '';
    in
    ''
      runHook preInstall

      mkdir -p $out/bin $out/share/jls/
      cp -r dist/classpath $out/share/jls/
      install -Dm644 scripts/logging.properties $out/share/jls

      makeWrapper ${jdk}/bin/java $out/bin/jls \
        ${wrapperFlags} \
        --add-flags "org.javacs.Main"

      makeWrapper ${jdk}/bin/java $out/bin/jls-dap \
        ${wrapperFlags} \
        --add-flags "org.javacs.debug.JavaDebugServer"

      runHook postInstall
    '';

  meta = {
    description = "Java Language Server for Neovim";
    license = lib.licenses.mit;
    mainProgram = "jls";
  };
}
