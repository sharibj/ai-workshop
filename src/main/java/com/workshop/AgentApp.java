package com.workshop;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshop.constants.Role;
import com.workshop.gemini.GeminiClient;
import com.workshop.gemini.GeminiRequest;
import com.workshop.gemini.GeminiRequest.SystemInstruction;
import com.workshop.gemini.models.Content;
import com.workshop.gemini.models.Part;
import com.workshop.models.NameReply;
import com.workshop.speaker.Speaker;
import com.workshop.tools.SpeakTool;
import com.workshop.tools.ToolRegistry;

import static com.workshop.constants.Prompts.SAY_MY_NAME_SYSTEM_PROMPT;
import static com.workshop.constants.Prompts.SAY_MY_NAME_WITH_TOOLS_SYSTEM_PROMPT;
//import static com.workshop.constants.Prompts.SAY_MY_NAME_WITH_SPEAK_TOOL_SYSTEM_PROMPT;

/**
 * AgentApp — the only entry point.
 */
public class AgentApp {
	
	private static final boolean USE_REAL_TTS = true;
	private static final List<Content> history = new ArrayList<>();
	
	
	public static void main(String[] args) {
		Scanner in = new Scanner(System.in);
		System.out.println("Say-My-Name agent ready. Type 'exit' to quit.");
		while (true) {
			System.out.print("\nyou> ");
			if (!in.hasNextLine()) {
				break;
			}
			String userInput = in.nextLine().trim();
			if (userInput.isEmpty()) {
				continue;
			}
			if (userInput.equalsIgnoreCase("exit")) {
				break;
			}
			
			try {
				processUserInput(userInput);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	
	/**
	 * Workshop Format:
	 * <p>
	 * // STEP 1: Say my name
	 * //		step1(userInput);
	 * <p>
	 * // STEP 2: Remember my name
	 * // the memory is nothing but a record of all conversations that forms context
	 * //		step2(userInput);
	 * <p>
	 * // STEP 3: Say my name... literally
	 * // the system prompt is how we shape the model's behavior.
	 * //		step3(userInput);
	 * <p>
	 * <p>
	 * // STEP 4: Say my name... automatically
	 * // Use tool call
	 * //		step4(userInput);
	 * <p>
	 * // STEP 5: Keep trying until you say my name
	 * //Agent loop
	 * //		step5(userInput);
	 *
	 */
	private static void processUserInput(String userInput) throws Exception {
		step5(userInput);
	}
	
	
	private static void step5(String userInput) throws Exception {
		GeminiClient gemini = getGeminiClient();
		
		// flaky speaker: fails ~70% of the time so the model must retry
		Speaker speaker = new Speaker(gemini, USE_REAL_TTS, true);
		ToolRegistry tools = new ToolRegistry();
		tools.register(new SpeakTool(speaker));
		
		history.add(new Content(Role.USER, userInput));
		
		// agent loop: keep going until the model returns plain text
		for (int step = 1; step <= 6; step++) {
			System.out.println("🔁 step " + step + " — asking model...");
			GeminiRequest geminiRequest = new GeminiRequest(history);
			// system prompt: instruct the model to retry on failure
			geminiRequest.systemInstruction =
					new SystemInstruction("Retry tool call if it fails. Keep trying until success");
			
			geminiRequest.setTools(tools);
			
			GeminiClient.Reply reply = gemini.chatWithTools(geminiRequest);
			
			if (reply.toolCall != null) {
				// record the tool call in history so the model sees it next turn
//				System.out.println("  ↳ 🤖 tool call:   " + reply.toolCall.name + " " + reply.toolCall.args);
				history.add(new Content(Role.MODEL,
						List.of(Part.ofFunctionCall(reply.toolCall.name, reply.toolCall.args))));
				String result = tools.execute(reply.toolCall.name, reply.toolCall.args);
				// record the tool result so the model knows what happened
//				System.out.println("  ↳ 📦 tool result: " + result);
				history.add(new Content(Role.USER,
						List.of(Part.ofFunctionResponse(reply.toolCall.name, result))));
			} else {
				// model returned text — it's done
				history.add(new Content(Role.MODEL, reply.text));
				System.out.println("bot> " + reply.text);
				break;
			}
		}
	}
	
	
	private static void step4(String userInput) throws Exception {
		GeminiClient gemini = getGeminiClient();
		history.add(new Content(Role.USER, userInput));
		GeminiRequest geminiRequest = new GeminiRequest(history);
		// system prompt: only say the name when explicitly asked
		geminiRequest.systemInstruction = new SystemInstruction(
				SAY_MY_NAME_WITH_TOOLS_SYSTEM_PROMPT
		);
		Speaker speaker = new Speaker(gemini, USE_REAL_TTS);
		SpeakTool speakTool = new SpeakTool(speaker);
		
		// register tools so the model knows what it can call
		ToolRegistry tools = new ToolRegistry();
		tools.register(speakTool);
		
		// attach tool declarations to the request
		geminiRequest.setTools(tools);
		
		GeminiClient.Reply reply = gemini.chatWithTools(geminiRequest);
		if (reply.toolCall != null) {
			// model decided to call a tool — execute it
			System.out.println("🤖 llm_call: " + reply.toolCall.name + "(" + reply.toolCall.args + ")");
			String result = tools.execute(reply.toolCall.name, reply.toolCall.args);
			System.out.println("📦 tool result: " + result);
		} else if (!reply.text.isBlank()) {
			history.add(new Content(Role.MODEL, reply.text));
			System.out.println("bot> " + reply.text);
		}
	}
	
	
	private static void step3(String userInput)
			throws Exception {
		GeminiClient gemini = getGeminiClient();
		history.add(new Content(Role.USER, userInput));
		GeminiRequest geminiRequest = new GeminiRequest(history);
		// add a system prompt to shape the model's behavior
		geminiRequest.systemInstruction = new SystemInstruction(
				SAY_MY_NAME_SYSTEM_PROMPT
		);
		// ask for structured (JSON) output so we can parse it
		String response = gemini.complete(geminiRequest);
		
		// parse the JSON reply into name + message fields
		NameReply nameReply = tryParse(response);
		if (!nameReply.message.isBlank()) {
			history.add(new Content(Role.MODEL, nameReply.message));
			System.out.println("bot> " + nameReply.message);
		}
		
		if (!nameReply.name.isBlank()) {
			// human in the loop: we decide whether to invoke the speaker
			System.out.print("👨‍💻 java_code: name=\"" + nameReply.name + "\" detected. " +
					"Invoke speaker? (y/n) ");
			
			Scanner in = new Scanner(System.in);
			String confirm = in.hasNextLine() ? in.nextLine().trim().toLowerCase() : "n";
			if (confirm.equals("y") || confirm.equals("yes")) {
				Speaker speaker = new Speaker(gemini, USE_REAL_TTS);
				speaker.speak(nameReply.name, Speaker.Source.CODE);
			} else {
				System.out.println("   skipped.");
			}
		} else {
			System.out.println("👨‍💻 java_code: name is empty → speaker NOT called");
		}
	}
	
	
	private static void step2(String userInput) throws Exception {
		GeminiClient gemini = getGeminiClient();
		// add user message to history (this is the "memory")
		history.add(new Content(Role.USER, userInput));
		// send the full history so the model has context
		String reply = gemini.complete(history);
		// record the model's reply so future turns include it
		history.add(new Content(Role.MODEL, reply));
		System.out.println("bot> " + reply);
	}
	
	
	private static void step1(String userInput) throws Exception {
		GeminiClient gemini = getGeminiClient();
		// single stateless call
		String reply = gemini.complete(userInput);
		System.out.println("bot> " + reply);
	}
	
	
	private static GeminiClient getGeminiClient() {
		// Get the API key
		String apiKey = System.getenv("GEMINI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.err.println("Please set GEMINI_API_KEY environment variable.");
			System.exit(1);
		}
		
		// Setup Gemini Client
		return new GeminiClient(apiKey);
	}
	
	
	private static final ObjectMapper M = new ObjectMapper();
	
	
	private static NameReply tryParse(String raw) {
		if (raw == null || raw.isBlank()) {
			return new NameReply();
		}
		String trimmed = raw.trim();
		// Tolerate ```json fences if the model adds them.
		if (trimmed.startsWith("```")) {
			int firstNl = trimmed.indexOf('\n');
			if (firstNl > 0) {
				trimmed = trimmed.substring(firstNl + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
			}
		}
		try {
			return M.readValue(trimmed, NameReply.class);
		} catch (Exception e) {
			return new NameReply();
		}
	}
}

