$ErrorActionPreference = "Stop"
docker compose up --build -d
docker compose ps
Write-Output ""
Write-Output "Peachtree Dispatch: http://localhost:5173"
Write-Output "API documentation:  http://localhost:8000/docs"
