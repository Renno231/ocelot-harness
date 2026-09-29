# Shared Java selection for source builds and installed launchers.
ocelot_select_java() {
  ocelot_root=$1
  ocelot_mode=$2
  if [ -n "${OCELOT_JAVA:-}" ]; then
    ocelot_java=$OCELOT_JAVA
    ocelot_java_source=OCELOT_JAVA
  elif [ "$ocelot_mode" = runtime ] && [ -d "$ocelot_root/runtime" ]; then
    ocelot_java="$ocelot_root/runtime/bin/java"
    ocelot_java_source='bundled runtime'
  elif [ -n "${JAVA_HOME:-}" ]; then
    ocelot_java="$JAVA_HOME/bin/java"
    ocelot_java_source=JAVA_HOME
  else
    ocelot_java=$(command -v java 2>/dev/null || true)
    ocelot_java_source=PATH
  fi
  if [ ! -x "$ocelot_java" ] && [ -x "$ocelot_java.exe" ]; then
    ocelot_java="$ocelot_java.exe"
  fi
  if [ ! -x "$ocelot_java" ]; then
    printf 'ERROR: Java 8 is required; invalid Java executable selected through %s: %s\n' "$ocelot_java_source" "$ocelot_java" >&2
    return 1
  fi
  ocelot_java_version=$("$ocelot_java" -version 2>&1) || {
    printf 'ERROR: Java 8 is required; selected java -version failed: %s\n' "$ocelot_java" >&2
    return 1
  }
  case "$ocelot_java_version" in
    *'version "1.8.'*) ;;
    *)
      printf 'ERROR: Java 8 is required for this release; selected through %s: %s\n' "$ocelot_java_source" "$ocelot_java" >&2
      printf '%s\n' "$ocelot_java_version" 'Use the bundled download, set JAVA_HOME to Java 8, or set OCELOT_JAVA to its java executable. Java 21 is not yet supported.' >&2
      return 1
      ;;
  esac
}
