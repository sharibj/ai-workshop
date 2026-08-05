package com.workshop.speaker;

import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

import com.workshop.gemini.GeminiClient;

/**
 * The "speaker" of our agent — turns a name into output the human can perceive.
 * <p>
 * Always: print an ASCII banner to the terminal.
 * If USE_REAL_TTS: also call Gemini TTS and play the audio through the
 * JDK's built-in javax.sound.sampled.
 * <p>
 * Each speak() call is attributed to a Source so students can SEE who decided
 * to call the speaker:
 * STEP 3 → Source.CODE (yellow): our Java code parsed JSON and called speak.
 * STEP 4 → Source.LLM  (green):  the LLM itself emitted a tool call.
 */
public class Speaker {
	
	private static final String RESET = "[0m";
	private final GeminiClient gemini;
	private final boolean useRealTts;
	
	// STEP 5 demo: simulate a flaky speaker that fails ~50% of the time.
	// The model sees the failure in the tool result and must DECIDE to retry.
	// This is what makes the loop's value visible — without flakiness, one
	// tool call would suffice and there'd be nothing for the loop to do.
	private final boolean isBroken;
	
	
	public Speaker(GeminiClient gemini, boolean useRealTts) {
		this.gemini = gemini;
		this.useRealTts = useRealTts;
		this.isBroken = false;
	}
	
	public Speaker(GeminiClient gemini, boolean useRealTts,  boolean isBroken) {
		this.gemini = gemini;
		this.useRealTts = useRealTts;
		this.isBroken = isBroken;
	}
	
	public boolean isBroken() {
		return isBroken;
	}
	public void speak(String name) {
		speak(name, Source.CODE);
	}
	
	// source only controls how the banner is labelled (direct code call vs model tool call)
	public void speak(String name, Source source) {
		printBanner(name, source);
		if (useRealTts) {
			try {
				String utterance = "You are " + name + ".";
				byte[] wav = gemini.synthesizeSpeech(utterance);
				playWav(wav);
			} catch (Exception e) {
				System.out.println("[tts failed: " + e.getMessage() + "]");
			}
		}
	}
	
	private void printBanner(String name, Source source) {
		String c = source.color;
		System.out.println();
		System.out.println(c + "🔊 ──── " + source.emoji + "  " + source.label + " ────────────────" + RESET);
		for (String line : BannerFont.render(name).split("\n")) {
			System.out.println(c + "   " + line + RESET);
		}
		System.out.println(c + "─────────────────────────────────────────" + RESET);
	}
	
	
	private void playWav(byte[] wavBytes) throws Exception {
		try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
			Clip clip = AudioSystem.getClip();
			Object lock = new Object();
			clip.addLineListener(ev -> {
				if (ev.getType() == LineEvent.Type.STOP) {
					synchronized (lock) {
						lock.notifyAll();
					}
				}
			});
			clip.open(in);
			clip.start();
			synchronized (lock) {
				lock.wait(10_000);
			}
			clip.stop();
			clip.close();
		}
	}
	
	
	public enum Source {
		CODE("👨‍💻", "code-driven", "[1;33m"),  // yellow
		LLM("🤖", "LLM-driven", "[1;32m"); // green
		
		final String emoji;
		final String label;
		final String color;
		
		
		Source(String e, String l, String c) {
			emoji = e;
			label = l;
			color = c;
		}
	}
}
