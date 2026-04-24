#!/usr/bin/env pwsh
# Script to build sdmx-proxy Docker image tagged as 'local'.
# Mirrors the CI flow (.github/workflows/pr.yml): multi-stage Docker build
# with repo root as context. No local Gradle build is required — the Dockerfile
# runs ./gradlew bootJar internally.

$ErrorActionPreference = "Stop"

if (-not $env:GPR_USERNAME -or -not $env:GPR_PASSWORD) {
    Write-Host "Error: GPR_USERNAME and GPR_PASSWORD environment variables must be set." -ForegroundColor Red
    Write-Host "They are needed to pull private Gradle dependencies from GitHub Package Registry." -ForegroundColor Red
    exit 1
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$dockerfile = Join-Path $repoRoot "docker\sdmx-proxy.Dockerfile"

Write-Host "Building Docker image..." -ForegroundColor Green

Push-Location $repoRoot
try {
    $env:DOCKER_BUILDKIT = "1"
    docker build `
        -f $dockerfile `
        -t statgpt/statgpt-sdmx-proxy:local `
        --secret id=GPR_USERNAME,env=GPR_USERNAME `
        --secret id=GPR_PASSWORD,env=GPR_PASSWORD `
        .
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker build failed!" -ForegroundColor Red
        exit $LASTEXITCODE
    }

    Write-Host "Docker image built successfully and tagged as 'local'." -ForegroundColor Green
} finally {
    Pop-Location
}
