param(
    [string]$ServerJar = 'fabric-server-launch.jar',
    [string]$JavaPath = 'java',
    [string]$MinMemory = '1G',
    [string]$MaxMemory = '3G',
    [ValidateSet('Auto', 'Fabric', 'Forge')]
    [string]$Loader = 'Auto',
    [string]$ForgeArgsFile = ''
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$updateDirectory = Join-Path $PSScriptRoot 'config\guildquests\update'
$installPlan = Join-Path $updateDirectory 'pending-install.properties'
$installer = Join-Path $updateDirectory 'installer.jar'
$restartRequest = Join-Path $updateDirectory 'restart.requested'

$forgeFiles = @()
$forgeLibraries = Join-Path $PSScriptRoot 'libraries\net\minecraftforge\forge'
if (Test-Path -LiteralPath $forgeLibraries -PathType Container) {
    $forgeFiles = @(Get-ChildItem -LiteralPath $forgeLibraries -Directory | ForEach-Object {
        $candidate = Join-Path $_.FullName 'win_args.txt'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $candidate }
    })
}
if ($ForgeArgsFile) {
    if ($Loader -eq 'Fabric') { throw '-ForgeArgsFile cannot be combined with -Loader Fabric.' }
    $Loader = 'Forge'
}
if ($Loader -eq 'Auto') {
    if ($forgeFiles.Count -gt 0 -and (Test-Path -LiteralPath $ServerJar -PathType Leaf)) {
        throw 'Both Fabric and Forge were found. Select -Loader Fabric or -Loader Forge.'
    }
    $Loader = if ($forgeFiles.Count -gt 0) { 'Forge' } else { 'Fabric' }
}
$launchArguments = @()
if ($Loader -eq 'Forge') {
    if (-not $ForgeArgsFile) {
        if ($forgeFiles.Count -ne 1) { throw 'Select one installed Forge version with -ForgeArgsFile libraries/.../win_args.txt.' }
        $ForgeArgsFile = $forgeFiles[0]
    }
    if (-not (Test-Path -LiteralPath $ForgeArgsFile -PathType Leaf)) { throw "Forge argument file not found: $ForgeArgsFile" }
    if (Test-Path -LiteralPath 'user_jvm_args.txt' -PathType Leaf) { $launchArguments += '@user_jvm_args.txt' }
    $launchArguments += "-Xms$MinMemory", "-Xmx$MaxMemory", "@$ForgeArgsFile", 'nogui'
} else {
    if (-not (Test-Path -LiteralPath $ServerJar -PathType Leaf)) { throw "Server JAR not found: $ServerJar" }
    $launchArguments += "-Xms$MinMemory", "-Xmx$MaxMemory", '-jar', $ServerJar, 'nogui'
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
    & $JavaPath @launchArguments
    $serverExitCode = $LASTEXITCODE
    # A normal stop or a crash does not request an automatic restart.
    if (-not (Test-Path -LiteralPath $restartRequest)) { exit $serverExitCode }
    Write-Host 'GuildQuests requested an update. Applying it before restarting...'
}
