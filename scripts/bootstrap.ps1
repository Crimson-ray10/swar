# iTantra Bootstrap Script for Windows PowerShell
Write-Host "Bootstrapping iTantra environment..." -ForegroundColor Cyan

# 1. Create Virtual Environment if absent
if (-not (Test-Path ".\.venv\Scripts\python.exe")) {
    Write-Host "Creating Python virtual environment..." -ForegroundColor Yellow
    python -m venv .venv
}

# 2. Upgrade pip and install core dependencies
Write-Host "Installing/verifying Python dependencies..." -ForegroundColor Yellow
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"

# 3. Create required runtime directories
$dirs = @("models/cache", "models/prompts", "runs", "data/manifests", "data/splits")
foreach ($d in $dirs) {
    if (-not (Test-Path $d)) {
        New-Item -ItemType Directory -Path $d -Force | Out-Null
    }
}

# 4. Copy .env if not present
if (-not (Test-Path ".env")) {
    Copy-Item .env.example .env
    Write-Host "Created .env from .env.example" -ForegroundColor Green
}

# 5. Run system doctor
.\scripts\doctor.ps1
