package com.github.whisperjavaapp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;

public class VoiceRecorderApp extends JFrame {
	private JButton recordButton;
	private JTextArea resultArea;
	private volatile boolean isRecording = false;

	private ExecutorService recordingExecutor = Executors.newSingleThreadExecutor();

	private ExecutorService transcriptionExecutor = Executors.newCachedThreadPool();

	private final Object transcriptLock = new Object();

	private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16000, 16, 1, true, false);
	private static final int RECORD_SECONDS = 10;

	public VoiceRecorderApp() {
		initUI();
		setTitle("Голосовой ввод Whisper");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		pack();
		setLocationRelativeTo(null);
		setResizable(false);
	}

	private void initUI() {
		setLayout(new BorderLayout(10, 10));

		JPanel buttonPanel = new JPanel();

		buttonPanel.setPreferredSize(new Dimension(200, 40));

		recordButton = new JButton("Начать запись (10 сек)");
		recordButton.setFont(new Font("Arial", Font.BOLD, 14));
		recordButton.addActionListener(this::startRecording);
		buttonPanel.add(recordButton);

		resultArea = new JTextArea(5, 40);
		resultArea.setEditable(false);
		resultArea.setLineWrap(true);
		resultArea.setWrapStyleWord(true);
		resultArea.setText("Результат распознавания появится здесь...");
		JScrollPane scrollPane = new JScrollPane(resultArea);

		add(buttonPanel, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);

		JLabel statusLabel = new JLabel("Готово к работе. Нажмите кнопку и говорите.");
		statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		add(statusLabel, BorderLayout.SOUTH);
	}

	private void startRecording(ActionEvent e) {
		if (isRecording)
			return;

		recordButton.setEnabled(false);
		recordButton.setText("Запись... (0/" + RECORD_SECONDS + " сек)");
		resultArea.setText("");

		recordingExecutor.submit(() -> {
			try {
				recordAudio();
			} catch (Exception ex) {
				SwingUtilities.invokeLater(() -> {
					resultArea.setText("Ошибка записи: " + ex.getMessage());
					recordButton.setEnabled(true);
					recordButton.setText("Начать запись (10 сек)");
				});
				ex.printStackTrace();
			}
		});
	}

	private void recordAudio() throws Exception {
		isRecording = true;

		TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(
				new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT));
		microphone.open(AUDIO_FORMAT);
		microphone.start();

		ByteArrayOutputStream fullAudioBuffer = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		long startTime = System.currentTimeMillis();
		long lastProcessTime = startTime;
		final int PROCESS_INTERVAL = 2000;
		int lastProcessedBytes = 0;

		StringBuilder fullTranscript = new StringBuilder();

		while ((System.currentTimeMillis() - startTime) < RECORD_SECONDS * 1000) {
			int bytesRead = microphone.read(buffer, 0, buffer.length);
			fullAudioBuffer.write(buffer, 0, bytesRead);

			int elapsedSeconds = (int) ((System.currentTimeMillis() - startTime) / 1000);
			SwingUtilities.invokeLater(
					() -> recordButton.setText("Запись... (" + elapsedSeconds + "/" + RECORD_SECONDS + " сек)"));

			long currentTime = System.currentTimeMillis();
			if (currentTime - lastProcessTime >= PROCESS_INTERVAL) {
				lastProcessTime = currentTime;

				byte[] fullAudio = fullAudioBuffer.toByteArray();
				if (fullAudio.length <= lastProcessedBytes) {
					continue;
				}

				byte[] newAudio = new byte[fullAudio.length - lastProcessedBytes];
				System.arraycopy(fullAudio, lastProcessedBytes, newAudio, 0, newAudio.length);
				lastProcessedBytes = fullAudio.length;

				transcriptionExecutor.submit(() -> {
					try {
						String partialText = transcribeAudioChunk(newAudio);
						if (!partialText.trim().isEmpty()) {
							synchronized (transcriptLock) {
								fullTranscript.append(partialText).append(" ");

								SwingUtilities.invokeLater(() -> {
									resultArea.setText(fullTranscript.toString());
								});
							}
						}
					} catch (Exception ex) {
						System.err.println("Ошибка распознавания чанка: " + ex.getMessage());
					}
				});
			}
		}

		microphone.stop();
		microphone.close();
		isRecording = false;

		byte[] finalAudioData = fullAudioBuffer.toByteArray();

		String finalText = transcribeAudioChunk(finalAudioData);

		Files.write(Paths.get("result.txt"), finalText.getBytes(StandardCharsets.UTF_8));

		SwingUtilities.invokeLater(() -> {
			resultArea.setText(finalText);
			recordButton.setEnabled(true);
			recordButton.setText("Начать запись (10 сек)");
		});
	}

	private String transcribeAudioChunk(byte[] audioData) {
		try {
			if (audioData.length < 16000) {
				return "";
			}

			ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
			AudioInputStream audioStream = new AudioInputStream(
					bais,
					AUDIO_FORMAT,
					audioData.length / AUDIO_FORMAT.getFrameSize());

			File tempAudioFile = File.createTempFile("chunk", ".wav");
			AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, tempAudioFile);

			String text = transcribeWithWhisper(tempAudioFile);
			tempAudioFile.delete();

			return text;
		} catch (Exception e) {
			System.err.println("Ошибка обработки чанка: " + e.getMessage());
			return "";
		}
	}

	private String transcribeWithWhisper(File audioFile) {
		try {
			WhisperJNI.loadLibrary();
			WhisperJNI.setLibraryLogger(null);
			WhisperJNI whisper = new WhisperJNI();

			float[] samples = readWavFileAsFloat(audioFile);

			Path modelPath = Path.of("models", "ggml-tiny.bin");
			WhisperContext ctx = whisper.init(modelPath);

			WhisperFullParams params = new WhisperFullParams();

			params.language = "auto";

			int result = whisper.full(ctx, params, samples, samples.length);

			if (result != 0) {
				ctx.close();
				return "Ошибка транскрибации: код " + result;
			}

			int numSegments = whisper.fullNSegments(ctx);
			StringBuilder fullText = new StringBuilder();

			for (int i = 0; i < numSegments; i++) {
				String segmentText = whisper.fullGetSegmentText(ctx, i);
				fullText.append(segmentText).append(" ");
			}

			ctx.close();
			return fullText.toString().trim();

		} catch (Exception e) {
			e.printStackTrace();
			return "Ошибка распознавания: " + e.getMessage();
		}
	}

	private float[] readWavFileAsFloat(File wavFile) throws Exception {
		AudioInputStream audioStream = AudioSystem.getAudioInputStream(wavFile);
		byte[] bytes = audioStream.readAllBytes();

		float[] samples = new float[bytes.length / 2];
		for (int i = 0; i < samples.length; i++) {
			short s = (short) ((bytes[i * 2 + 1] << 8) | (bytes[i * 2] & 0xFF));
			samples[i] = s / 32768.0f;
		}

		return samples;
	}

	@Override
	public void dispose() {
		recordingExecutor.shutdown();
		transcriptionExecutor.shutdown();
		super.dispose();
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			new VoiceRecorderApp().setVisible(true);
		});
	}
}
