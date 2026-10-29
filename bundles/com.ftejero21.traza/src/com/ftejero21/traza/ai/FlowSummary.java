package com.ftejero21.traza.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;

import com.ftejero21.traza.core.FlowStore.GeneratedFlow;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.prefs.TrazaPreferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Port de host-flow-summary.ts: deteccion de escenario, eleccion de carpeta del workspace y formateo del resumen de un
 * flujo o un cambio. En lugar de vscode.Location produce texto carpeta/ruta:linea.
 */
public final class FlowSummary {
	private static final int MAX_SUMMARY = 600;
	private static final int MAX_ERRORS = 400;
	private static final int MAX_LABEL = 120;
	private static final int MAX_FILE = 260;
	private static final int MAX_CHAT_STEPS = 8;
	private static final int MAX_CHANGE_FILES = 12;
	private static final int MAX_CHANGE_RISKS = 5;
	public static final Function<String, String> PLAIN = s -> s;

	private FlowSummary() {
	}

	public static boolean isSpanish() {
		return TrazaPreferences.isSpanish();
	}

	/** Texto en una linea (saltos colapsados) y recortado a max. */
	public static String oneLine(JsonElement value, int max) {
		if (!J.isStr(value)) {
			return "";
		}
		return oneLine(value.getAsString(), max);
	}

	public static String oneLine(String value, int max) {
		String text = value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
		return text.length() > max ? text.substring(0, Math.max(0, max - 1)) + "…" : text;
	}

	/** Escapa el texto para markdown (sin HTML, enlaces ni comandos). */
	public static String escapeMarkdown(String text) {
		return text.replaceAll("([\\\\`*_{}\\[\\]()#+\\-.!|<>~])", "\\\\$1").replaceAll("\\r?\\n", "  \n");
	}

	private static final Pattern[] SCENARIO_PATTERNS = {
		Pattern.compile("\\bque\\s+(pasa|ocurre|sucede|pasaria|ocurriria|sucederia)\\s+(si|cuando)\\b"),
		Pattern.compile("\\bsi\\s+(pongo|meto|introduzco|escribo|envio|mando|falla|no\\s+(hay|tengo|existe|esta))\\b"),
		Pattern.compile("\\bwhat\\s+(happens|would\\s+happen|will\\s+happen)\\s+(if|when)\\b"),
		Pattern.compile("\\bwhat\\s+if\\b") };

	/** La pregunta plantea un escenario concreto («que pasa si...», «what happens if...»)? */
	public static boolean isScenarioQuestion(String question) {
		String folded = FlowContextCollector.foldAccents(question).toLowerCase(Locale.ROOT);
		for (Pattern p : SCENARIO_PATTERNS) {
			if (p.matcher(folded).find()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Carpeta del workspace a la que se limita el contexto: hint exacto o nombre de carpeta citado en la pregunta (sin
	 * tildes ni mayusculas, palabra completa; gana el nombre mas largo). null con menos de 2 carpetas o sin coincidencia.
	 */
	public static String resolveFlowFolder(String question, String hint) {
		List<IProject> folders = WorkspaceFiles.openProjects();
		if (folders.size() < 2) {
			return null;
		}
		String wanted = hint == null ? "" : hint.trim().toLowerCase(Locale.ROOT);
		if (!wanted.isEmpty()) {
			for (IProject f : folders) {
				if (f.getName().toLowerCase(Locale.ROOT).equals(wanted)) {
					return f.getName();
				}
			}
		}
		String text = FlowContextCollector.foldAccents(question).toLowerCase(Locale.ROOT);
		String best = null;
		for (IProject folder : folders) {
			String name = FlowContextCollector.foldAccents(folder.getName()).toLowerCase(Locale.ROOT).trim();
			if (name.isEmpty()) {
				continue;
			}
			if (!Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(name) + "([^a-z0-9]|$)").matcher(text).find()) {
				continue;
			}
			if (best == null || name.length() > FlowContextCollector.foldAccents(best).trim().length()) {
				best = folder.getName();
			}
		}
		return best;
	}

	/* ------------------------------ escenario ------------------------------ */

	private static final class Where {
		String mountId;
		String file;
		Integer line;
	}

	private static final class ScenarioInfo {
		String label;
		String kind;
		String badge;
		String title;
		String userImpact;
		String conclusion;
		int stepIndex;
		String stepLabel;
		Where where;
	}

	private static Where refOf(JsonElement value) {
		JsonObject ref = J.obj(value);
		if (ref == null || !J.isStr(ref.get("file")) || ref.get("file").getAsString().trim().isEmpty()) {
			return null;
		}
		Where w = new Where();
		Double line = J.num(ref.get("line"));
		w.line = line != null && line > 0 ? (int) Math.floor(line) : null;
		w.mountId = J.isStr(ref.get("mountId")) ? ref.get("mountId").getAsString().trim() : "";
		w.file = ref.get("file").getAsString().trim();
		return w;
	}

	private static ScenarioInfo scenarioInfo(GeneratedFlow generated) {
		JsonObject scenario = J.obj(generated.flow.get("scenario"));
		JsonObject result = scenario == null ? null : J.obj(scenario.get("result"));
		if (scenario == null || result == null) {
			return null;
		}
		String kind = J.isStr(result.get("kind")) ? result.get("kind").getAsString() : "none";
		JsonArray steps = J.arr(generated.flow.get("steps"));
		String failId = J.isStr(result.get("failStepId")) ? result.get("failStepId").getAsString() : "";
		int index = -1;
		if (!failId.isEmpty()) {
			for (int i = 0; i < steps.size(); i++) {
				JsonObject s = J.obj(steps.get(i));
				if (s != null && failId.equals(J.str(s, "id"))) {
					index = i;
					break;
				}
			}
		}
		JsonObject failStep = index >= 0 ? J.obj(steps.get(index)) : null;
		Where where = null;
		if (kind.equals("error") || kind.equals("stop")) {
			where = refOf(result.get("where"));
			if (where == null && failStep != null) {
				where = refOf(failStep.get("ref"));
			}
		}
		ScenarioInfo info = new ScenarioInfo();
		info.label = oneLine(scenario.get("label"), 60);
		info.kind = kind;
		info.badge = oneLine(result.get("badge"), 60);
		info.title = oneLine(result.get("title"), 90);
		info.userImpact = oneLine(result.get("userImpact"), 400);
		info.conclusion = oneLine(result.get("conclusion"), 900);
		info.stepIndex = index >= 0 ? index + 1 : 0;
		String stepLabel = "";
		if (failStep != null) {
			stepLabel = oneLine(failStep.get("label"), MAX_LABEL);
			if (stepLabel.isEmpty()) {
				stepLabel = oneLine(failStep.get("title"), MAX_LABEL);
			}
		}
		info.stepLabel = stepLabel;
		info.where = where;
		return info;
	}

	private static String whereText(Where where, Function<String, String> esc) {
		if (where == null) {
			return "";
		}
		boolean multi = WorkspaceFiles.openProjects().size() > 1;
		String file = oneLine(multi && !where.mountId.isEmpty() ? where.mountId + "/" + where.file : where.file, MAX_FILE);
		return esc.apply(file) + (where.line != null ? ":" + where.line : "");
	}

	/** Ubicacion del fallo del escenario como texto carpeta/ruta:linea; null si no hay. */
	public static String failLocation(GeneratedFlow generated) {
		ScenarioInfo info = scenarioInfo(generated);
		if (info == null || info.where == null) {
			return null;
		}
		return whereText(info.where, PLAIN);
	}

	/* ------------------------------ pasos ------------------------------ */

	private static String[] stepLines(JsonObject flow, int max, Function<String, String> esc, int[] more) {
		Map<String, String> lanes = new LinkedHashMap<>();
		for (JsonElement lane : J.arr(flow.get("lanes"))) {
			JsonObject o = J.obj(lane);
			if (o == null) {
				continue;
			}
			String id = J.str(o, "id");
			if (!id.isEmpty()) {
				String name = oneLine(o.get("name"), 80);
				lanes.put(id, name.isEmpty() ? oneLine(id, 80) : name);
			}
		}
		Function<JsonElement, String> nameOf = id -> {
			String key = J.isStr(id) ? id.getAsString() : "";
			String v = lanes.get(key);
			if (v == null) {
				v = oneLine(key, 80);
				if (v.isEmpty()) {
					v = "?";
				}
			}
			return esc.apply(v);
		};
		List<JsonObject> steps = new ArrayList<>();
		for (JsonElement s : J.arr(flow.get("steps"))) {
			JsonObject o = J.obj(s);
			if (o != null) {
				steps.add(o);
			}
		}
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < Math.min(max, steps.size()); i++) {
			JsonObject step = steps.get(i);
			String label = oneLine(step.get("label"), MAX_LABEL);
			if (label.isEmpty()) {
				label = oneLine(step.get("title"), MAX_LABEL);
			}
			JsonObject ref = J.obj(step.get("ref"));
			String file = ref != null ? oneLine(ref.get("file"), MAX_FILE) : "";
			String line = ref != null && J.num(ref.get("line")) != null ? ":" + Semantics.txt(ref.get("line")) : "";
			String where = !file.isEmpty() ? " (" + esc.apply(file) + line + ")" : "";
			lines.add((i + 1) + ". " + nameOf.apply(step.get("from")) + " → " + nameOf.apply(step.get("to")) + ": " + esc.apply(label) + where);
		}
		more[0] = Math.max(0, steps.size() - max);
		return lines.toArray(new String[0]);
	}

	/* ------------------------------ textos ------------------------------ */

	/** Resumen del flujo: titulo, resumen, escenario y pasos con carpeta/ruta:linea (texto plano si esc = PLAIN). */
	public static String formatFlowChatMarkdown(GeneratedFlow generated, boolean compact, Function<String, String> esc) {
		boolean es = isSpanish();
		JsonObject flow = generated.flow;
		String title = oneLine(generated.title, 120);
		List<String> parts = new ArrayList<>();
		parts.add(compact ? "**" + esc.apply((es ? "Recorrido" : "Walkthrough") + ": " + title) + "**" : "**" + esc.apply(title) + "**");
		String summary = oneLine(flow.get("summary"), MAX_SUMMARY);
		if (!summary.isEmpty() && !compact) {
			parts.add(esc.apply(summary));
		}
		ScenarioInfo info = scenarioInfo(generated);
		if (info != null) {
			String badge = !info.badge.isEmpty() ? " · " + info.badge : "";
			List<String> block = new ArrayList<>();
			block.add("**" + esc.apply((es ? "Escenario" : "Scenario") + ": " + info.label + badge) + "**");
			if (!info.userImpact.isEmpty() && !compact) {
				block.add("**" + (es ? "Qué ve el usuario" : "What the user sees") + ":** " + esc.apply(info.userImpact));
			}
			if (info.kind.equals("error") || info.kind.equals("stop")) {
				String head = info.kind.equals("error") ? (es ? "Dónde falla" : "Where it fails") : (es ? "Dónde se detiene" : "Where it stops");
				String step = info.stepIndex > 0 ? (es ? "paso " : "step ") + info.stepIndex : "";
				String label = !info.stepLabel.isEmpty() ? " — " + esc.apply(info.stepLabel) : "";
				String place = whereText(info.where, esc);
				String text = (step + label + (!place.isEmpty() ? " (" + place + ")" : "")).trim();
				if (!text.isEmpty()) {
					block.add("**" + head + ":** " + text);
				}
			} else if (info.kind.equals("ok") && !compact) {
				block.add(es ? "El flujo termina correctamente." : "The flow ends successfully.");
			}
			if (!info.conclusion.isEmpty() && !compact) {
				block.add("**" + (es ? "Conclusión" : "Conclusion") + ":** " + esc.apply(info.conclusion));
			}
			parts.add(String.join("\n\n", block));
		} else if (generated.scenarioAsked) {
			parts.add(es
				? "No se pudo generar un escenario simulable para esta pregunta; puedes probarlo en «Simular datos» del panel."
				: "A simulable scenario could not be generated for this question; you can try it in \"Simular datos\" in the panel.");
			if (J.isStr(flow.get("errorSummary"))) {
				String errors = oneLine(flow.get("errorSummary"), MAX_ERRORS);
				if (!errors.isEmpty()) {
					parts.add("**" + (es ? "Errores" : "Errors") + ":** " + esc.apply(errors));
				}
			}
		}
		int[] more = new int[1];
		String[] lines = stepLines(flow, MAX_CHAT_STEPS, esc, more);
		if (lines.length > 0) {
			String list = String.join("\n", lines);
			String moreText = more[0] > 0 ? "\n\n" + (es ? "… y " + more[0] + " pasos más" : "… and " + more[0] + " more steps") : "";
			parts.add("**" + (es ? "Pasos" : "Steps") + "**\n\n" + list + moreText);
		}
		return String.join("\n\n", parts);
	}

	private static final Pattern CHANGE_QUESTION_START = Pattern.compile("^(como|que|donde|por que|cual|cuales|cuando|how|what|where|why|which|when)\\b");
	private static final Pattern[] CHANGE_PATTERNS = {
		Pattern.compile("^(por favor\\s+)?(cambia|cambie|cambiemos|modifica|modifique|anade|agrega|pon|pongamos|quita|elimina|renombra|refactoriza|implementa|arregla|corrige|sustituye|reemplaza|haz que)\\b"),
		Pattern.compile("\\b(necesito|quiero|me gustaria|puedes|podrias|hay que)\\s+(que\\s+)?(\\w+\\s+){0,12}?(cambi|modific|anad|agreg|pong|poner|quit|elimin|renombr|refactoriz|implement|arregl|corrig|sustitu|reemplaz)\\w*"),
		Pattern.compile("^(please\\s+)?(change|modify|add|remove|rename|refactor|implement|fix|replace|make)\\b"),
		Pattern.compile("\\b(i need|i want|can you|could you|please)\\s+(to\\s+)?(\\w+\\s+){0,6}?(change|modify|add|remove|rename|refactor|implement|fix|replace|make)\\b") };

	/** El texto es una peticion de cambio de codigo/UI (y no una pregunta ni un escenario)? */
	public static boolean isChangeRequest(String text) {
		if (isScenarioQuestion(text)) {
			return false;
		}
		String folded = FlowContextCollector.foldAccents(text).toLowerCase(Locale.ROOT).trim().replaceFirst("^[¿¡\\s]+", "");
		if (folded.isEmpty() || CHANGE_QUESTION_START.matcher(folded).find()) {
			return false;
		}
		for (Pattern p : CHANGE_PATTERNS) {
			if (p.matcher(folded).find()) {
				return true;
			}
		}
		return false;
	}

	/** Resumen de una propuesta de cambio resuelta (texto plano si esc = PLAIN). */
	public static String formatChangeChatMarkdown(JsonObject proposal, String flowTitleRaw, boolean generated, Function<String, String> esc) {
		boolean es = isSpanish();
		String flowTitle = esc.apply(oneLine(flowTitleRaw, 120));
		List<String> parts = new ArrayList<>();
		parts.add(generated ? (es ? "Flujo generado" : "Generated flow") + ": **" + flowTitle + "**"
			: (es ? "Sobre el flujo abierto" : "On the open flow") + ": **" + flowTitle + "**");
		parts.add("**" + esc.apply(oneLine(proposal.get("title"), 120)) + "**");
		String summary = oneLine(proposal.get("summary"), MAX_SUMMARY);
		if (!summary.isEmpty()) {
			parts.add(esc.apply(summary));
		}
		boolean multi = WorkspaceFiles.openProjects().size() > 1;
		List<JsonObject> all = new ArrayList<>();
		for (JsonElement f : J.arr(proposal.get("files"))) {
			JsonObject o = J.obj(f);
			if (o != null) {
				all.add(o);
			}
		}
		if (!all.isEmpty()) {
			List<String> list = new ArrayList<>();
			for (JsonObject f : all.subList(0, Math.min(MAX_CHANGE_FILES, all.size()))) {
				String mount = J.isStr(f.get("mountId")) ? f.get("mountId").getAsString().trim() : "";
				String rel = oneLine(f.get("path"), MAX_FILE);
				String path = multi && !mount.isEmpty() ? mount + "/" + rel : rel;
				String status = oneLine(f.get("status"), 20);
				list.add("- " + esc.apply(path) + (!status.isEmpty() ? " (" + esc.apply(status) + ")" : ""));
			}
			int more = Math.max(0, all.size() - MAX_CHANGE_FILES);
			String moreText = more > 0 ? "\n\n" + (es ? "… y " + more + " archivos más" : "… and " + more + " more files") : "";
			parts.add("**" + (es ? "Archivos" : "Files") + "**\n\n" + String.join("\n", list) + moreText);
		}
		List<String> risks = new ArrayList<>();
		for (JsonElement r : J.arr(proposal.get("risks"))) {
			String line = oneLine(r, 200);
			if (!line.isEmpty() && risks.size() < MAX_CHANGE_RISKS) {
				risks.add(line);
			}
		}
		if (!risks.isEmpty()) {
			List<String> lines = new ArrayList<>();
			for (String x : risks) {
				lines.add("- " + esc.apply(x));
			}
			parts.add("**" + (es ? "Riesgos" : "Risks") + "**\n\n" + String.join("\n", lines));
		}
		parts.add(es ? "Abierto en «Simular cambios» (antes/después). No se ha modificado ningún archivo."
			: "Opened in \"Simular cambios\" (before/after). No file has been modified.");
		return String.join("\n\n", parts);
	}
}
