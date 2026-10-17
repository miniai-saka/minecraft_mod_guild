#!/bin/sh
set -eu
cd "$(dirname "$0")"
java_path=${JAVA_PATH:-java}
server_jar=${SERVER_JAR:-fabric-server-launch.jar}
min_memory=${MIN_MEMORY:-1G}
max_memory=${MAX_MEMORY:-3G}
update_dir=config/guildquests/update
plan="$update_dir/pending-install.properties"
helper="$update_dir/installer.jar"
restart="$update_dir/restart.requested"
test -f "$server_jar" || { echo "Server JAR not found: $server_jar" >&2; exit 1; }
while :; do
    if test -f "$plan"; then
        test -f "$helper" || { echo "Update installer is missing: $helper" >&2; exit 1; }
        "$java_path" -cp "$helper" dev.guildquests.update.UpdateInstaller "$plan" || exit 1
    fi
    rm -f "$restart"
    result=0
    "$java_path" "-Xms$min_memory" "-Xmx$max_memory" -jar "$server_jar" nogui || result=$?
    test -f "$restart" || exit "$result"
    echo 'GuildQuests requested an update. Applying it before restarting...'
done
