#!/usr/bin/env pwsh
Write-Host "Запуск Whisper-бэкенда в Docker"

# Проверка наличия модели
if (-not (Test-Path "models/ggml-tiny.bin")) {
    Write-Host "Модель не найдена!"
    Write-Host "Скачайте модель:"
    Write-Host "wget -O models/ggml-tiny.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    exit 1
}

# Проверка наличия JAR
if (-not (Test-Path "backend/target/whisper-backend-1.0.0-jar-with-dependencies.jar")) {
    Write-Host "JAR не найден! Собираю..."
    Push-Location backend
    mvn clean package
    Pop-Location
}

# Сборка образа
Write-Host "Сборка Docker образа..."
docker build -t whisper-backend .

# Запуск контейнера
Write-Host "Запуск контейнера..."
docker run --rm -p 9876:9876 whisper-backend
