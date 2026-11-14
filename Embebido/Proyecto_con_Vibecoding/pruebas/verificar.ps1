param([switch]$Sanitizadores)

$ErrorActionPreference = 'Stop'
$fuente = Split-Path $PSScriptRoot -Parent
$temporal = Join-Path $env:LOCALAPPDATA ("Temp\opencode\embebido-tests-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporal | Out-Null
$fallas = 0

# Se generan copias de prueba: nunca se modifica Configuracion.h del proyecto
# ni se copian las credenciales locales a los artefactos de prueba.
foreach ($polaridad in @('true', 'false')) {
    $variante = Join-Path $temporal $polaridad
    New-Item -ItemType Directory -Path $variante | Out-Null
    Get-ChildItem $fuente -File | Where-Object {
        $_.Extension -in '.h', '.ino', '.cpp' -and $_.Name -ne 'ConfiguracionWifi.h'
    } | Copy-Item -Destination $variante
    Copy-Item $PSScriptRoot -Destination (Join-Path $variante 'pruebas') -Recurse
    $config = Join-Path $variante 'Configuracion.h'
    $contenido = [IO.File]::ReadAllText($config)
    $patron = '(?m)^constexpr bool LLUVIA_CON_ADC_ALTO = (?:true|false);'
    if (-not [regex]::IsMatch($contenido, $patron)) {
        throw 'No se encontro la constante de polaridad para generar la variante.'
    }
    $contenido = [regex]::Replace($contenido, $patron,
        "constexpr bool LLUVIA_CON_ADC_ALTO = $polaridad;")
    [IO.File]::WriteAllText($config, $contenido, [Text.UTF8Encoding]::new($false))
    $linux = (& wsl.exe -d Ubuntu --exec wslpath -a $variante).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo convertir la ruta para Ubuntu/WSL.' }

    foreach ($suite in @('control_local', 'fsm')) {
        Write-Output "=== $suite / LLUVIA_CON_ADC_ALTO=$polaridad / sanitizadores=$Sanitizadores ==="
        $argumentos = @('-std=c++11', '-Wall', '-Wextra', '-Werror', '-pedantic')
        if ($Sanitizadores) {
            $argumentos += @('-fsanitize=address,undefined', '-fno-omit-frame-pointer')
        }
        $argumentos += @('-I', "$linux/pruebas/dobles", "$linux/pruebas/$suite.cpp",
            '-o', "$linux/$suite")
        & wsl.exe -d Ubuntu --exec g++ @argumentos
        if ($LASTEXITCODE -ne 0) { $fallas++; continue }
        & wsl.exe -d Ubuntu --exec "$linux/$suite"
        if ($LASTEXITCODE -ne 0) { $fallas++ }
    }
}

Write-Output "Artefactos fuera del repositorio: $temporal"
if ($fallas -ne 0) { throw "Fallaron $fallas suites/compilaciones." }
Write-Output 'Todas las suites y ambas polaridades: OK'
