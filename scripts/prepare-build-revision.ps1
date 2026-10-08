param(
    [Parameter(Mandatory = $true)][string]$Revision,
    [string]$ContextDirectory
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$resolved = & git -C $repo rev-parse --verify --end-of-options "$Revision^{commit}"
if ($LASTEXITCODE -ne 0 -or -not $resolved) { throw 'Revisao Git invalida.' }
$selectedSha = $resolved.Trim()
if ($selectedSha -notmatch '^[a-f0-9]{40}$') { throw 'Revisao Git invalida.' }
if ($ContextDirectory) {
    # Somente arquivos rastreados no commit. Nao altera ou remove o checkout.
    $contextPath = [System.IO.Path]::GetFullPath($ContextDirectory)
    if (Test-Path -LiteralPath $contextPath) { throw 'O destino do contexto ja existe; nada sera sobrescrito ou removido.' }
    $archivePath = Join-Path ([System.IO.Path]::GetTempPath()) ('poprc-build-' + [guid]::NewGuid() + '.tar')
    try {
        & git -C $repo archive --format=tar --output=$archivePath $selectedSha
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao exportar a revisao selecionada.' }
        New-Item -ItemType Directory -Path $contextPath | Out-Null
        & tar -xf $archivePath -C $contextPath
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao extrair o contexto; nao usar este destino no build.' }
    } finally {
        if (Test-Path -LiteralPath $archivePath) { Remove-Item -LiteralPath $archivePath -Force }
    }
    Write-Output "BUILD_CONTEXT=$contextPath"
} else {
    $checkoutSha = (& git -C $repo rev-parse HEAD).Trim()
    if ($selectedSha -ne $checkoutSha) { throw 'Checkout diferente da revisao selecionada. Use -ContextDirectory para exportar essa revisao.' }
    $changes = & git -C $repo status --porcelain --untracked-files=all
    if ($LASTEXITCODE -ne 0 -or $changes) { throw 'Ha alteracoes ou arquivos nao rastreados locais. Preserve-os; use -ContextDirectory para um contexto isolado.' }
    # Modo informativo; para publicar, use o snapshot, sem arquivos ignorados.
}
Write-Output "APP_REVISION=$selectedSha"
