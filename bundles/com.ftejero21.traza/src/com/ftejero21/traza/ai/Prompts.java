package com.ftejero21.traza.ai;

import java.io.IOException;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.BundleFiles;
import com.ftejero21.traza.core.J;
import com.google.gson.JsonObject;

/** Prompts de sistema exportados de nexo-vscode (resources/ai/prompts.json). */
public final class Prompts {
	private static JsonObject data;

	private Prompts() {
	}

	private static synchronized JsonObject data() {
		if (data == null) {
			try {
				data = J.parse(BundleFiles.readText("resources/ai/prompts.json")).getAsJsonObject();
			} catch (IOException | RuntimeException e) {
				TrazaPlugin.error("No se pudieron cargar los prompts de la IA.", e);
				data = new JsonObject();
			}
		}
		return data;
	}

	private static String get(String section, String key) {
		JsonObject s = J.obj(data().get(section));
		return s == null ? "" : J.str(s, key);
	}

	/** Prompt de sistema de la operacion (FLOW, SIMULATE, ...). El modo agente solo existe para FLOW, PROPOSE, UI_MOCK y DB_MAP_SQL. */
	public static String systemFor(String op, String lang, boolean agent) {
		boolean agentic = agent && (op.equals("FLOW") || op.equals("PROPOSE") || op.equals("UI_MOCK") || op.equals("DB_MAP_SQL"));
		String key = op.equals("DB_EXPLAIN") ? "DB_EXPLAIN|" + ("en".equals(lang) ? "en" : "es") : op + (agentic ? "|agent" : "");
		String value = get("system", key);
		if (value.isEmpty()) {
			throw new AiException("Operación de IA desconocida.");
		}
		return value;
	}

	/** Nota de respuesta completa (la pregunta ya enmascarada se sustituye en {{QUESTION}}). */
	public static String answerNote(String kind, String question, String lang, boolean tools) {
		String key = "answerNote|" + kind + "|" + ("en".equals(lang) ? "en" : "es") + (tools ? "|tools" : "");
		return get("answer", key).replace("{{QUESTION}}", question);
	}

	public static String flowAnswerNote() {
		return get("answer", "FLOW_ANSWER_NOTE");
	}

	public static String changeAnswerNote() {
		return get("answer", "CHANGE_ANSWER_NOTE");
	}

	public static String compactSchema(String definition) {
		JsonObject s = J.obj(data().get("compactSchema"));
		String v = s == null ? "" : J.str(s, definition);
		return v.isEmpty() ? "{}" : v;
	}
}
