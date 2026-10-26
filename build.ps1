param()

$ErrorActionPreference = "Stop"
$repositoryRoot = $PSScriptRoot
$sidebarDirectory = Join-Path $repositoryRoot "build-dependencies\SidebarLib"
$sidebarCommit = "78805df8e9d49db3679e05d9e77a8bb57644ff14"

function Invoke-CheckedCommand {
    param(
        [string]$Executable,
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE."
    }
}

if (-not (Test-Path (Join-Path $sidebarDirectory ".git"))) {
    New-Item -ItemType Directory -Force -Path (Split-Path $sidebarDirectory) | Out-Null
    Invoke-CheckedCommand "git" @(
        "clone",
        "--no-checkout",
        "https://github.com/andrei1058/SidebarLib.git",
        $sidebarDirectory
    )
    Invoke-CheckedCommand "git" @(
        "-C",
        $sidebarDirectory,
        "checkout",
        "--detach",
        $sidebarCommit
    )
}

$checkedOutCommit = (& git -C $sidebarDirectory rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not determine the SidebarLib checkout revision."
}
if ($checkedOutCommit -ne $sidebarCommit) {
    throw "SidebarLib is checked out at $checkedOutCommit; expected pinned revision $sidebarCommit. Remove build-dependencies\SidebarLib and retry."
}

$sidebarPom = Join-Path $sidebarDirectory "pom.xml"
Invoke-CheckedCommand "mvn" @(
    "-f",
    $sidebarPom,
    "-B",
    "-ntp",
    "versions:set",
    "-DnewVersion=24.2",
    "-DprocessAllModules=true",
    "-DgenerateBackupPoms=false"
)
Invoke-CheckedCommand "mvn" @(
    "-f",
    $sidebarPom,
    "-B",
    "-ntp",
    "versions:use-dep-version",
    "-Dincludes=me.clip:placeholderapi",
    "-DdepVersion=2.11.6",
    "-DforceVersion=true",
    "-DgenerateBackupPoms=false"
)
Invoke-CheckedCommand "mvn" @(
    "-f",
    $sidebarPom,
    "-B",
    "-ntp",
    "-DskipTests",
    "-Dmaven.javadoc.skip=true",
    "install"
)

Invoke-CheckedCommand "mvn" @(
    "-f",
    (Join-Path $repositoryRoot "pom.xml"),
    "-B",
    "-ntp",
    "-pl",
    "bedwars-plugin",
    "-am",
    "clean",
    "package",
    "-DskipTests"
)
