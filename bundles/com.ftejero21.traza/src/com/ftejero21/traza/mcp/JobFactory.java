package com.ftejero21.traza.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.AiException;
import com.ftejero21.traza.ai.ChangePatcher;
import com.ftejero21.traza.ai.ChangePatcher.ChangeFile;
import com.ftejero21.traza.ai.ChangePatcher.CodeWindow;
import com.ftejero21.traza.ai.ChangePatcher.PatchError;
import com.ftejero21.traza.ai.Checks;
import com.ftejero21.traza.ai.Checks.Validation;
import com.ftejero21.traza.ai.Msg;
import com.ftejero21.traza.ai.PromptBuilders;
import com.ftejero21.traza.ai.PromptBuilders.DbSchema;
import com.ftejero21.traza.ai.PromptBuilders.DiagnosisCtx;
import com.ftejero21.traza.ai.PromptBuilders.FlowSql;
import com.ftejero21.traza.ai.PromptBuilders.Seed;
import com.ftejero21.traza.ai.PromptBuilders.UiMockInput;
import com.ftejero21.traza.ai.PromptBuilders.UiPlanInput;
import com.ftejero21.traza.ai.Prompts;
import com.ftejero21.traza.ai.Semantics;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.SecretMasker;
import com.ftejero21.traza.mcp.JobRegistry.JobTask;
import com.ftejero21.traza.tools.AgentSessions;
import com.ftejero21.traza.tools.AgentToolSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Port de flowRequest, compareChange y dbExplain de host-ai.ts: decodifica y valida la entrada y la convierte en un job. */
public final class JobFactory {
	private static final int MAX_USER_CHARS = 100_000;
	private static final int AGENT_SEED_CHARS = 45_000;

	private JobFactory() {
	}

	private static String lang(JsonObject body) {
		return "en".equals(J.str(body, "language")) ? "en" : "es";
	}

	private static CompletableFuture<JsonElement> failed(RuntimeException e) {
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		f.completeExceptionally(e);
		return f;
	}

	private static JobTask task(String op, boolean agent, String lang, IntFunction<String> user, int maxChars, Function<JsonObject, Validation> check) {
		JobTask t = new JobTask();
		t.op = op;
		t.agent = agent;
		t.system = Prompts.systemFor(op, op.equals("DB_EXPLAIN") ? lang : "es", agent);
		t.user = user.apply(maxChars);
		t.check = check;
		return t;
	}

	/** Lee (solo lectura, enmascarado) los archivos de partida; los que no se encuentran van como pista de busqueda. */
	private static String readSeeds(AgentToolSession session, List<Seed> seeds, int maxLines, int maxChars) {
		StringBuilder sb = new StringBuilder();
		for (Seed seed : seeds) {
			String path = session.pathFor(seed.mountId, seed.file);
			if (path != null) {
				JsonObject args = new JsonObject();
				args.addProperty("path", path);
				args.addProperty("startLine", 1);
				args.addProperty("endLine", maxLines);
				sb.append(session.invoke("traza_read", args, maxChars)).append("\n\n");
			} else {
				sb.append("- ").append(seed.mountId.isEmpty() ? "" : seed.mountId + "/").append(seed.file).append(':').append(seed.line).append(" (no encontrado: búscalo)\n");
			}
		}
		return sb.toString();
	}

	/* ------------------------------ flow ------------------------------ */

	/** Simular, analizar logs, plan de pantalla, maqueta y mapa de datos (FlowDiagnosisResponse). */
	public static CompletableFuture<JsonElement> flowRequest(String id, String kind, JsonObject body, Consumer<String> progress) {
		String lang = lang(body);
		try {
			JsonObject ctx = PromptBuilders.decodeContext(J.str(body, "contextBase64"), lang);
			String operation;
			JobTask task;
			switch (kind) {
			case "simulate":
			case "logs": {
				DiagnosisCtx d = PromptBuilders.decodeDiagnosis(ctx, lang);
				boolean simulate = kind.equals("simulate");
				String tail;
				int lineCount = 0;
				if (simulate) {
					JsonElement input = ctx.get("input");
					if (input == null || PromptBuilders.json(input).length() > PromptBuilders.MAX_INPUT_CHARS) {
						throw new AiException(Msg.t("invalid", lang));
					}
					tail = PromptBuilders.simulateTail(SecretMasker.maskSensitiveJson(input), lang);
				} else {
					JsonElement logs = ctx.get("logs");
					JsonElement pre = ctx.get("pre");
					if (!J.isStr(logs) || logs.getAsString().trim().isEmpty() || logs.getAsString().length() > PromptBuilders.MAX_LOG_CHARS) {
						throw new AiException(Msg.t("invalid", lang));
					}
					if (!Semantics.isObj(pre) || PromptBuilders.json(pre).length() > PromptBuilders.MAX_PRE_CHARS) {
						throw new AiException(Msg.t("invalid", lang));
					}
					String masked = SecretMasker.maskSensitiveText(logs.getAsString());
					String[] lines = masked.split("\r?\n", -1);
					Double requested = J.num(ctx.get("logLines"));
					lineCount = requested != null && requested > 0 ? (int) Math.min((long) requested.doubleValue(), lines.length) : lines.length;
					tail = PromptBuilders.logsTail(lines, SecretMasker.maskSensitiveJson(pre), lang);
				}
				operation = simulate ? "SIMULATE" : "LOGS";
				task = task(operation, false, lang, PromptBuilders.fitCode(d.flow, d.code, null, tail), MAX_USER_CHARS, Checks.diagnosis(simulate, lineCount));
				break;
			}
			case "ui-plan": {
				UiPlanInput ui = PromptBuilders.decodeUiPlan(ctx, lang);
				String user = PromptBuilders.buildUiPlanText(ui.input, ui.change, lang);
				if (user.length() > PromptBuilders.MAX_UI_PROMPT_CHARS) {
					throw new AiException(Msg.t("uiTooLarge", lang));
				}
				operation = "UI_PLAN";
				task = task(operation, false, lang, max -> user, MAX_USER_CHARS, Checks.uiPlan());
				break;
			}
			case "ui-mock": {
				UiMockInput mock = PromptBuilders.decodeUiMock(ctx, lang);
				operation = "UI_MOCK";
				AgentToolSession session = AgentSessions.get();
				String seedText = readSeeds(session, mock.seeds, PromptBuilders.UI_MOCK_SEED_LINES, PromptBuilders.UI_MOCK_SEED_FILE_CHARS);
				String overview = session.overview();
				task = task(operation, true, lang, PromptBuilders.buildUiMockText(mock, seedText, overview, lang), AGENT_SEED_CHARS, Checks.uiMock());
				break;
			}
			case "db-map-sql": {
				DiagnosisCtx d = PromptBuilders.decodeDiagnosis(ctx, lang);
				DbSchema schema = PromptBuilders.decodeDbSchema(ctx, lang);
				List<Seed> seeds = PromptBuilders.decodeSeeds(ctx.get("seeds"), PromptBuilders.MAX_DB_SQL_SEEDS, lang);
				List<FlowSql> flowSql = PromptBuilders.decodeFlowSql(ctx.get("flowSql"), d.flow, lang);
				String reason = "empty".equals(J.str(ctx, "reason")) ? "empty" : "failed";
				String schemaText = schema.empty ? "(sin conexion)" : Semantics.cut(PromptBuilders.json(schema.schema), PromptBuilders.DB_SQL_SCHEMA_CHARS);
				operation = "DB_MAP_SQL";
				AgentToolSession session = AgentSessions.get();
				String seedText = readSeeds(session, seeds, PromptBuilders.DB_SQL_SEED_LINES, PromptBuilders.DB_SQL_SEED_FILE_CHARS);
				String overview = session.overview();
				task = task(operation, true, lang, PromptBuilders.buildDbSqlText(d.flow, reason, flowSql, schemaText, seedText, overview, lang), AGENT_SEED_CHARS,
					Checks.dbMapSql(d.flow));
				break;
			}
			default: {
				DiagnosisCtx d = PromptBuilders.decodeDiagnosis(ctx, lang);
				DbSchema schema = PromptBuilders.decodeDbSchema(ctx, lang);
				String tail = "## ESQUEMA REAL\n" + (schema.empty ? "(sin conexion)" : PromptBuilders.json(schema.schema)) + "\n\n" + PromptBuilders.idiomaTail(lang);
				operation = "DB_MAP";
				task = task(operation, false, lang, PromptBuilders.fitCode(d.flow, d.code, null, tail), MAX_USER_CHARS, Checks.dbMap(d.flow));
				break;
			}
			}
			final String op = operation;
			return JobRegistry.submit(id, kind, lang, "schema", new ArrayList<>(List.of(task)), tasks -> {
				JsonObject r = new JsonObject();
				r.addProperty("success", true);
				r.addProperty("codigo", "OK");
				r.add("mensaje", JsonNull.INSTANCE);
				r.addProperty("operation", op);
				r.add("result", tasks.get(0).result);
				r.addProperty("model", JobRegistry.MODEL);
				r.add("usage", JsonNull.INSTANCE);
				r.addProperty("cached", tasks.get(0).cached);
				return r;
			}, progress);
		} catch (AiException e) {
			return failed(e);
		} catch (IllegalStateException e) {
			TrazaPlugin.warn("No se pudo preparar la peticion de la vista (" + e.getClass().getSimpleName() + ").");
			return failed(new AiException(Msg.t("noFolders", lang)));
		}
	}

	/* ------------------------------ comparar ------------------------------ */

	/** «Simular cambios»: antes (SIMULATE_BEFORE) y despues (SIMULATE_AFTER) por caso. */
	public static CompletableFuture<JsonElement> compareChange(String id, String proposalId, JsonObject body, Consumer<String> progress) {
		String lang = lang(body);
		try {
			JsonObject ctx = PromptBuilders.decodeContext(J.str(body, "contextBase64"), lang);
			DiagnosisCtx d = PromptBuilders.decodeDiagnosis(ctx, lang);
			if (!J.str(body, "flowId").trim().equals(d.flowId)) {
				throw new AiException(Msg.t("invalid", lang));
			}
			List<ChangeFile> files = PromptBuilders.validChangeFiles(ctx.get("files"), lang);
			Set<String> presetIds = new HashSet<>();
			for (JsonElement preset : J.arr(d.flow.get("simulationPresets"))) {
				String presetId = J.obj(preset) != null ? Semantics.txt(preset.getAsJsonObject().get("id")) : "";
				if (!presetId.isEmpty()) {
					presetIds.add(presetId);
				}
			}
			JsonElement rawCases = ctx.get("cases");
			if (!J.isArr(rawCases) || rawCases.getAsJsonArray().size() == 0 || rawCases.getAsJsonArray().size() > PromptBuilders.MAX_COMPARE_CASES) {
				throw new AiException(Msg.t("invalid", lang));
			}
			List<String> casePreset = new ArrayList<>();
			List<JsonElement> caseInput = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (JsonElement item : rawCases.getAsJsonArray()) {
				JsonObject o = J.obj(item);
				JsonElement presetId = o == null ? null : o.get("presetId");
				if (!J.isStr(presetId) || !presetIds.contains(presetId.getAsString()) || !seen.add(presetId.getAsString())) {
					throw new AiException(Msg.t("invalid", lang));
				}
				JsonElement input = o.get("input");
				if (input == null || PromptBuilders.json(input).length() > PromptBuilders.MAX_INPUT_CHARS) {
					throw new AiException(Msg.t("invalid", lang));
				}
				casePreset.add(presetId.getAsString());
				caseInput.add(SecretMasker.maskSensitiveJson(input));
			}
			String title = J.isStr(ctx.get("title")) ? Semantics.cut(ctx.get("title").getAsString().trim(), PromptBuilders.MAX_CHANGE_TITLE) : "";
			String summary = J.isStr(ctx.get("summary")) ? Semantics.cut(ctx.get("summary").getAsString().trim(), PromptBuilders.MAX_CHANGE_SUMMARY) : "";
			List<CodeWindow> afterCode;
			boolean unapplied = false;
			try {
				afterCode = ChangePatcher.afterWindows(d.code, files);
			} catch (PatchError e) {
				afterCode = d.code;
				unapplied = true;
				TrazaPlugin.info("Comparar · cambio no aplicable a las ventanas (" + e.getMessage() + "); el modelo razona el despues con el diff");
			}
			String changeText = Semantics.changeDiff(PromptBuilders.changeJson(title, summary, files));
			List<JobTask> tasks = new ArrayList<>();
			Function<JsonObject, Validation> check = Checks.diagnosis(true, 0);
			for (JsonElement input : caseInput) {
				String tailBefore = PromptBuilders.simulateTail(input, lang);
				tasks.add(task("SIMULATE_BEFORE", false, lang, PromptBuilders.fitCode(d.flow, d.code, changeText, tailBefore, false), MAX_USER_CHARS, check));
				String tailAfter = unapplied ? PromptBuilders.UNAPPLIED_NOTE + tailBefore : tailBefore;
				tasks.add(task("SIMULATE_AFTER", false, lang, PromptBuilders.fitCode(d.flow, afterCode, changeText, tailAfter), MAX_USER_CHARS, check));
			}
			final String flowId = d.flowId;
			return JobRegistry.submit(id, "compare", lang, "schema", tasks, done -> {
				JsonArray cases = new JsonArray();
				boolean allCached = true;
				for (int i = 0; i < casePreset.size(); i++) {
					JsonObject c = new JsonObject();
					c.addProperty("presetId", casePreset.get(i));
					c.add("before", done.get(i * 2).result);
					c.add("after", done.get(i * 2 + 1).result);
					cases.add(c);
					allCached &= done.get(i * 2).cached && done.get(i * 2 + 1).cached;
				}
				JsonObject comparison = new JsonObject();
				comparison.addProperty("proposalId", proposalId);
				comparison.addProperty("flowId", flowId);
				comparison.add("cases", cases);
				JsonObject r = new JsonObject();
				r.addProperty("success", true);
				r.addProperty("codigo", "OK");
				r.add("mensaje", JsonNull.INSTANCE);
				r.add("comparison", comparison);
				r.addProperty("model", JobRegistry.MODEL);
				r.add("usage", JsonNull.INSTANCE);
				r.addProperty("cached", allCached);
				return r;
			}, progress);
		} catch (AiException e) {
			return failed(e);
		}
	}

	/* ------------------------------ tabla ------------------------------ */

	/** «Mejorar con IA» de la explicacion de una tabla (DbExplainAiResponse). */
	public static CompletableFuture<JsonElement> dbExplain(String id, JsonObject body, Consumer<String> progress) {
		String lang = lang(body);
		try {
			String text;
			try {
				text = new String(Base64.getDecoder().decode(J.str(body, "contextBase64").trim()), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException e) {
				throw new AiException(Msg.t("dbInvalid", lang));
			}
			if (text.isEmpty()) {
				throw new AiException(Msg.t("dbInvalid", lang));
			}
			if (text.length() > PromptBuilders.MAX_DB_EXPLAIN_CONTEXT) {
				throw new AiException(Msg.t("dbTooLong", lang));
			}
			JsonElement context;
			try {
				context = Semantics.parseStrict(text);
			} catch (RuntimeException e) {
				throw new AiException(Msg.t("dbInvalid", lang));
			}
			JsonObject target = Semantics.isObj(context) ? J.obj(context.getAsJsonObject().get("target")) : null;
			if (target == null || !J.isStr(target.get("name")) || target.get("name").getAsString().trim().isEmpty()) {
				throw new AiException(Msg.t("dbInvalid", lang));
			}
			JsonObject root = context.getAsJsonObject();
			Set<String> allowedPlaces = new HashSet<>(List.of("@app", "@api"));
			PromptBuilders.addText(allowedPlaces, target.get("name"));
			for (JsonElement related : J.arr(root.get("related"))) {
				PromptBuilders.addText(allowedPlaces, J.obj(related) != null ? related.getAsJsonObject().get("name") : null);
			}
			for (JsonElement fk : J.arr(target.get("foreignKeys"))) {
				PromptBuilders.addText(allowedPlaces, J.obj(fk) != null ? fk.getAsJsonObject().get("refTable") : null);
			}
			for (JsonElement ref : J.arr(target.get("referencedBy"))) {
				PromptBuilders.addText(allowedPlaces, J.obj(ref) != null ? ref.getAsJsonObject().get("table") : null);
			}
			Set<String> targetColumns = new HashSet<>();
			for (JsonElement column : J.arr(target.get("columns"))) {
				PromptBuilders.addText(targetColumns, J.obj(column) != null ? column.getAsJsonObject().get("name") : null);
			}
			String user = "Contexto de la base de datos y del proyecto (JSON):\n" + PromptBuilders.json(context);
			JobTask task = task("DB_EXPLAIN", false, lang, max -> user, MAX_USER_CHARS, Checks.dbExplain(allowedPlaces, targetColumns));
			return JobRegistry.submit(id, "dbExplain", lang, "dbBadOutput", new ArrayList<>(List.of(task)), tasks -> {
				JsonObject parsed = tasks.get(0).result.getAsJsonObject();
				JsonObject r = new JsonObject();
				r.addProperty("success", true);
				r.addProperty("codigo", "OK");
				r.addProperty("mensaje", "Explicacion generada.");
				r.add("summary", parsed.get("summary"));
				r.add("steps", parsed.get("steps"));
				return r;
			}, progress);
		} catch (AiException e) {
			return failed(e);
		}
	}
}
