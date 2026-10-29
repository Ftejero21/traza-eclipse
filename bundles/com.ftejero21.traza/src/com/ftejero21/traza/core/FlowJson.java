package com.ftejero21.traza.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.Semantics;
import com.ftejero21.traza.ui.Ui;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Port de parseFlowJson (extension.ts): un FlowDiagram o un objeto con `flow` y propuestas (maximo 10). */
public final class FlowJson {
	public static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
	private static final int MAX_PROPOSALS = 10;

	public static final class Parsed {
		public final JsonElement flow;
		public final List<JsonElement> proposals;

		Parsed(JsonElement flow, List<JsonElement> proposals) {
			this.flow = flow;
			this.proposals = proposals;
		}
	}

	private FlowJson() {
	}

	/** Titulo del flujo (60 caracteres como maximo). */
	public static String titleOf(JsonElement flow) {
		JsonObject o = J.obj(flow);
		String raw = o != null && J.isStr(o.get("title")) ? o.get("title").getAsString().trim() : "";
		return raw.isEmpty() ? "Sin título" : J.truncate(raw, 60);
	}

	/** JSON del flujo; null (con aviso al usuario) si no se puede usar. */
	public static Parsed parse(String text) {
		if (text.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
			Ui.error("Traza", "El JSON supera el límite de 2 MB.");
			return null;
		}
		JsonElement raw;
		try {
			raw = Semantics.parseStrict(text);
		} catch (RuntimeException e) {
			TrazaPlugin.warn("JSON no valido (" + e.getClass().getSimpleName() + ").");
			String m = e.getMessage() == null ? "" : J.truncate(e.getMessage(), 200);
			Ui.error("Traza", "El JSON no es válido: " + m);
			return null;
		}
		JsonObject o = J.obj(raw);
		if (o == null) {
			Ui.error("Traza", "El JSON no es un flujo válido");
			return null;
		}
		JsonObject nested = J.obj(o.get("flow"));
		JsonElement flow = nested != null ? nested : raw;
		List<JsonElement> proposals = new ArrayList<>();
		if (nested != null) {
			if (J.isArr(o.get("proposals"))) {
				for (JsonElement p : o.getAsJsonArray("proposals")) {
					if (J.obj(p) != null && proposals.size() < MAX_PROPOSALS) {
						proposals.add(p);
					}
				}
			} else if (J.obj(o.get("proposal")) != null) {
				proposals.add(o.get("proposal"));
			} else if (J.obj(o.get("change")) != null) {
				proposals.add(o.get("change"));
			}
		}
		return new Parsed(flow, proposals);
	}
}
