package com.ftejero21.traza.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.J;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Port de isFlowRelevant (host-change.ts): heuristica local, sin IA, de si el flujo abierto esta relacionado con la peticion. */
public final class ChangeRelevance {
	private static final Set<String> STOPWORDS = Set.of("necesito", "quiero", "gustaria", "puedes", "podrias", "cambiar", "cambia", "cambie", "cambiemos",
		"cambio", "modificar", "modifica", "poner", "ponemos", "pongamos", "quitar", "anadir", "agregar", "cuando", "donde", "para", "como", "este", "esta",
		"esto", "estos", "esas", "nuestro", "sale", "salga", "tambien", "todo", "with", "when", "change", "need", "want", "please", "make", "that", "this",
		"from", "into");

	private ChangeRelevance() {
	}

	private static String str(JsonElement v) {
		return J.isStr(v) ? v.getAsString() : "";
	}

	private static String flowHaystack(JsonObject flow) {
		StringBuilder parts = new StringBuilder();
		parts.append(str(flow.get("title"))).append(' ').append(str(flow.get("question"))).append(' ').append(str(flow.get("summary")));
		for (JsonElement lane : J.arr(flow.get("lanes"))) {
			parts.append(' ').append(J.obj(lane) != null ? str(lane.getAsJsonObject().get("name")) : "");
		}
		for (JsonElement step : J.arr(flow.get("steps"))) {
			JsonObject s = J.obj(step);
			if (s == null) {
				continue;
			}
			JsonObject ref = J.obj(s.get("ref"));
			parts.append(' ').append(str(s.get("label"))).append(' ').append(str(s.get("title"))).append(' ').append(ref != null ? str(ref.get("file")) : "");
		}
		for (JsonElement file : J.arr(flow.get("files"))) {
			parts.append(' ').append(J.isStr(file) ? file.getAsString() : J.obj(file) != null ? str(file.getAsJsonObject().get("file")) : "");
		}
		return FlowContextCollector.foldAccents(parts.toString()).toLowerCase(Locale.ROOT);
	}

	/** El flujo abierto esta relacionado con la peticion? */
	public static boolean isFlowRelevant(JsonElement flow, String text) {
		JsonObject obj = J.obj(flow);
		if (obj == null) {
			return false;
		}
		Set<String> unique = new LinkedHashSet<>();
		for (String w : FlowContextCollector.foldAccents(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (w.length() >= 4) {
				unique.add(w);
			}
		}
		List<String> words = unique.stream().filter(w -> !STOPWORDS.contains(w)).collect(Collectors.toList());
		if (words.isEmpty()) {
			TrazaPlugin.info("Cambio: flujo abierto relevante=false hits=0/0");
			return false;
		}
		String haystack = flowHaystack(obj);
		long hits = words.stream().filter(w -> haystack.contains(w) || (w.length() >= 6 && haystack.contains(w.substring(0, 5)))).count();
		boolean relevant = hits >= 2 || (words.size() <= 2 && hits >= 1);
		TrazaPlugin.info("Cambio: flujo abierto relevante=" + relevant + " hits=" + hits + "/" + words.size());
		return relevant;
	}
}
