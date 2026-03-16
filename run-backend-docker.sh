#!/bin/bash
echo "Запуск Whisper-бэкенда в Docker"

# Проверка наличия модели
if [ ! -f "models/ggml-tiny.bin" ]; then
	echo "Модель не найдена!"
	echo "Скачайте модель:"
	echo "wget -O models/ggml-tiny.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
	exit 1
fi

# Проверка наличия JAR
if [ ! -f "backend/target/whisper-backend-1.0.0-jar-with-dependencies.jar" ]; then
	echo "JAR не найден! Собираю..."
	cd backend && mvn clean package && cd ..
fi

# Сборка образа
echo "Сборка Docker образа..."
docker build -t whisper-backend .

# Запуск контейнера
echo "Запуск контейнера..."
docker run --rm -p 9876:9876 whisper-backend
