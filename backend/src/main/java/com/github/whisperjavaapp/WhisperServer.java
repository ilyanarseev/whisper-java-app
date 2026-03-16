package com.github.whisperjavaapp;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public class WhisperServer {
	private static final int PORT = 9876;
	private static Path MODEL_PATH;

	public static void main(String[] args) throws IOException {
		String modelPathProp = System.getProperty("whisper.model.path");
		if (modelPathProp != null) {
			MODEL_PATH = Paths.get(modelPathProp);
		} else {
			MODEL_PATH = Path.of("models", "ggml-tiny.bin");
		}

		System.out.println("=== Whisper-сервер ===");
		System.out.println("Порт: " + PORT);
		System.out.println("Путь к модели: " + MODEL_PATH.toAbsolutePath());
		System.out.println("Модель существует: " + Files.exists(MODEL_PATH));

		if (!Files.exists(MODEL_PATH)) {
			System.err.println("ОШИБКА: Модель не найдена!");
			System.err.println("Ожидается файл: " + MODEL_PATH.toAbsolutePath());
			System.err.println("Скачайте модель и положите по указанному пути");
			System.exit(1);
		}

		System.out.println("Java version: " + System.getProperty("java.version"));
		System.out.println("OS: " + System.getProperty("os.name"));
		System.out.println("OS arch: " + System.getProperty("os.arch"));
		System.out.println("Temp dir: " + System.getProperty("java.io.tmpdir"));
		System.out.println("Library path: " + System.getProperty("java.library.path"));

		System.out.println("Запуск сервера...");

		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
			server.createContext("/transcribe", new TranscribeHandler());
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();

			System.out.println("Сервер готов к работе на порту " + PORT);
			System.out.println("Ожидание запросов...");

		} catch (Exception e) {
			System.err.println("Ошибка запуска сервера: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}

	static class TranscribeHandler implements HttpHandler {
		private final WhisperTranscriber transcriber;

		public TranscribeHandler() {
			try {
				System.out.println("Инициализация WhisperTranscriber...");
				this.transcriber = new WhisperTranscriber(MODEL_PATH);
				System.out.println("WhisperTranscriber инициализирован");
			} catch (Exception e) {
				System.err.println("Ошибка инициализации WhisperTranscriber: " + e.getMessage());
				e.printStackTrace();
				throw new RuntimeException("Не удалось инициализировать Whisper", e);
			}
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}

			String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
			System.out.println("Получен запрос от " + clientIp);

			try {
				byte[] audioData = exchange.getRequestBody().readAllBytes();
				System.out.println("   Размер аудио: " + audioData.length + " байт");

				File tempFile = File.createTempFile("whisper_", ".wav");
				Files.write(tempFile.toPath(), audioData);

				long startTime = System.currentTimeMillis();
				String result = transcriber.transcribe(tempFile);
				long elapsed = System.currentTimeMillis() - startTime;

				System.out.println("   Распознано за " + elapsed + " мс");
				System.out
						.println("   Результат: " + (result.length() > 50 ? result.substring(0, 50) + "..." : result));

				byte[] response = result.getBytes("UTF-8");
				exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
				exchange.sendResponseHeaders(200, response.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(response);
				}

				tempFile.delete();

			} catch (Exception e) {
				System.err.println("Ошибка обработки запроса: " + e.getMessage());
				e.printStackTrace();
				String error = "Ошибка: " + e.getMessage();
				exchange.sendResponseHeaders(500, error.getBytes().length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(error.getBytes());
				}
			}
		}
	}

	static class WhisperTranscriber {
		private final WhisperJNI whisper;
		private final Path modelPath;

		public WhisperTranscriber(Path modelPath) throws IOException {
			System.out.println("Загрузка whisper-jni...");

			try {
				WhisperJNI.loadLibrary();
				System.out.println("whisper-jni загружен");
			} catch (UnsatisfiedLinkError e) {
				System.err.println("Ошибка загрузки нативной библиотеки: " + e.getMessage());
				System.err.println("Возможно, не хватает libgomp. Установите:");
				System.err.println("  - Ubuntu/Debian: sudo apt install libgomp1");
				System.err.println("  - Arch: sudo pacman -S gcc-libs");
				System.err.println("  - Windows: установите VC++ Redistributable");
				throw new IOException("Не удалось загрузить нативную библиотеку", e);
			}

			this.whisper = new WhisperJNI();
			this.modelPath = modelPath;

			if (!Files.exists(modelPath)) {
				throw new IOException("Модель не найдена: " + modelPath.toAbsolutePath());
			}

			System.out.println("Модель найдена: " + modelPath.getFileName());
		}

		public String transcribe(File audioFile) throws Exception {
			AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile);
			byte[] bytes = audioStream.readAllBytes();

			float[] samples = new float[bytes.length / 2];
			for (int i = 0; i < samples.length; i++) {
				short s = (short) ((bytes[i * 2 + 1] << 8) | (bytes[i * 2] & 0xFF));
				samples[i] = s / 32768.0f;
			}

			WhisperContext ctx = whisper.init(modelPath);
			if (ctx == null) {
				throw new RuntimeException("Не удалось инициализировать контекст Whisper");
			}

			WhisperFullParams params = new WhisperFullParams();
			params.language = "auto";

			int result = whisper.full(ctx, params, samples, samples.length);
			if (result != 0) {
				ctx.close();
				throw new RuntimeException("Ошибка транскрибации: код " + result);
			}

			int numSegments = whisper.fullNSegments(ctx);
			StringBuilder fullText = new StringBuilder();

			for (int i = 0; i < numSegments; i++) {
				fullText.append(whisper.fullGetSegmentText(ctx, i)).append(" ");
			}

			ctx.close();
			return fullText.toString().trim();
		}
	}
}
