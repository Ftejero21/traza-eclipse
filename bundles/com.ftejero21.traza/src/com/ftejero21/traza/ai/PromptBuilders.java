package com.ftejero21.traza.ai;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import com.ftejero21.traza.ai.ChangePatcher.ChangeFile;
import com.ftejero21.traza.ai.ChangePatcher.ChangeHunk;
import com.ftejero21.traza.ai.ChangePatcher.ChangeLine;
import com.ftejero21.traza.ai.ChangePatcher.CodeWindow;
import com.ftejero21.traza.ai.FlowContextCollector.ContextFragment;
import com.ftejero21.traza.ai.FlowContextCollector.FlowContext;
import com.ftejero21.traza.ai.FlowContextCollector.FolderRole;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.SecretMasker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Decodificadores y constructores de texto de host-ai.ts (sin llamadas al modelo). */
public final class PromptBuilders {
	public static final int MAX_STEPS = 20;
	public static final int MAX_WINDOWS = 24;
	public static final int MAX_WINDOW_CHARS = 8000;
	public static final int MAX_INPUT_CHARS = 20_000;
	public static final int MAX_LOG_CHARS = 60_000;
	public static final int MAX_PRE_CHARS = 120_000;
	public static final int MAX_DB_SCHEMA_CHARS = 40_000;
	public static final int MAX_FLOW_JSON = 120_000;
	public static final int MAX_FLOW_ID = 200;
	public static final int MAX_PATH = 400;
	public static final int MAX_UI_INPUT_CHARS = 300_000;
	public static final int MAX_UI_PROMPT_CHARS = 240_000;
	public static final int MAX_UI_FILE = 60_000;
	public static final int MAX_UI_SHEETS = 4;
	public static final int MAX_UI_CHILDREN = 8;
	public static final int MAX_UI_CHILD_FILE = 30_000;
	public static final int MAX_UI_CHILD_SHEETS = 2;
	public static final int MAX_UI_GLOBAL_CSS = 60_000;
	public static final int MAX_UI_SIMULATION_JSON = 20_000;
	public static final int MAX_UI_CHANGE_JSON = 40_000;
	public static final int MAX_UI_NAME = 200;
	public static final int UI_PLAN_GLOBAL_CSS_AI_CHARS = 12_000;
	public static final int MAX_UI_MOCK_INPUT_CHARS = 80_000;
	public static final int MAX_UI_MOCK_SEEDS = 4;
	public static final int MAX_UI_MOCK_STEPS = 30;
	public static final int UI_MOCK_SEED_FILE_CHARS = 20_000;
	public static final int UI_MOCK_SEED_LINES = 400;
	public static final int MAX_DB_SQL_SEEDS = 8;
	public static final int DB_SQL_SEED_LINES = 300;
	public static final int DB_SQL_SEED_FILE_CHARS = 8_000;
	public static final int DB_SQL_SCHEMA_CHARS = 12_000;
	public static final int MAX_DB_SQL_INPUT_CHARS = 60_000;
	public static final int MAX_DB_SQL_FLOW = 8;
	public static final int DB_SQL_FLOW_CODE_CHARS = 2_000;
	public static final int DB_SQL_FLOW_CHARS = 8_000;
	public static final int FLOW_MAX_CONTEXT_CHARS = 110_000;
	public static final int MAX_ACTIVE_FLOW_CHARS = 8000;
	public static final int MAX_MOUNTS = 16;
	public static final int MAX_COMPARE_CASES = 7;
	public static final int MAX_CHANGE_FILES = 12;
	public static final int MAX_CHANGE_HUNKS = 40;
	public static final int MAX_CHANGE_LINES = 400;
	public static final int MAX_CHANGE_TEXT = 2000;
	public static final int MAX_CHANGE_TITLE = 70;
	public static final int MAX_CHANGE_SUMMARY = 500;
	public static final int MAX_DB_EXPLAIN_CONTEXT = 60_000;
	public static final int MAX_DB_EXPLAIN_STEPS = 8;
	public static final int AGENT_MAX_HINT_FILES = 12;
	public static final String TRUNC = "\n…[truncado]";
	public static final String UNAPPLIED_NOTE = "## NOTA\nEl CODIGO de esta petición es el ORIGINAL (el cambio no se pudo aplicar a las ventanas): razona el después aplicando tú el CAMBIO PROPUESTO.\n\n";
	public static final String JSON_TAIL = "\n\nResponde con un único objeto json.";
	private static final Set<String> CHANGE_STATUSES = Set.of("modified", "added", "deleted");
	private static final Set<String> CHANGE_KINDS = Set.of("context", "add", "del");
	private static final Set<String> DB_EXPLAIN_KINDS = Set.of("actor", "request", "flow", "insert", "fill", "note");
	private static final Pattern ENDPOINT_PATTERN = Pattern.compile("^(GET|POST|PUT|PATCH|DELETE) /[\\w\\-/{}.:]{0,120}$");

	/** Definicion del esquema que se ensena al modelo cuando su respuesta no se puede dibujar. */
	public static final Map<String, String> FORMAT_DEFINITION = Map.of(
		"FLOW", "FlowDiagram", "SIMULATE", "SimulationResult", "SIMULATE_BEFORE", "SimulationResult", "SIMULATE_AFTER", "SimulationResult",
		"LOGS", "LogAnalysisResult", "PROPOSE", "ChangeProposal", "UI_PLAN", "UiPreviewPlan", "UI_MOCK", "UiMock", "DB_MAP", "FlowDbMap",
		"DB_MAP_SQL", "FlowDbMap");

	private PromptBuilders() {
	}

	public static String json(JsonElement v) {
		try {
			return J.GSON.toJson(v == null ? com.google.gson.JsonNull.INSTANCE : v);
		} catch (RuntimeException e) {
			return "null";
		}
	}

	private static boolean str(JsonElement v, int max) {
		return J.isStr(v) && v.getAsString().length() <= max;
	}

	private static boolean validPath(JsonElement p) {
		return J.isStr(p) && !p.getAsString().trim().isEmpty() && p.getAsString().length() <= MAX_PATH;
	}

	private static Integer intOrNull(JsonElement v) {
		Double d = J.num(v);
		return d == null ? null : (int) d.doubleValue();
	}

	private static String cut(String s, int max) {
		return Semantics.cut(s, max);
	}

	/** Mensaje de reintento por FORMATO. */
	public static String formatFeedback(String definition, List<String> errors, int attempt, int max) {
		StringBuilder sb = new StringBuilder("## FORMATO NO LEGIBLE (intento " + attempt + " de " + max
			+ ")\nTraza no ha podido dibujar tu respuesta anterior. Partes que no se pudieron leer (ruta JSON: problema):\n");
		List<String> shown = errors.size() > 20 ? errors.subList(0, 20) : errors;
		for (int i = 0; i < shown.size(); i++) {
			if (i > 0) {
				sb.append('\n');
			}
			sb.append("- ").append(shown.get(i));
		}
		if (definition != null) {
			sb.append("\nFormato esperado (definicion ").append(definition).append("):\n").append(Prompts.compactSchema(definition));
		}
		return sb + "\nDevuelve de nuevo el JSON completo con el mismo contenido, corrigiendo solo estas partes. Solo JSON.";
	}

	/* ------------------------------ decodificacion ------------------------------ */

	public static JsonObject decodeContext(String contextBase64, String lang) {
		JsonElement raw;
		try {
			String text = new String(Base64.getDecoder().decode(contextBase64.trim()), StandardCharsets.UTF_8);
			raw = Semantics.parseStrict(text);
		} catch (RuntimeException e) {
			throw new AiException(Msg.t("invalid", lang));
		}
		if (!Semantics.isObj(raw)) {
			throw new AiException(Msg.t("invalid", lang));
		}
		return raw.getAsJsonObject();
	}

	public static String validFlowId(JsonElement raw, String lang) {
		String flowId = J.isStr(raw) ? raw.getAsString().trim() : "";
		if (flowId.length() > MAX_FLOW_ID) {
			throw new AiException(Msg.t("invalid", lang));
		}
		return flowId;
	}

	public static JsonObject validFlow(JsonElement flow, String lang) {
		if (!Semantics.isObj(flow) || !J.isArr(flow.getAsJsonObject().get("steps")) || flow.getAsJsonObject().getAsJsonArray("steps").size() == 0
			|| flow.getAsJsonObject().getAsJsonArray("steps").size() > MAX_STEPS || json(flow).length() > MAX_FLOW_JSON) {
			throw new AiException(Msg.t("invalid", lang));
		}
		return flow.getAsJsonObject();
	}

	/** Ventanas de codigo (<= 24, contenido <= 8000) enmascaradas de nuevo. */
	public static List<CodeWindow> validCode(JsonElement raw, String lang) {
		List<CodeWindow> out = new ArrayList<>();
		if (J.isNull(raw)) {
			return out;
		}
		if (!J.isArr(raw) || raw.getAsJsonArray().size() > MAX_WINDOWS) {
			throw new AiException(Msg.t("invalid", lang));
		}
		for (JsonElement item : raw.getAsJsonArray()) {
			if (J.isNull(item)) {
				continue;
			}
			if (!Semantics.isObj(item) || !validPath(item.getAsJsonObject().get("path")) || !str(item.getAsJsonObject().get("content"), MAX_WINDOW_CHARS)) {
				throw new AiException(Msg.t("invalid", lang));
			}
			JsonObject o = item.getAsJsonObject();
			String path = o.get("path").getAsString();
			out.add(new CodeWindow(path, intOrNull(o.get("startLine")), intOrNull(o.get("endLine")),
				SecretMasker.maskConfigSecrets(path, o.get("content").getAsString())));
		}
		return out;
	}

	public static final class DiagnosisCtx {
		public String flowId;
		public JsonObject flow;
		public List<CodeWindow> code;
	}

	public static DiagnosisCtx decodeDiagnosis(JsonObject ctx, String lang) {
		DiagnosisCtx d = new DiagnosisCtx();
		d.flowId = validFlowId(ctx.get("flowId"), lang);
		d.flow = validFlow(ctx.get("flow"), lang);
		d.code = validCode(ctx.get("code"), lang);
		return d;
	}

	public static List<ChangeFile> validChangeFiles(JsonElement raw, String lang) {
		if (!J.isArr(raw) || raw.getAsJsonArray().size() == 0 || raw.getAsJsonArray().size() > MAX_CHANGE_FILES) {
			throw new AiException(Msg.t("invalid", lang));
		}
		List<ChangeFile> out = new ArrayList<>();
		for (JsonElement fileEl : raw.getAsJsonArray()) {
			JsonObject file = J.obj(fileEl);
			if (file == null || !validPath(file.get("path")) || !J.isStr(file.get("status")) || !CHANGE_STATUSES.contains(file.get("status").getAsString())) {
				throw new AiException(Msg.t("invalid", lang));
			}
			String status = file.get("status").getAsString();
			JsonElement hunks = file.get("hunks");
			if (!J.isArr(hunks) || hunks.getAsJsonArray().size() == 0 || hunks.getAsJsonArray().size() > MAX_CHANGE_HUNKS) {
				throw new AiException(Msg.t("invalid", lang));
			}
			ChangeFile cf = new ChangeFile();
			cf.path = file.get("path").getAsString();
			cf.status = status;
			for (JsonElement hunkEl : hunks.getAsJsonArray()) {
				JsonObject hunk = J.obj(hunkEl);
				Long oldStart = hunk == null ? null : J.integer(hunk.get("oldStart"));
				Long newStart = hunk == null ? null : J.integer(hunk.get("newStart"));
				JsonElement lines = hunk == null ? null : hunk.get("lines");
				if (oldStart == null || oldStart < 0 || newStart == null || newStart < 0 || !J.isArr(lines) || lines.getAsJsonArray().size() == 0
					|| lines.getAsJsonArray().size() > MAX_CHANGE_LINES) {
					throw new AiException(Msg.t("invalid", lang));
				}
				ChangeHunk ch = new ChangeHunk();
				ch.oldStart = oldStart.intValue();
				ch.newStart = newStart.intValue();
				for (JsonElement lineEl : lines.getAsJsonArray()) {
					JsonObject line = J.obj(lineEl);
					JsonElement kind = line == null ? null : line.get("kind");
					JsonElement text = line == null ? null : line.get("text");
					if (!J.isStr(kind) || !CHANGE_KINDS.contains(kind.getAsString()) || !J.isStr(text) || text.getAsString().length() > MAX_CHANGE_TEXT
						|| text.getAsString().contains("\n")) {
						throw new AiException(Msg.t("invalid", lang));
					}
					if (status.equals("added") && !kind.getAsString().equals("add")) {
						throw new AiException(Msg.t("invalid", lang));
					}
					if (status.equals("deleted") && !kind.getAsString().equals("del")) {
						throw new AiException(Msg.t("invalid", lang));
					}
					ch.lines.add(new ChangeLine(kind.getAsString(), text.getAsString()));
				}
				cf.hunks.add(ch);
			}
			out.add(cf);
		}
		return out;
	}

	/** JSON del cambio propuesto (para changeDiff) a partir de los archivos validados. */
	public static JsonObject changeJson(String title, String summary, List<ChangeFile> files) {
		JsonObject o = new JsonObject();
		o.addProperty("title", title);
		o.addProperty("summary", summary);
		JsonArray fa = new JsonArray();
		for (ChangeFile f : files) {
			JsonObject fo = new JsonObject();
			fo.addProperty("path", f.path);
			fo.addProperty("status", f.status);
			JsonArray ha = new JsonArray();
			for (ChangeHunk h : f.hunks) {
				JsonObject ho = new JsonObject();
				ho.addProperty("oldStart", h.oldStart);
				ho.addProperty("newStart", h.newStart);
				JsonArray la = new JsonArray();
				for (ChangeLine l : h.lines) {
					JsonObject lo = new JsonObject();
					lo.addProperty("kind", l.kind);
					lo.addProperty("text", l.text);
					la.add(lo);
				}
				ho.add("lines", la);
				ha.add(ho);
			}
			fo.add("hunks", ha);
			fa.add(fo);
		}
		o.add("files", fa);
		return o;
	}

	/* ------------------------------ textos de usuario ------------------------------ */

	public static String idiomaTail(String lang) {
		return "## IDIOMA DE LA RESPUESTA\n" + ("en".equals(lang) ? "en" : "es") + JSON_TAIL;
	}

	public static String composeDiagnosis(JsonElement flow, List<CodeWindow> code, String changeText, String tail, boolean applied) {
		StringBuilder sb = new StringBuilder("## FLUJO\n" + json(flow) + "\n\n## CODIGO\n");
		if (code.isEmpty()) {
			sb.append("(sin codigo)\n");
		}
		for (CodeWindow f : code) {
			sb.append("### ").append(cut(f.path.trim(), 400)).append(":L").append(f.startLine == null ? "?" : f.startLine.toString())
				.append("-L").append(f.endLine == null ? "?" : f.endLine.toString()).append('\n')
				.append(Semantics.numbered(f.content, f.startLine)).append("\n\n");
		}
		if (changeText != null && !changeText.isEmpty()) {
			sb.append("## CAMBIO PROPUESTO (").append(applied ? "YA INCLUIDO EN EL CODIGO" : "AUN NO APLICADO: el CODIGO es el original").append(")\n")
				.append(changeText).append("\n\n");
		}
		return sb + "\n" + tail;
	}

	/** FLUJO + CODIGO + tail, quitando ventanas del final hasta caber en el presupuesto. */
	public static IntFunction<String> fitCode(JsonElement flow, List<CodeWindow> rawCode, String changeText, String tail, boolean applied) {
		List<CodeWindow> base = new ArrayList<>();
		for (CodeWindow f : rawCode) {
			if (!f.path.trim().isEmpty() && f.content != null && !f.content.isEmpty() && f.content.length() <= MAX_WINDOW_CHARS && base.size() < MAX_WINDOWS) {
				base.add(f);
			}
		}
		return maxChars -> {
			int budget = Math.min(Math.max(8000, FLOW_MAX_CONTEXT_CHARS) + tail.length(), maxChars);
			List<CodeWindow> code = new ArrayList<>(base);
			String text = composeDiagnosis(flow, code, changeText, tail, applied);
			while (text.length() > budget && !code.isEmpty()) {
				code.remove(code.size() - 1);
				text = composeDiagnosis(flow, code, changeText, tail, applied);
			}
			return text;
		};
	}

	public static IntFunction<String> fitCode(JsonElement flow, List<CodeWindow> rawCode, String changeText, String tail) {
		return fitCode(flow, rawCode, changeText, tail, true);
	}

	public static String simulateTail(JsonElement input, String lang) {
		return "## DATOS DE ENTRADA\n" + json(input) + "\n\n" + idiomaTail(lang);
	}

	public static String logsTail(String[] lines, JsonElement pre, String lang) {
		StringBuilder sb = new StringBuilder("## LOGS\n");
		for (int i = 0; i < lines.length; i++) {
			sb.append('[').append(i).append("] ").append(lines[i]).append('\n');
		}
		sb.append("\n## PRE-ANALISIS\n").append(cut(json(pre), MAX_PRE_CHARS)).append("\n\n");
		return sb + idiomaTail(lang);
	}

	public static final class FlowTextInput {
		public String mode;
		public String question;
		public FlowContext context;
		public String activeFlow;
		public String lang;
		public boolean scenarioHint;
		public String overview;
		public String focusFolder;
		public List<String> hintFiles;
	}

	private static final Set<String> FRONT_TECH = Set.of("Angular", "React", "Vue", "Svelte");

	private static String roleLine(FolderRole role) {
		List<String> parts = new ArrayList<>();
		for (String layer : role.roles) {
			if (layer.equals("datos")) {
				parts.add("datos");
				continue;
			}
			List<String> tech = new ArrayList<>();
			for (String t : role.tech) {
				if (FRONT_TECH.contains(t) == layer.equals("front")) {
					tech.add(t);
				}
			}
			parts.add(tech.isEmpty() ? layer : layer + " (" + String.join(", ", tech) + ")");
		}
		return "- " + cut(role.mount, 200) + ": " + String.join(", ", parts);
	}

	public static String composeFlowText(FlowTextInput input, String map, List<ContextFragment> fragments, String activeContent) {
		StringBuilder sb = new StringBuilder(input.overview != null && !input.overview.isEmpty() ? "## CARPETAS DEL WORKSPACE\n" + input.overview + "\n\n" : "");
		sb.append("## MAPA DEL PROYECTO\n").append(map == null || map.isEmpty() ? "(vacio)" : map).append("\n\n## MONTAJES\n");
		List<FlowContextCollector.ContextMount> mounts = input.context.mounts.size() > MAX_MOUNTS ? input.context.mounts.subList(0, MAX_MOUNTS) : input.context.mounts;
		for (FlowContextCollector.ContextMount m : mounts) {
			sb.append("- id=").append(cut(m.id.trim(), 200)).append(" prefijo=").append(cut(m.prefix, MAX_PATH)).append(" nombre=").append(cut(m.name.trim(), 200)).append('\n');
		}
		if (mounts.isEmpty()) {
			sb.append("(ninguno)\n");
		}
		sb.append('\n');
		List<FolderRole> roles = input.context.roles;
		if (roles != null && !roles.isEmpty()) {
			List<String> lines = new ArrayList<>();
			for (FolderRole r : roles.subList(0, Math.min(MAX_MOUNTS, roles.size()))) {
				lines.add(roleLine(r));
			}
			sb.append("## ROLES DE LAS CARPETAS\n").append(String.join("\n", lines))
				.append("\nEl flujo debe recorrer todas estas capas cuando la petición trate un comportamiento de la aplicación.\n\n");
		}
		List<FlowContextCollector.BridgeHint> bridge = input.context.bridge;
		if (bridge != null && !bridge.isEmpty()) {
			sb.append("## PUENTE HTTP DETECTADO\n");
			for (FlowContextCollector.BridgeHint b : bridge.subList(0, Math.min(8, bridge.size()))) {
				sb.append("- ").append(cut(b.front, MAX_PATH)).append(":L").append(b.line).append(' ').append(b.method).append(" → ").append(cut(b.back, MAX_PATH))
					.append(b.handler != null ? " (" + cut(b.handler, 120) + ")" : "").append('\n');
			}
			sb.append('\n');
		}
		if (input.focusFolder != null && !input.focusFolder.trim().isEmpty()) {
			sb.append("## CARPETA MENCIONADA\n").append(cut(input.focusFolder.trim(), 200))
				.append(" (empieza aquí, pero investiga también las demás carpetas relacionadas)\n\n");
		}
		if (input.hintFiles != null && !input.hintFiles.isEmpty()) {
			sb.append("## ARCHIVOS INDICADOS\n");
			List<String> hf = input.hintFiles.subList(0, Math.min(AGENT_MAX_HINT_FILES, input.hintFiles.size()));
			for (int i = 0; i < hf.size(); i++) {
				sb.append(i > 0 ? "\n" : "").append("- ").append(cut(hf.get(i), MAX_PATH));
			}
			sb.append("\n\n");
		}
		if (input.mode.equals("PROPOSE") && input.activeFlow != null && !input.activeFlow.isEmpty()) {
			sb.append("## FLUJO ACTIVO\n").append(cut(input.activeFlow, MAX_ACTIVE_FLOW_CHARS)).append("\n\n");
		}
		if (!fragments.isEmpty()) {
			sb.append("## FRAGMENTOS RELEVANTES\n");
			for (ContextFragment f : fragments) {
				sb.append("### ").append(f.path.trim()).append(":L").append(f.startLine).append("-L").append(f.endLine).append('\n')
					.append(Semantics.numbered(f.content, f.startLine)).append("\n\n");
			}
		}
		FlowContextCollector.ContextActive active = input.context.active;
		if (active != null && activeContent != null) {
			sb.append("## ARCHIVO ACTIVO ").append(active.path.trim()).append(" (lineas ").append(active.startLine).append('-').append(active.endLine)
				.append(" de ").append(active.totalLines).append(")\n").append(Semantics.numbered(activeContent, active.startLine)).append("\n\n");
			FlowContextCollector.ContextSelection sel = input.context.selection;
			if (sel != null && !sel.text.trim().isEmpty()) {
				sb.append("## SELECCION (lineas ").append(sel.startLine).append('-').append(sel.endLine).append(")\n").append(sel.text).append("\n\n");
			}
		}
		sb.append("## PETICION (modo ").append(input.mode).append(")\n").append(input.question);
		if (input.mode.equals("FLOW") && input.scenarioHint) {
			sb.append("\n\n(La pregunta plantea un escenario concreto: incluye \"scenario\" según la regla 9.)");
		}
		return sb + "\n\n" + idiomaTail(input.lang);
	}

	/** Recorte de tejechat: primero fragmentos de menor prioridad (el ultimo entre iguales), luego el mapa y luego el archivo activo. */
	public static IntFunction<String> fitFlowText(FlowTextInput input) {
		return maxChars -> {
			int max = Math.min(Math.max(8000, FLOW_MAX_CONTEXT_CHARS), maxChars);
			String map = input.context.mapText.trim();
			List<ContextFragment> fragments = new ArrayList<>(input.context.fragments);
			String activeContent = input.context.active != null ? input.context.active.content : null;
			String text = composeFlowText(input, map, fragments, activeContent);
			boolean multi = input.context.mounts.size() > 1;
			java.util.function.Function<String, String> mountOf = path -> multi ? path.split("/")[0] : "";
			while (text.length() > max && !fragments.isEmpty()) {
				int drop = fragments.size() - 1;
				int lowest = fragments.get(drop).priority;
				for (int i = drop - 1; i >= 0; i--) {
					int p = fragments.get(i).priority;
					if (p < lowest) {
						lowest = p;
						drop = i;
					}
				}
				if (multi) {
					Map<String, Integer> counts = new java.util.HashMap<>();
					for (ContextFragment f : fragments) {
						counts.merge(mountOf.apply(f.path), 1, Integer::sum);
					}
					int most = counts.getOrDefault(mountOf.apply(fragments.get(drop).path), 0);
					for (int i = fragments.size() - 1; i >= 0; i--) {
						if (fragments.get(i).priority != lowest) {
							continue;
						}
						int count = counts.getOrDefault(mountOf.apply(fragments.get(i).path), 0);
						if (count > most) {
							most = count;
							drop = i;
						}
					}
				}
				fragments.remove(drop);
				text = composeFlowText(input, map, fragments, activeContent);
			}
			if (text.length() > max && !map.isEmpty()) {
				int keep = Math.max(0, map.length() - (text.length() - max) - TRUNC.length());
				map = map.substring(0, Math.min(keep, map.length())) + TRUNC;
				text = composeFlowText(input, map, fragments, activeContent);
			}
			if (text.length() > max && activeContent != null && !activeContent.isEmpty()) {
				int keep = Math.max(0, activeContent.length() - (text.length() - max) - TRUNC.length());
				activeContent = activeContent.substring(0, Math.min(keep, activeContent.length())) + TRUNC;
				text = composeFlowText(input, map, fragments, activeContent);
			}
			return text;
		};
	}

	/* ------------------------------ vista de pantalla ------------------------------ */

	private static String validUiName(JsonElement raw, String lang) {
		String value = J.isStr(raw) ? raw.getAsString().trim() : "";
		if (value.length() > MAX_UI_NAME) {
			throw new AiException(Msg.t("invalid", lang));
		}
		return value;
	}

	private static JsonObject validUiFile(JsonElement file, int maxChars, String lang) {
		JsonObject f = J.obj(file);
		if (f == null || !validPath(f.get("path")) || !str(f.get("content"), maxChars)) {
			throw new AiException(Msg.t("invalid", lang));
		}
		String path = f.get("path").getAsString().trim();
		JsonObject out = new JsonObject();
		out.addProperty("path", path);
		out.addProperty("content", SecretMasker.maskConfigSecrets(path, f.get("content").getAsString()));
		return out;
	}

	private static JsonArray validUiSheets(JsonElement raw, int maxSheets, int maxChars, String lang) {
		JsonArray out = new JsonArray();
		if (J.isNull(raw)) {
			return out;
		}
		if (!J.isArr(raw) || raw.getAsJsonArray().size() > maxSheets) {
			throw new AiException(Msg.t("invalid", lang));
		}
		for (JsonElement s : raw.getAsJsonArray()) {
			if (!J.isNull(s)) {
				out.add(validUiFile(s, maxChars, lang));
			}
		}
		return out;
	}

	public static final class UiPlanInput {
		public JsonObject input;
		public JsonObject change;
	}

	public static UiPlanInput decodeUiPlan(JsonObject ctx, String lang) {
		if (json(ctx).length() > MAX_UI_INPUT_CHARS) {
			throw new AiException(Msg.t("invalid", lang));
		}
		validFlowId(ctx.get("flowId"), lang);
		JsonObject rc = J.obj(ctx.get("component"));
		if (rc == null) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonObject html = validUiFile(rc.get("html"), MAX_UI_FILE, lang);
		if (html.get("content").getAsString().trim().isEmpty()) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonObject component = new JsonObject();
		component.addProperty("name", validUiName(rc.get("name"), lang));
		component.addProperty("selector", validUiName(rc.get("selector"), lang));
		component.add("ts", validUiFile(rc.get("ts"), MAX_UI_FILE, lang));
		component.add("html", html);
		component.add("css", validUiSheets(rc.get("css"), MAX_UI_SHEETS, MAX_UI_FILE, lang));
		JsonArray children = new JsonArray();
		JsonElement rawChildren = ctx.get("children");
		if (!J.isNull(rawChildren)) {
			if (!J.isArr(rawChildren) || rawChildren.getAsJsonArray().size() > MAX_UI_CHILDREN) {
				throw new AiException(Msg.t("invalid", lang));
			}
			for (JsonElement childEl : rawChildren.getAsJsonArray()) {
				if (J.isNull(childEl)) {
					continue;
				}
				JsonObject child = J.obj(childEl);
				if (child == null) {
					throw new AiException(Msg.t("invalid", lang));
				}
				String selector = validUiName(child.get("selector"), lang);
				if (selector.isEmpty()) {
					throw new AiException(Msg.t("invalid", lang));
				}
				JsonObject c = new JsonObject();
				c.addProperty("selector", selector);
				c.addProperty("name", validUiName(child.get("name"), lang));
				c.add("html", validUiFile(child.get("html"), MAX_UI_CHILD_FILE, lang));
				c.add("css", validUiSheets(child.get("css"), MAX_UI_CHILD_SHEETS, MAX_UI_CHILD_FILE, lang));
				children.add(c);
			}
		}
		String globalCss = J.isStr(ctx.get("globalCss")) ? ctx.get("globalCss").getAsString() : "";
		if (globalCss.length() > MAX_UI_GLOBAL_CSS) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonElement simulation = ctx.get("simulation");
		if (!Semantics.isObj(simulation) || json(simulation).length() > MAX_UI_SIMULATION_JSON) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonElement change = ctx.get("change");
		if (J.isNull(change)) {
			change = null;
		}
		if (change != null && (!Semantics.isObj(change) || json(change).length() > MAX_UI_CHANGE_JSON)) {
			throw new AiException(Msg.t("invalid", lang));
		}
		UiPlanInput out = new UiPlanInput();
		out.input = new JsonObject();
		out.input.add("component", component);
		out.input.add("children", children);
		out.input.addProperty("globalCss", SecretMasker.maskSensitiveText(globalCss));
		out.input.add("simulation", SecretMasker.maskSensitiveJson(simulation));
		out.change = change == null ? null : SecretMasker.maskSensitiveJson(change).getAsJsonObject();
		return out;
	}

	public static String buildUiPlanText(JsonObject input, JsonObject change, String lang) {
		JsonObject component = input.getAsJsonObject("component");
		JsonObject ts = component.getAsJsonObject("ts");
		JsonObject html = component.getAsJsonObject("html");
		StringBuilder sb = new StringBuilder("## COMPONENTE\nname: " + J.str(component, "name") + "\nselector: " + J.str(component, "selector") + "\n\n");
		sb.append("## TS ").append(J.str(ts, "path")).append('\n').append(J.str(ts, "content")).append("\n\n");
		sb.append("## PLANTILLA ").append(J.str(html, "path")).append('\n').append(J.str(html, "content")).append("\n\n");
		for (JsonElement sheet : component.getAsJsonArray("css")) {
			sb.append("## ESTILOS DEL COMPONENTE ").append(J.str(sheet.getAsJsonObject(), "path")).append('\n').append(J.str(sheet.getAsJsonObject(), "content")).append("\n\n");
		}
		sb.append("## HIJOS\n");
		JsonArray children = input.getAsJsonArray("children");
		if (children.size() == 0) {
			sb.append("(sin hijos)\n");
		}
		for (JsonElement childEl : children) {
			JsonObject child = childEl.getAsJsonObject();
			JsonObject ch = child.getAsJsonObject("html");
			sb.append("### ").append(J.str(child, "selector")).append(" (").append(J.str(child, "name")).append(")\n#### PLANTILLA ")
				.append(J.str(ch, "path")).append('\n').append(J.str(ch, "content")).append('\n');
		}
		sb.append("\n## ESTILOS GLOBALES (recortados)\n").append(Semantics.cutCss(J.str(input, "globalCss"), UI_PLAN_GLOBAL_CSS_AI_CHARS)).append("\n\n");
		sb.append("## RESULTADO DE LA SIMULACION\n").append(json(input.get("simulation"))).append("\n\n");
		String changeText = Semantics.changeDiff(change);
		if (changeText != null) {
			sb.append("## CAMBIO PROPUESTO\n").append(changeText).append("\n\n");
		}
		return sb + idiomaTail(lang);
	}

	/* ------------------------------ maqueta de pantalla (UI_MOCK) ------------------------------ */

	public static final class Seed {
		public String mountId;
		public String file;
		public int line;
	}

	public static final class UiMockInput {
		public String flowId;
		public String question;
		public JsonObject screen;
		public JsonArray steps;
		public List<Seed> seeds;
		public JsonElement simulation;
		public JsonObject change;
		public boolean applied;
	}

	private static String uiMockText(JsonElement raw, int max, String lang) {
		String value;
		if (J.isNum(raw) && J.num(raw) != null) {
			value = Long.toString((long) J.num(raw).doubleValue());
		} else if (J.isStr(raw)) {
			value = raw.getAsString();
		} else {
			value = "";
		}
		value = value.trim();
		if (value.length() > max) {
			throw new AiException(Msg.t("invalid", lang));
		}
		return value;
	}

	public static List<Seed> decodeSeeds(JsonElement raw, int max, String lang) {
		if (!J.isArr(raw) || raw.getAsJsonArray().size() > max) {
			throw new AiException(Msg.t("invalid", lang));
		}
		List<Seed> out = new ArrayList<>();
		for (JsonElement el : raw.getAsJsonArray()) {
			JsonObject s = J.obj(el);
			if (s == null || !str(s.get("mountId"), 200) || !validPath(s.get("file"))) {
				throw new AiException(Msg.t("invalid", lang));
			}
			Integer line = intOrNull(s.get("line"));
			Seed seed = new Seed();
			seed.mountId = s.get("mountId").getAsString().trim();
			seed.file = s.get("file").getAsString().trim();
			seed.line = line != null && line >= 1 ? line : 1;
			out.add(seed);
		}
		return out;
	}

	public static final class FlowSql {
		public String stepId;
		public String code;
	}

	public static List<FlowSql> decodeFlowSql(JsonElement raw, JsonObject flow, String lang) {
		List<FlowSql> out = new ArrayList<>();
		if (J.isNull(raw)) {
			return out;
		}
		if (!J.isArr(raw) || raw.getAsJsonArray().size() > MAX_DB_SQL_FLOW) {
			throw new AiException(Msg.t("invalid", lang));
		}
		Set<String> ids = new HashSet<>();
		for (JsonElement s : J.arr(flow.get("steps"))) {
			JsonObject so = J.obj(s);
			if (so != null && J.isStr(so.get("id"))) {
				ids.add(so.get("id").getAsString());
			}
		}
		for (JsonElement item : raw.getAsJsonArray()) {
			JsonObject o = J.obj(item);
			if (o == null) {
				throw new AiException(Msg.t("invalid", lang));
			}
			JsonElement stepId = o.get("stepId");
			JsonElement code = o.get("code");
			if (!J.isStr(stepId) || stepId.getAsString().trim().isEmpty() || stepId.getAsString().length() > 80 || !J.isStr(code)
				|| code.getAsString().length() > DB_SQL_FLOW_CODE_CHARS) {
				throw new AiException(Msg.t("invalid", lang));
			}
			if (code.getAsString().trim().isEmpty() || !ids.contains(stepId.getAsString().trim())) {
				continue;
			}
			FlowSql fs = new FlowSql();
			fs.stepId = stepId.getAsString().trim();
			fs.code = SecretMasker.maskSensitiveText(code.getAsString().trim());
			out.add(fs);
		}
		return out;
	}

	public static UiMockInput decodeUiMock(JsonObject ctx, String lang) {
		if (json(ctx).length() > MAX_UI_MOCK_INPUT_CHARS) {
			throw new AiException(Msg.t("invalid", lang));
		}
		UiMockInput in = new UiMockInput();
		in.flowId = validFlowId(ctx.get("flowId"), lang);
		JsonElement question = ctx.get("question");
		if (!str(question, 500)) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonObject rawScreen = J.obj(ctx.get("screen"));
		if (rawScreen == null) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonObject screen = new JsonObject();
		screen.addProperty("index", uiMockText(rawScreen.get("index"), 20, lang));
		screen.addProperty("id", uiMockText(rawScreen.get("id"), 200, lang));
		screen.addProperty("title", uiMockText(rawScreen.get("title"), 400, lang));
		screen.addProperty("label", uiMockText(rawScreen.get("label"), 400, lang));
		screen.addProperty("what", uiMockText(rawScreen.get("what"), 600, lang));
		screen.addProperty("kind", uiMockText(rawScreen.get("kind"), 80, lang));
		JsonElement rawSteps = ctx.get("steps");
		if (!J.isArr(rawSteps) || rawSteps.getAsJsonArray().size() > MAX_UI_MOCK_STEPS) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonArray steps = new JsonArray();
		for (JsonElement el : rawSteps.getAsJsonArray()) {
			JsonObject s = J.obj(el);
			if (s == null) {
				throw new AiException(Msg.t("invalid", lang));
			}
			JsonObject o = new JsonObject();
			o.addProperty("index", uiMockText(s.get("index"), 20, lang));
			o.addProperty("label", uiMockText(s.get("label"), 400, lang));
			o.addProperty("title", uiMockText(s.get("title"), 400, lang));
			String ref = uiMockText(s.get("ref"), MAX_PATH + 220, lang);
			String ui = uiMockText(s.get("ui"), 400, lang);
			if (ref.isEmpty()) {
				o.add("ref", com.google.gson.JsonNull.INSTANCE);
			} else {
				o.addProperty("ref", ref);
			}
			if (ui.isEmpty()) {
				o.add("ui", com.google.gson.JsonNull.INSTANCE);
			} else {
				o.addProperty("ui", ui);
			}
			steps.add(o);
		}
		in.seeds = decodeSeeds(ctx.get("seeds"), MAX_UI_MOCK_SEEDS, lang);
		JsonElement simulation = ctx.get("simulation");
		if (!Semantics.isObj(simulation) || json(simulation).length() > MAX_UI_SIMULATION_JSON) {
			throw new AiException(Msg.t("invalid", lang));
		}
		JsonElement change = ctx.get("change");
		if (J.isNull(change)) {
			change = null;
		}
		if (change != null && (!Semantics.isObj(change) || json(change).length() > MAX_UI_CHANGE_JSON)) {
			throw new AiException(Msg.t("invalid", lang));
		}
		in.applied = change != null && J.isBool(change.getAsJsonObject().get("applied")) && change.getAsJsonObject().get("applied").getAsBoolean();
		in.question = SecretMasker.maskSensitiveText(question.getAsString());
		in.screen = SecretMasker.maskSensitiveJson(screen).getAsJsonObject();
		in.steps = SecretMasker.maskSensitiveJson(steps).getAsJsonArray();
		in.simulation = SecretMasker.maskSensitiveJson(simulation);
		in.change = change == null ? null : SecretMasker.maskSensitiveJson(change).getAsJsonObject();
		return in;
	}

	/** Textos de la pantalla y del recorrido para UI_MOCK, con la semilla recortada para que el total quepa en maxChars. */
	public static IntFunction<String> buildUiMockText(UiMockInput input, String seedText, String overview, String lang) {
		JsonObject screen = input.screen;
		return maxChars -> {
			StringBuilder sb = new StringBuilder("## PANTALLA A VISUALIZAR\npaso: P" + J.str(screen, "index") + "\ntitulo: " + J.str(screen, "title") + "\netiqueta: "
				+ J.str(screen, "label") + "\n");
			String what = !J.str(screen, "what").isEmpty() ? J.str(screen, "what") : !J.str(screen, "kind").isEmpty() ? J.str(screen, "kind") : "(sin descripcion)";
			sb.append("lo que ve el usuario: ").append(what).append("\npregunta: ").append(input.question).append("\n\n");
			sb.append("## RECORRIDO HASTA LA PANTALLA\n");
			if (input.steps.size() == 0) {
				sb.append("(sin pasos)\n");
			}
			for (JsonElement el : input.steps) {
				JsonObject step = el.getAsJsonObject();
				String title = !J.str(step, "title").isEmpty() ? " · " + J.str(step, "title") : "";
				String ref = !J.str(step, "ref").isEmpty() ? " · " + J.str(step, "ref") : "";
				String ui = !J.str(step, "ui").isEmpty() ? " · ui: " + J.str(step, "ui") : "";
				sb.append("- P").append(J.str(step, "index")).append(' ').append(J.str(step, "label")).append(title).append(ref).append(ui).append('\n');
			}
			sb.append("\n## RESULTADO DE LA SIMULACION\n").append(json(input.simulation)).append("\n\n");
			String changeText = Semantics.changeDiff(input.change);
			if (changeText != null) {
				sb.append("## CAMBIO PROPUESTO (aplicado: ").append(input.applied ? "sí" : "no").append(")\n").append(changeText).append("\n\n");
			}
			sb.append("## CARPETAS DEL WORKSPACE\n").append(overview == null || overview.isEmpty() ? "(sin datos)" : overview).append("\n\n");
			String tail = idiomaTail(lang);
			String head = "## ARCHIVOS DE PARTIDA\n";
			int room = maxChars - sb.length() - head.length() - tail.length() - 2;
			String seed = seedText.trim().isEmpty() ? "(ninguno)" : seedText.trim();
			if (seed.length() > room) {
				seed = room > TRUNC.length() ? seed.substring(0, room - TRUNC.length()) + TRUNC : "";
			}
			return sb + head + seed + "\n\n" + tail;
		};
	}

	/* ------------------------------ mapa de datos ------------------------------ */

	public static final class DbSchema {
		public JsonElement schema;
		public boolean empty;
	}

	public static DbSchema decodeDbSchema(JsonObject ctx, String lang) {
		JsonElement schema = ctx.get("schema");
		if (J.isNull(schema)) {
			schema = new JsonObject();
		} else if (!Semantics.isObj(schema) || json(schema).length() > MAX_DB_SCHEMA_CHARS) {
			throw new AiException(Msg.t("invalid", lang));
		} else {
			schema = SecretMasker.maskSensitiveJson(schema);
		}
		DbSchema out = new DbSchema();
		out.schema = schema;
		out.empty = !Semantics.isObj(schema) || schema.getAsJsonObject().size() == 0;
		return out;
	}

	public static IntFunction<String> buildDbSqlText(JsonObject flow, String reason, List<FlowSql> flowSql, String schemaText, String seedText, String overview, String lang) {
		return maxChars -> {
			StringBuilder sb = new StringBuilder("## FLUJO\n" + json(flow) + "\n\n");
			sb.append("## MOTIVO DEL REINTENTO\n").append(reason.equals("empty") ? "El mapa anterior salió vacío o sin tablas pintables." : "El mapa anterior no se pudo generar.").append("\n\n");
			sb.append("## SQL DEL FLUJO\n");
			if (!flowSql.isEmpty()) {
				StringBuilder sql = new StringBuilder("(punto de partida: contrástala con el código; si difiere, manda el código real y el ESQUEMA REAL; resuelve constantes y placeholders)\n");
				for (FlowSql item : flowSql) {
					sql.append("### paso ").append(item.stepId).append('\n').append(item.code).append('\n');
				}
				String sqlText = sql.toString();
				sb.append(sqlText.length() > DB_SQL_FLOW_CHARS ? sqlText.substring(0, DB_SQL_FLOW_CHARS - TRUNC.length()) + TRUNC : sqlText);
			} else {
				sb.append("(ninguna: localiza la SQL en el código leyendo la clase/función de cada paso de datos y la del paso anterior)");
			}
			sb.append("\n\n");
			sb.append("## ESQUEMA REAL\n").append(schemaText).append("\n\n");
			sb.append("## CARPETAS DEL WORKSPACE\n").append(overview == null || overview.isEmpty() ? "(sin datos)" : overview).append("\n\n");
			String tail = idiomaTail(lang);
			String head = "## ARCHIVOS DE ACCESO A DATOS\n";
			int room = maxChars - sb.length() - head.length() - tail.length() - 2;
			String seed = seedText.trim().isEmpty() ? "(ninguno)" : seedText.trim();
			if (seed.length() > room) {
				seed = room > TRUNC.length() ? seed.substring(0, room - TRUNC.length()) + TRUNC : "";
			}
			return sb + head + seed + "\n\n" + tail;
		};
	}

	/* ------------------------------ explicacion de tabla ------------------------------ */

	public static void addText(Set<String> set, JsonElement value) {
		if (J.isStr(value) && !value.getAsString().trim().isEmpty()) {
			set.add(value.getAsString().trim());
		}
	}

	private static final Pattern LINE_BREAKS = Pattern.compile("\\s*(?:\\r\\n|[\\n\\r\\u2028\\u2029\\u0085\\u000b\\u000c])\\s*");
	private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00a0]{2,}");

	private static String clean(JsonElement value, int max) {
		if (!J.isStr(value)) {
			return null;
		}
		String normalized = MULTI_SPACE.matcher(LINE_BREAKS.matcher(value.getAsString()).replaceAll(" ")).replaceAll(" ").trim();
		return normalized.length() <= max ? normalized : normalized.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	public static JsonObject parseDbExplain(JsonObject root, Set<String> allowedPlaces, Set<String> targetColumns) {
		JsonArray steps = new JsonArray();
		for (JsonElement el : J.arr(root.get("steps"))) {
			if (steps.size() >= MAX_DB_EXPLAIN_STEPS) {
				break;
			}
			JsonObject node = J.obj(el);
			if (node == null) {
				continue;
			}
			String title = clean(node.get("title"), 90);
			String text = clean(node.get("text"), 360);
			if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) {
				continue;
			}
			String kind = Semantics.txt(node.get("kind")).trim().toLowerCase(java.util.Locale.ROOT);
			JsonObject step = new JsonObject();
			step.addProperty("kind", DB_EXPLAIN_KINDS.contains(kind) ? kind : "note");
			for (String key : new String[] { "from", "to" }) {
				JsonElement v = node.get(key);
				if (J.isStr(v) && allowedPlaces.contains(v.getAsString().trim())) {
					step.addProperty(key, v.getAsString().trim());
				} else {
					step.add(key, com.google.gson.JsonNull.INSTANCE);
				}
			}
			step.addProperty("title", title != null && !title.isEmpty() ? title : text);
			step.addProperty("text", text != null && !text.isEmpty() ? text : title);
			JsonElement ep = node.get("endpoint");
			if (J.isStr(ep) && ENDPOINT_PATTERN.matcher(ep.getAsString().trim()).matches()) {
				step.addProperty("endpoint", ep.getAsString().trim());
			} else {
				step.add("endpoint", com.google.gson.JsonNull.INSTANCE);
			}
			Set<String> columns = new LinkedHashSet<>();
			for (JsonElement item : J.arr(node.get("columns"))) {
				if (columns.size() >= 12) {
					break;
				}
				if (J.isStr(item) && targetColumns.contains(item.getAsString().trim())) {
					columns.add(item.getAsString().trim());
				}
			}
			step.add("columns", J.strArray(columns));
			steps.add(step);
		}
		JsonObject out = new JsonObject();
		String summary = clean(root.get("summary"), 300);
		if (summary == null || summary.isEmpty()) {
			out.add("summary", com.google.gson.JsonNull.INSTANCE);
		} else {
			out.addProperty("summary", summary);
		}
		out.add("steps", steps);
		return out;
	}

	/** Casos de la propuesta: preset conocido del flujo o caso nuevo con label e input validos; lo demas se descarta. */
	public static JsonArray sanitizeProposalCases(JsonElement raw, JsonObject flow) {
		JsonArray out = new JsonArray();
		if (!J.isArr(raw)) {
			return out;
		}
		Map<String, String> presets = new java.util.LinkedHashMap<>();
		for (JsonElement p : J.arr(flow.get("simulationPresets"))) {
			JsonObject po = J.obj(p);
			if (po == null) {
				continue;
			}
			String id = Semantics.txt(po.get("id")).trim();
			if (!id.isEmpty()) {
				presets.put(id, Semantics.txt(po.get("label")).trim());
			}
		}
		Set<String> seen = new HashSet<>();
		for (JsonElement el : raw.getAsJsonArray()) {
			if (out.size() >= 3) {
				break;
			}
			JsonObject item = J.obj(el);
			if (item == null) {
				continue;
			}
			String presetId = J.isStr(item.get("presetId")) ? item.get("presetId").getAsString().trim() : null;
			if (presetId != null && !presetId.isEmpty() && presetId.length() <= 100 && presets.containsKey(presetId)) {
				if (!seen.add(presetId)) {
					continue;
				}
				JsonObject c = new JsonObject();
				c.addProperty("presetId", presetId);
				String label = presets.get(presetId);
				c.addProperty("label", cut(label.isEmpty() ? presetId : label, 28));
				c.add("input", com.google.gson.JsonNull.INSTANCE);
				out.add(c);
				continue;
			}
			String label = J.isStr(item.get("label")) ? cut(item.get("label").getAsString().trim(), 28) : "";
			JsonElement input = item.get("input");
			if (label.isEmpty() || !Semantics.isObj(input) || json(input).length() > MAX_INPUT_CHARS) {
				continue;
			}
			if (!seen.add("new:" + label.toLowerCase(java.util.Locale.ROOT))) {
				continue;
			}
			JsonObject c = new JsonObject();
			c.add("presetId", com.google.gson.JsonNull.INSTANCE);
			c.addProperty("label", label);
			c.add("input", SecretMasker.maskSensitiveJson(input));
			out.add(c);
		}
		return out;
	}

	/** El flujo activo sin datos solo de frontend ni resultados (codeSnapshot, dbMap, scenario, verification). */
	public static JsonElement stripFlow(JsonObject flow, boolean keepScenario) {
		return strip(flow, 0, keepScenario);
	}

	private static JsonElement strip(JsonElement value, int depth, boolean keepScenario) {
		if (depth > 32) {
			return com.google.gson.JsonNull.INSTANCE;
		}
		if (value != null && value.isJsonArray()) {
			JsonArray out = new JsonArray();
			for (JsonElement v : value.getAsJsonArray()) {
				out.add(strip(v, depth + 1, keepScenario));
			}
			return out;
		}
		if (!Semantics.isObj(value)) {
			return value;
		}
		JsonObject out = new JsonObject();
		for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
			String k = e.getKey();
			if (k.equals("codeSnapshot") || k.equals("verification")) {
				continue;
			}
			if (depth == 0 && (k.equals("dbMap") || (k.equals("scenario") && !keepScenario))) {
				continue;
			}
			out.add(k, strip(e.getValue(), depth + 1, keepScenario));
		}
		return out;
	}
}
