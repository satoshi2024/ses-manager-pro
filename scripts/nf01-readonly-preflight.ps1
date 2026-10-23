$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$sourceFile = Join-Path $repositoryRoot 'src/main/java/com/ses/nf01/preflight/LifecycleAuthorizationPreflight.java'
$driverRoot = Join-Path $env:USERPROFILE '.m2/repository/com/mysql/mysql-connector-j'

foreach ($name in @('NF01_PREFLIGHT_SCHEMA', 'NF01_PREFLIGHT_CODE_BASELINE', 'NF01_PREFLIGHT_OUTPUT_DIR', 'NF01_PREFLIGHT_JDBC_URL', 'NF01_PREFLIGHT_DB_USER', 'NF01_PREFLIGHT_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Required preflight environment variable is missing: $name"
    }
}

$driver = Get-ChildItem -LiteralPath $driverRoot -Recurse -Filter 'mysql-connector-j-*.jar' -File -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Directory.Name } -Descending |
    Select-Object -First 1
if ($null -eq $driver) {
    throw 'MySQL Connector/J was not found in the local Maven cache.'
}

$classesDirectory = Join-Path $env:TEMP ('nf01-preflight-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classesDirectory | Out-Null

& javac --release 17 -encoding UTF-8 -d $classesDirectory $sourceFile
if ($LASTEXITCODE -ne 0) {
    throw 'NF01 preflight compilation failed.'
}

$classPath = $classesDirectory + [IO.Path]::PathSeparator + $driver.FullName
& java -cp $classPath com.ses.nf01.preflight.LifecycleAuthorizationPreflight
exit $LASTEXITCODE
