param(
    [string]$ServerJar = 'fabric-server-launch.jar',
    [string]$JavaPath = 'java',
    [string]$MinMemory = '1G',
    [string]$MaxMemory = '3G'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$updateDirectory = Join-Path $PSScriptRoot 'config\guildquests\update'
$installPlan = Join-Path $updateDirectory 'pending-install.properties'
$installer = Join-Path $updateDirectory 'installer.jar'
$restartRequest = Join-Path $updateDirectory 'restart.requested'

if (-not (Test-Path -LiteralPath $ServerJar -PathType Leaf)) {
    throw "Server JAR not found: $ServerJar"
}
while ($true) {
    # Always finish a pending installation before Fabric scans mods, including after a crash.
    if (Test-Path -LiteralPath $installPlan) {
        if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
            throw "Update installer is missing: $installer"
        }
        & $JavaPath '-cp' $installer 'dev.guildquests.update.UpdateInstaller' $installPlan
        if ($LASTEXITCODE -ne 0) { throw 'Update failed. See config/guildquests/update/installer.log. Server was not restarted.' }
    }
    if (Test-Path -LiteralPath $restartRequest) { Remove-Item -LiteralPath $restartRequest }
    & $JavaPath "-Xms$MinMemory" "-Xmx$MaxMemory" '-jar' $ServerJar 'nogui'
    $serverExitCode = $LASTEXITCODE
    # A normal stop or a crash does not request an automatic restart.
    if (-not (Test-Path -LiteralPath $restartRequest)) { exit $serverExitCode }
    Write-Host 'GuildQuests requested an update. Applying it before restarting...'
}
