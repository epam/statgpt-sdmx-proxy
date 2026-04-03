#!/usr/bin/env pwsh
# Script to build sdmx-proxy and create Docker image tagged as 'local'

$ErrorActionPreference = "Stop"

Write-Host "Building sdmx-proxy with Gradle..." -ForegroundColor Green

# Build the project using Gradle
# This will automatically run prepareFilesForDocker task which copies JAR and Dockerfile to build/docker/backend
gradle clean
gradle build -x test --full-stacktrace --parallel --no-daemon

if ($LASTEXITCODE -ne 0) {
    Write-Host "Gradle build failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "Gradle build completed successfully." -ForegroundColor Green
Write-Host "Building Docker image..." -ForegroundColor Green

# Change to the docker build directory
$dockerDir = Join-Path $PSScriptRoot "..\build\docker\backend"

if (-not (Test-Path $dockerDir)) {
    Write-Host "Error: Docker directory not found at $dockerDir" -ForegroundColor Red
    Write-Host "Make sure Gradle build completed successfully." -ForegroundColor Red
    exit 1
}

# Build Docker image
Push-Location $dockerDir
try {
    docker build -t statgpt/statgpt-sdmx-proxy:local .
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker build failed!" -ForegroundColor Red
        exit $LASTEXITCODE
    }

    Write-Host "Docker image built successfully and tagged as 'local'." -ForegroundColor Green
} finally {
    Pop-Location
}
