param(
    [switch]$SkipPackage,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$appName = 'Ducky Player'
$appJarName = 'DuckyPlayer.jar'
$jdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
if (-not (Test-Path -LiteralPath "$jdkHome\bin\javac.exe")) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
        $jdkHome = $env:JAVA_HOME
    } else {
        throw 'Java 21 JDK not found. Set JAVA_HOME to a Java 21 JDK.'
    }
}

$buildRoot = Join-Path $projectRoot 'build'
if ($Clean -and (Test-Path -LiteralPath $buildRoot)) {
    $resolvedBuild = (Resolve-Path -LiteralPath $buildRoot).Path
    $resolvedRoot = (Resolve-Path -LiteralPath $projectRoot).Path
    if (-not $resolvedBuild.StartsWith($resolvedRoot + [IO.Path]::DirectorySeparatorChar)) {
        throw "Refusing to clean path outside project: $resolvedBuild"
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}

$libraries = @(
    @{ Name = 'jna-5.19.1.jar'; Uri = 'https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.19.1/jna-5.19.1.jar'; Sha256 = '4FB141DD8EF6B0585FFCEEA4BC49602FBC6312FA977E2C488794EA3E6AAFECAE' },
    @{ Name = 'jna-platform-5.19.1.jar'; Uri = 'https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.19.1/jna-platform-5.19.1.jar'; Sha256 = '3B3864F5B449E9C3C24B16861524B622B086563F44E0CD8384C8EFC5A6052F82' },
    @{ Name = 'xz-1.10.jar'; Uri = 'https://repo1.maven.org/maven2/org/tukaani/xz/1.10/xz-1.10.jar'; Sha256 = '95C63C1A55B22DD6453890A419CC1A640F790BBF7D8AE82DB1E30AEFEFB08888' },
    @{ Name = 'jlayer-1.0.1.jar'; Uri = 'https://repo1.maven.org/maven2/javazoom/jlayer/1.0.1/jlayer-1.0.1.jar'; Sha256 = '850508C837454A1B06017C32A36876FAE516DE1E89A829F725FEE1E6DCC52000' }
)
$libDir = Join-Path $projectRoot 'lib'
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
foreach ($library in $libraries) {
    $destination = Join-Path $libDir $library.Name
    if (-not (Test-Path -LiteralPath $destination)) {
        Invoke-WebRequest -Uri $library.Uri -OutFile $destination
    }
    $actualHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    if ($actualHash -ne $library.Sha256) {
        throw "Checksum mismatch for $($library.Name). Delete the file and retry."
    }
}

$classes = Join-Path $buildRoot 'classes'
$testClasses = Join-Path $buildRoot 'test-classes'
$jarDir = Join-Path $buildRoot 'jar'
New-Item -ItemType Directory -Force -Path $classes,$testClasses,$jarDir | Out-Null
$classpath = (($libraries | ForEach-Object { Join-Path $libDir $_.Name }) -join ';')
$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Filter *.java -Recurse | ForEach-Object FullName)
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\test\java') -Filter *.java -Recurse | ForEach-Object FullName)

& "$jdkHome\bin\javac.exe" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp $classpath -d $classes @mainSources
if ($LASTEXITCODE -ne 0) { throw "Main compilation failed with exit code $LASTEXITCODE" }

& "$jdkHome\bin\javac.exe" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$classes;$classpath" -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed with exit code $LASTEXITCODE" }

& "$jdkHome\bin\java.exe" -ea -cp "$testClasses;$classes;$classpath" io.duckyplayer.SelfTest
if ($LASTEXITCODE -ne 0) { throw "Tests failed with exit code $LASTEXITCODE" }

$appJar = Join-Path $jarDir $appJarName
& "$jdkHome\bin\jar.exe" --create --file $appJar --main-class io.duckyplayer.Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw "JAR creation failed with exit code $LASTEXITCODE" }
foreach ($library in $libraries) {
    Copy-Item -LiteralPath (Join-Path $libDir $library.Name) -Destination $jarDir -Force
}

# Read-only .NET bridge is shipped beside the Java application JAR.
& dotnet build (Join-Path $projectRoot 'clock-probe\ClockProbe.csproj') -c Release -o (Join-Path $jarDir 'clock-probe')
if ($LASTEXITCODE -ne 0) { throw 'Clock helper build failed.' }

if (-not $SkipPackage) {
    $packageDir = Join-Path $buildRoot 'package'
    $appImage = Join-Path $packageDir $appName
    if (Test-Path -LiteralPath $appImage) { Remove-Item -LiteralPath $appImage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
    & "$jdkHome\bin\jpackage.exe" --type app-image --dest $packageDir --name $appName `
        --input $jarDir --main-jar $appJarName --main-class io.duckyplayer.Main `
        --add-modules 'java.desktop,java.logging,jdk.unsupported' `
        --java-options '-Xms256m' --java-options '-Xmx256m' `
        --java-options '-XX:+UseZGC' --java-options '-XX:+ZGenerational' `
        --java-options '-Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw "Packaging failed with exit code $LASTEXITCODE" }
}

Write-Host 'Build and tests succeeded.'
if (-not $SkipPackage) {
    Write-Host "Application: $buildRoot\package\$appName\$appName.exe"
}
