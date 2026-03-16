FROM eclipse-temurin:11-jre-jammy

RUN apt-get update && apt-get install -y \
    libgomp1 \
    libpulse0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY backend/target/whisper-backend-1.0.0-jar-with-dependencies.jar backend.jar
COPY models/ggml-tiny.bin models/

RUN useradd -m -u 1000 appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 9876

CMD ["java", "-jar", "backend.jar"]
