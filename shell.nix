let
  java = "openjdk8_headless";
  pkgs = import ./nix/pkgs.nix { inherit java; };
in
pkgs.mkShell {
  buildInputs = [
    pkgs.git
    pkgs.${java}
    pkgs.sbt
  ];
}
