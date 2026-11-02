#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$FijiRoot,
    [string]$JdkHome
)
$ErrorActionPreference = 'Stop'
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$jarDirectory = Join-Path $FijiRoot 'jars'
if (-not (Test-Path -LiteralPath $jarDirectory -PathType Container)) {
    throw 'FijiRoot must be the Fiji installation directory containing jars/.'
}

# Use the Fiji dependency set explicitly; this extension does not redistribute it.
# Exclude every installed version of this extension from compilation and tests.
$dependencies = @(Get-ChildItem -LiteralPath $jarDirectory -Filter '*.jar' -File |
    Where-Object { $_.Name -notlike 'fiji-deepseek-provider-*' -and $_.Name -notlike 'fiji-llm-provider-*' } |
    Sort-Object Name)
if (-not ($dependencies | Where-Object { $_.Name -like 'fiji-llm-*.jar' })) {
    throw 'Fiji Chat is missing. Install the Fiji-chat update site first.'
}

$jdkCandidates = @()
if ($JdkHome) {
    $jdkCandidates = @(Join-Path (Resolve-Path -LiteralPath $JdkHome).Path 'bin')
} else {
    $bundledJava = Join-Path $FijiRoot 'java'
    if (Test-Path -LiteralPath $bundledJava -PathType Container) {
        $jdkCandidates += @(Get-ChildItem -LiteralPath $bundledJava -Filter 'javac.exe' -File -Recurse |
            Sort-Object FullName | ForEach-Object DirectoryName)
    }
    if ($env:JAVA_HOME) { $jdkCandidates += Join-Path $env:JAVA_HOME 'bin' }
    $pathCompiler = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($pathCompiler) { $jdkCandidates += Split-Path -Parent $pathCompiler.Source }
}
$jdkBin = $jdkCandidates | Where-Object {
    (Test-Path -LiteralPath (Join-Path $_ 'javac.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'jar.exe'))
} | Select-Object -First 1
if (-not $jdkBin) { throw 'A JDK is required. Supply -JdkHome with a JDK 21 installation.' }
$compilerVersion = (& (Join-Path $jdkBin 'javac.exe') -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch 'javac (\d+)' -or [int]$Matches[1] -lt 21) {
    throw "Use JDK 21 or newer; found: $compilerVersion. Supply -JdkHome to select another JDK."
}

$manifest = Join-Path $PSScriptRoot 'MANIFEST.MF'
$versionLine = Select-String -LiteralPath $manifest -Pattern '^Implementation-Version: (\S+)$'
if ($null -eq $versionLine) { throw 'Implementation-Version missing from MANIFEST.MF.' }
$version = $versionLine.Matches[0].Groups[1].Value
$buildDirectory = Join-Path $PSScriptRoot 'build'
# Fresh per-run directories prevent stale classes without deleting an existing tree.
$runDirectory = Join-Path $buildDirectory ('run-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $runDirectory 'classes'
$tests = Join-Path $runDirectory 'tests'
New-Item -ItemType Directory -Path $classes,$tests -Force | Out-Null
$candidateJar = Join-Path $runDirectory "fiji-llm-provider-$version.jar"
$outputJar = Join-Path $buildDirectory "fiji-llm-provider-$version.jar"
$dependencyClassPath = ($dependencies.FullName -join ';')

function Write-JavaArgumentFile([string]$Path, [string[]]$Arguments) {
    $lines = @($Arguments | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' })
    [System.IO.File]::WriteAllLines($Path, $lines, [System.Text.UTF8Encoding]::new($false))
}

$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Filter '*.java' -File -Recurse |
    Sort-Object FullName | ForEach-Object FullName)
$compileArguments = Join-Path $runDirectory 'compile.args'
Write-JavaArgumentFile $compileArguments (@('--release','17','-encoding','UTF-8','-proc:none',
    '-cp',$dependencyClassPath,'-d',$classes) + $sources)
& (Join-Path $jdkBin 'javac.exe') "@$compileArguments"
if ($LASTEXITCODE -ne 0) { throw 'Production compilation failed.' }
Copy-Item -Path (Join-Path $PSScriptRoot 'src\main\resources\*') -Destination $classes -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'LICENSE.txt') -Destination $classes
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'NOTICE.txt') -Destination $classes
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'licenses') -Destination $classes -Recurse
& (Join-Path $jdkBin 'jar.exe') --create --file $candidateJar --manifest $manifest --date 2026-11-03T00:00:00Z -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }

$testSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java') -Filter '*.java' -File -Recurse |
    Sort-Object FullName | ForEach-Object FullName)
$compileTestArguments = Join-Path $runDirectory 'compile-tests.args'
Write-JavaArgumentFile $compileTestArguments (@('--release','17','-encoding','UTF-8','-proc:none',
    '-cp',"$candidateJar;$dependencyClassPath",'-d',$tests) + $testSources)
& (Join-Path $jdkBin 'javac.exe') "@$compileTestArguments"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
$runtimeArguments = Join-Path $runDirectory 'test-classpath.args'
Write-JavaArgumentFile $runtimeArguments @('--add-opens=java.base/java.lang=ALL-UNNAMED',
    '-cp',"$tests;$candidateJar;$dependencyClassPath")
foreach ($test in @('VerificationMain','KeyManagerVerificationMain','StartupVerificationMain',
    'ModelCatalogVerificationMain','MultimodalVerificationMain','TransportVerificationMain',
    'MultiProviderVerificationMain','AssistantExecutionVerificationMain','ProviderTimeoutVerificationMain')) {
    & (Join-Path $jdkBin 'java.exe') "@$runtimeArguments" "sc.fiji.llm.deepseek.$test"
    if ($LASTEXITCODE -ne 0) { throw "Verification failed: $test" }
}

Copy-Item -LiteralPath $candidateJar -Destination $outputJar -Force
Write-Output "All nine verification suites passed. Built: $outputJar"
Get-FileHash -LiteralPath $outputJar -Algorithm SHA256
