#!/bin/sh
set -eu
cd "$(dirname "$0")"
java_path=${JAVA_PATH:-java}
server_jar=${SERVER_JAR:-fabric-server-launch.jar}
min_memory=${MIN_MEMORY:-1G}
max_memory=${MAX_MEMORY:-3G}
loader=${LOADER:-auto}
forge_args=${FORGE_ARGS_FILE:-}
update_dir=config/guildquests/update
plan="$update_dir/pending-install.properties"
helper="$update_dir/installer.jar"
restart="$update_dir/restart.requested"
forge_count=0
detected_forge_args=
for candidate in libraries/net/minecraftforge/forge/*/unix_args.txt; do
    if test -f "$candidate"; then
        forge_count=$((forge_count+1))
        detected_forge_args=$candidate
    fi
done
if test -n "$forge_args"; then
    test "$loader" != fabric || { echo 'FORGE_ARGS_FILE cannot be combined with LOADER=fabric.' >&2; exit 1; }
    loader=forge
fi
if test "$loader" = auto; then
    if test "$forge_count" -gt 0 && test -f "$server_jar"; then
        echo 'Both loaders were found. Set LOADER=fabric or LOADER=forge.' >&2; exit 1
    fi
    if test "$forge_count" -gt 0; then loader=forge; else loader=fabric; fi
fi
case "$loader" in
    forge)
        if test -z "$forge_args"; then
            test "$forge_count" = 1 || { echo 'Select a Forge version with FORGE_ARGS_FILE.' >&2; exit 1; }
            forge_args=$detected_forge_args
        fi
        test -f "$forge_args" || { echo "Forge argument file not found: $forge_args" >&2; exit 1; }
        ;;
    fabric) test -f "$server_jar" || { echo "Server JAR not found: $server_jar" >&2; exit 1; } ;;
    *) echo 'LOADER must be auto, fabric, or forge.' >&2; exit 1 ;;
esac
while :; do
    if test -f "$plan"; then
        test -f "$helper" || { echo "Update installer is missing: $helper" >&2; exit 1; }
        "$java_path" -cp "$helper" dev.guildquests.update.UpdateInstaller "$plan" || exit 1
    fi
    rm -f "$restart"
    result=0
    if test "$loader" = forge; then
        set --
        if test -f user_jvm_args.txt; then set -- '@user_jvm_args.txt'; fi
        "$java_path" "$@" "-Xms$min_memory" "-Xmx$max_memory" "@$forge_args" nogui || result=$?
    else
        "$java_path" "-Xms$min_memory" "-Xmx$max_memory" -jar "$server_jar" nogui || result=$?
    fi
    test -f "$restart" || exit "$result"
    echo 'GuildQuests requested an update. Applying it before restarting...'
done
