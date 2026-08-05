package com.workshop.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshop.gemini.models.Content;
import com.workshop.gemini.models.Part;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Talks to Gemini over plain HTTP. Intentionally NO abstractions — students
 * should be able to read this top-to-bottom and see exactly what goes on the wire.
 *
 *   Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=...
 */
public class GeminiClient {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;

    public GeminiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Reply container — either plain text OR a tool call. (STEP 4+) */
    public static class Reply {
        public final String text;
        public final ToolCall toolCall;
        public Reply(String text, ToolCall toolCall) {
            this.text = text;
            this.toolCall = toolCall;
        }
    }

    public static class ToolCall {
        public final String name;
        public final JsonNode args;
        public ToolCall(String name, JsonNode args) {
            this.name = name;
            this.args = args;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 1 — single-shot, stateless completion.
    // ─────────────────────────────────────────────────────────────────────
    public String complete(String prompt) throws Exception {
        GeminiRequest geminiRequest = new GeminiRequest(prompt);
        return post(geminiRequest).text();
    }
    
    
    public String complete(List<Content> contents) throws Exception {
        GeminiRequest geminiRequest = new GeminiRequest(contents);
        return post(geminiRequest).text();
    }
    
    public String complete(GeminiRequest geminiRequest) throws Exception {
        return post(geminiRequest).text();
    }
    
    
    
    // ─────────────────────────────────────────────────────────────────────
    // STEP 2/3 — multi-turn chat. Optionally asks for JSON output.
    //   If conv has a system prompt set, it's attached as `systemInstruction`.
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // STEP 4/5 — chat with tool declarations. Model may return a functionCall.
    // ─────────────────────────────────────────────────────────────────────
    public Reply chatWithTools(GeminiRequest request) throws Exception {
        GeminiResponse response = post(request);
        Part.FunctionCall fc = response.functionCall();
        
        if (fc != null) {
            return new Reply(null, new ToolCall(fc.name, fc.args));
        }
        return new Reply(response.text(), null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 3+ (optional) — turn text into spoken audio via Gemini TTS.
    //   Returns a WAV byte stream (24kHz, 16-bit, mono) ready for playback.
    // ─────────────────────────────────────────────────────────────────────
    public byte[] synthesizeSpeech(String text) throws Exception {
        String ttsUrl =
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-2.5-flash-preview-tts:generateContent";

        GeminiRequest request = new GeminiRequest(
                List.of(new Content(null,
                        List.of(new Part(text)))));

        request.generationConfig = new GeminiRequest.GenerationConfig();
        request.generationConfig.responseModalities = List.of("AUDIO");
        request.generationConfig.speechConfig = new GeminiRequest.SpeechConfig("Kore");

        String requestBody = json.writeValueAsString(request);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ttsUrl + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("Gemini TTS error " + res.statusCode() + ": " + res.body());
        }

        GeminiResponse response = json.readValue(res.body(), GeminiResponse.class);
        String b64 = response.inlineData();
        if (b64.isEmpty()) {
            throw new RuntimeException(
                "Gemini TTS returned no audio (finishReason=" + response.finishReason() + "). " +
                "Common cause: the input text was too short or got filtered. " +
                "Try a fuller utterance.");
        }
        byte[] pcm = java.util.Base64.getDecoder().decode(b64);
        return wrapPcmAsWav(pcm, 24000, 1, 16);
    }

    /** Prepend a 44-byte RIFF/WAV header to raw PCM so AudioSystem can play it. */
    private byte[] wrapPcmAsWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate   = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize   = pcm.length;
        int chunkSize  = 36 + dataSize;
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44 + dataSize)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(chunkSize).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1)        // PCM
         .putShort((short) channels).putInt(sampleRate)
         .putInt(byteRate).putShort((short) blockAlign)
         .putShort((short) bitsPerSample);
        h.put("data".getBytes()).putInt(dataSize).put(pcm);
        return h.array();
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────────────

    private GeminiResponse post(GeminiRequest request) throws Exception {
        String requestBody = json.writeValueAsString(request);
        appendLog("→ REQUEST\n" + json.writerWithDefaultPrettyPrinter().writeValueAsString(request) + "\n");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            appendLog("← ERROR " + res.statusCode() + "\n" + res.body() + "\n");
            throw new RuntimeException("Gemini error " + res.statusCode() + ": " + res.body());
        }
        appendLog("← RESPONSE\n" + res.body() + "\n");
        return json.readValue(res.body(), GeminiResponse.class);
    }

    /** Append to gemini.log so traffic can be tailed in a split terminal. */
    private static void appendLog(String entry) {
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("gemini.log"),
                    entry + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }
}
