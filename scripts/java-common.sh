# Shared Java selection for source builds and installed launchers.
ocelot_select_java() {
  ocelot_root=$1
  ocelot_mode=$2
  if [ -n "${OCELOT_JAVA:-}" ]; then
    ocelot_java=$OCELOT_JAVA
    ocelot_java_source=OCELOT_JAVA
  elif [ -n "${JAVA_HOME:-}" ]; then
    ocelot_java="$JAVA_HOME/bin/java"
    ocelot_java_source=JAVA_HOME
  else
    ocelot_java=$(command -v java 2>/dev/null || true)
    ocelot_java_source=PATH
    if [ -z "$ocelot_java" ] && [ "$ocelot_mode" = runtime ] && [ -d "$ocelot_root/runtime" ]; then
      ocelot_java="$ocelot_root/runtime/bin/java"
      ocelot_java_source='bundled runtime'
    fi
  fi
  if [ ! -x "$ocelot_java" ] && [ -x "$ocelot_java.exe" ]; then
    ocelot_java="$ocelot_java.exe"
  fi
  if [ ! -x "$ocelot_java" ]; then
    printf 'ERROR: Java 8 or newer is required; invalid Java executable selected through %s: %s\n' "$ocelot_java_source" "$ocelot_java" >&2
    return 1
  fi
  ocelot_java_version=$("$ocelot_java" -version 2>&1) || {
    printf 'ERROR: Java 8 or newer is required; selected java -version failed: %s\n' "$ocelot_java" >&2
    return 1
  }
  ocelot_java_major=$(printf '%s\n' "$ocelot_java_version" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
  if [ "$ocelot_java_major" = 1 ]; then
    ocelot_java_major=$(printf '%s\n' "$ocelot_java_version" | sed -n 's/.*version "1\.\([0-9][0-9]*\).*/\1/p' | head -n 1)
  fi
  if [ -z "$ocelot_java_major" ] || [ "$ocelot_java_major" -lt 8 ]; then
    printf 'ERROR: Java 8 or newer is required; selected through %s: %s\n' "$ocelot_java_source" "$ocelot_java" >&2
    printf '%s\n' "$ocelot_java_version" 'Use a bundled download, JAVA_HOME or OCELOT_JAVA. Verified Java versions are documented in the download guide.' >&2
    return 1
  fi
}
