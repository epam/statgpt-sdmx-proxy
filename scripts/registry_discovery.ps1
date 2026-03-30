<#
.SYNOPSIS
    SDMX registry discovery: fetches structure lists from a registry API and saves each response to a JSON file.

.DESCRIPTION
    Queries an SDMX registry (or compatible API) for the given structure types (e.g. datastructure,
    dataflow, codelist, conceptscheme), requesting each as structure/*/*/* with detail=allstubs and
    references=none, and writes the responses to separate JSON files in the output directory.

    The script does not depend on any specific provider (e.g. IMF). BaseUrl, Accept header, and
    structure types are passed as arguments.

.PARAMETER BaseUrl
    Base URL of the SDMX registry API (no trailing slash). Example: https://api.imf.org/external/sdmx/3.0

.PARAMETER Accept
    Accept header value for structure requests. Example: application/vnd.sdmx.structure+json; version=2.0.0

.PARAMETER StructureTypes
    Array of structure type names to fetch. Each type is requested at {BaseUrl}/structure/{type}/*/*/*
    Common values: datastructure, dataflow, codelist, conceptscheme

.PARAMETER OutputDir
    Directory where JSON files will be written. Default: registry-discovery-output next to the script.

.EXAMPLE
    .\registry_discovery.ps1 -BaseUrl "https://api.imf.org/external/sdmx/3.0" `
        -Accept "application/vnd.sdmx.structure+json; version=2.0.0" `
        -StructureTypes "datastructure","dataflow","codelist","conceptscheme"

.EXAMPLE
    .\registry_discovery.ps1 -BaseUrl "https://registry.example.com/sdmx/v3" `
        -Accept "application/vnd.sdmx.structure+json; version=2.0.0" `
        -StructureTypes "datastructure","dataflow" `
        -OutputDir ".\my-output"

.EXAMPLE
    # BIS (Bank for International Settlements) statistics API
    .\registry_discovery.ps1 -BaseUrl "https://stats.bis.org/api/v2" `
        -Accept "application/vnd.sdmx.structure+json; version=2.0.0" `
        -StructureTypes "datastructure","dataflow","codelist","conceptscheme"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, HelpMessage = "Base URL of the SDMX registry API (no trailing slash)")]
    [string]$BaseUrl,

    [Parameter(Mandatory = $true, HelpMessage = "Accept header for structure requests")]
    [string]$Accept,

    [Parameter(Mandatory = $true, HelpMessage = "Structure types to fetch (e.g. datastructure, dataflow, codelist, conceptscheme)")]
    [string[]]$StructureTypes,

    [string]$OutputDir = (Join-Path $PSScriptRoot "..\registry-discovery-output")
)

$ErrorActionPreference = "Stop"

# Normalize BaseUrl: remove trailing slash
$BaseUrl = $BaseUrl.TrimEnd('/')

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}
$OutputDir = (Resolve-Path $OutputDir).Path
Write-Host "Output directory: $OutputDir" -ForegroundColor Cyan
Write-Host "BaseUrl: $BaseUrl" -ForegroundColor Cyan
Write-Host "Accept: $Accept" -ForegroundColor Cyan
Write-Host "Structure types: $($StructureTypes -join ', ')" -ForegroundColor Cyan

foreach ($type in $StructureTypes) {
    $uri = "$BaseUrl/structure/$type/*/*/*?detail=allstubs&references=none"
    $outFile = Join-Path $OutputDir "$type.json"
    Write-Host "Fetching $type..." -ForegroundColor Green
    try {
        Invoke-WebRequest -Uri $uri -Headers @{ "Accept" = $Accept } -OutFile $outFile -UseBasicParsing
        Write-Host "  -> $outFile" -ForegroundColor Gray
    } catch {
        Write-Host "  Failed: $_" -ForegroundColor Red
        throw
    }
}

Write-Host "Done. Files: $($StructureTypes -join ', ').json" -ForegroundColor Green
