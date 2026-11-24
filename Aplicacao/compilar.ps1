[CmdletBinding()]
param([switch]$Verificar)

$ErrorActionPreference = 'Stop'
$raiz = $PSScriptRoot
$temporarios = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).Path
$compilacao = Join-Path $temporarios ('redelab-build-' + [Guid]::NewGuid())
$classes = Join-Path $compilacao 'classes'
$jarPath = Join-Path $raiz 'RedeLab.jar'

New-Item -ItemType Directory -Path $classes | Out-Null
try {
    $fontes = @(Get-ChildItem -LiteralPath (Join-Path $raiz 'src') -Filter '*.java' -File |
        ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 --release 17 -d $classes @fontes
    if ($LASTEXITCODE -ne 0) { throw 'Falha na compilacao de RedeLab.' }

    if ($Verificar) {
        & javac -encoding UTF-8 --release 17 -cp $classes -d $compilacao (Join-Path $raiz 'verificacao/VerificarRedeLab.java')
        if ($LASTEXITCODE -ne 0) { throw 'Falha na compilacao do teste.' }
        $classpath = $classes + [IO.Path]::PathSeparator + $compilacao
        & java -cp $classpath VerificarRedeLab
        if ($LASTEXITCODE -ne 0) { throw 'A verificacao de TCP e UDP falhou.' }
    }

    & jar --create --file $jarPath --main-class RedeLab -C $classes .
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao empacotar RedeLab.jar.' }
    Write-Host ('JAR criado em ' + $jarPath)
}
finally {
    $absoluto = (Resolve-Path -LiteralPath $compilacao).Path
    $pai = Split-Path -Parent $absoluto
    if ($pai -eq $temporarios.TrimEnd('\','/') -and (Split-Path -Leaf $absoluto) -like 'redelab-build-*') {
        Remove-Item -LiteralPath $absoluto -Recurse -Force
    }
}
