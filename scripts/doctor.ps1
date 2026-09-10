# iTantra Diagnostic Doctor Script
param(
    [switch]$CheckModels
)

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "          iTantra System Health Doctor          " -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan

$allPassed = $true

# 1. Python Environment Check
$pythonExe = ".\.venv\Scripts\python.exe"
if (Test-Path $pythonExe) {
    $pyVersion = & $pythonExe -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}')"
    Write-Host "[OK] Python detected: $pyVersion ($pythonExe)" -ForegroundColor Green
} else {
    Write-Host "[FAIL] Virtual environment python ($pythonExe) not found! Run scripts/bootstrap.ps1" -ForegroundColor Red
    $allPassed = $false
}

# 2. Node.js Check
try {
    $nodeVersion = & node --version
    Write-Host "[OK] Node.js detected: $nodeVersion" -ForegroundColor Green
} catch {
    Write-Host "[WARN] Node.js not found in PATH. Web frontend build requires Node 18+." -ForegroundColor Yellow
}

# 3. GPU / CUDA Diagnostics
try {
    $gpuOutput = & nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
    Write-Host "[OK] NVIDIA GPU detected: $gpuOutput" -ForegroundColor Green
} catch {
    Write-Host "[INFO] No discrete NVIDIA GPU detected via nvidia-smi (CPU mode will be used)" -ForegroundColor Gray
}

# 4. Core Config Files Check
$requiredConfigs = @(
    "configs/languages.yaml",
    "configs/wire_profiles.yaml",
    "configs/app.local.yaml",
    "models/manifest.yaml"
)

foreach ($cfg in $requiredConfigs) {
    if (Test-Path $cfg) {
        Write-Host "[OK] Config file present: $cfg" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] Missing required config: $cfg" -ForegroundColor Red
        $allPassed = $false
    }
}

# 5. Core Python Libraries Check
if (Test-Path $pythonExe) {
    $libsCheck = & $pythonExe -c "
errors = []
for mod in ['fastapi', 'uvicorn', 'pydantic', 'numpy', 'soundfile', 'sentencepiece']:
    try:
        __import__(mod)
    except ImportError as e:
        errors.append(f'{mod}: {e}')
if errors:
    print('FAIL: ' + '; '.join(errors))
else:
    print('OK')
"
    if ($libsCheck -match "^OK") {
        Write-Host "[OK] Core engine Python modules imported successfully" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] Python dependencies missing: $libsCheck" -ForegroundColor Red
        $allPassed = $false
    }
}

# 6. Model Checks (Optional flag -CheckModels)
if ($CheckModels) {
    Write-Host "`nChecking downloaded model checkpoints..." -ForegroundColor Cyan
    $modelDirs = @("models/cache/indic-conformer", "models/cache/indicf5", "models/cache/faster-whisper-small")
    foreach ($md in $modelDirs) {
        if (Test-Path $md) {
            Write-Host "[OK] Model checkpoint present: $md" -ForegroundColor Green
        } else {
            Write-Host "[INFO] Model cache missing: $md (Use scripts/download_models.ps1 or run mock mode)" -ForegroundColor Yellow
        }
    }
}

Write-Host "================================================" -ForegroundColor Cyan
if ($allPassed) {
    Write-Host "System status: HEALTHY (Ready for execution)" -ForegroundColor Green
    exit 0
} else {
    Write-Host "System status: ISSUES DETECTED" -ForegroundColor Red
    exit 1
}
