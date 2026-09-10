# iTantra Offline Demo Bundle Packager
param(
    [string]$OutputDir = "bundle"
)

Write-Host "Creating iTantra Offline Demo Bundle in '$OutputDir'..." -ForegroundColor Cyan

# 1. Create bundle directories
$bundleDirs = @(
    "$OutputDir/configs",
    "$OutputDir/models/prompts",
    "$OutputDir/models/cache",
    "$OutputDir/tests/fixtures",
    "$OutputDir/evaluation/reports",
    "$OutputDir/scripts",
    "$OutputDir/dist"
)

foreach ($d in $bundleDirs) {
    if (-not (Test-Path $d)) {
        New-Item -ItemType Directory -Path $d -Force | Out-Null
    }
}

# 2. Copy configs and manifests
Copy-Item "configs/*.yaml" "$OutputDir/configs/" -Force
Copy-Item "models/manifest.yaml" "$OutputDir/models/" -Force
Copy-Item ".env.example" "$OutputDir/" -Force

# 3. Copy test audio fixtures
Copy-Item "tests/fixtures/*.wav" "$OutputDir/tests/fixtures/" -Force

# 4. Copy built frontend distribution
if (Test-Path "apps/web/dist") {
    Copy-Item "apps/web/dist/*" "$OutputDir/dist/" -Recurse -Force
    Write-Host "[OK] Packaged built frontend UI distribution" -ForegroundColor Green
}

# 5. Copy evaluation matrix reports
if (Test-Path "evaluation/reports/benchmark_matrix.csv") {
    Copy-Item "evaluation/reports/benchmark_matrix.csv" "$OutputDir/evaluation/reports/" -Force
}

# 6. Copy runner scripts
Copy-Item "scripts/doctor.ps1" "$OutputDir/scripts/" -Force
Copy-Item "scripts/run_demo.py" "$OutputDir/scripts/" -Force

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "iTantra Demo Bundle created successfully in '$OutputDir'" -ForegroundColor Green
Write-Host "Contains all offline configs, audio fixtures, UI build, and diagnostic scripts." -ForegroundColor Green
Write-Host "=================================================" -ForegroundColor Cyan
