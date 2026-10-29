package com.ftejero21.traza.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.Checks;
import com.ftejero21.traza.ai.Checks.Validation;
import com.ftejero21.traza.ai.ChangeRelevance;
import com.ftejero21.traza.ai.FlowContextCollector;
import com.ftejero21.traza.ai.FlowContextCollector.CollectOptions;
import com.ftejero21.traza.ai.FlowContextCollector.FlowContext;
import com.ftejero21.traza.ai.FlowSummary;
import com.ftejero21.traza.ai.Msg;
import com.ftejero21.traza.ai.PromptBuilders;
import com.ftejero21.traza.ai.PromptBuilders.FlowTextInput;
import com.ftejero21.traza.ai.Prompts;
import com.ftejero21.traza.ai.SchemaValidator;
import com.ftejero21.traza.ai.Semantics;
import com.ftejero21.traza.core.EditorHint;
import com.ftejero21.traza.core.FlowStore;
import com.ftejero21.traza.core.FlowStore.GeneratedFlow;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.SecretMasker;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.mcp.JobRegistry.ToolText;
import com.ftejero21.traza.mcp.TaskRegistry.PendingRequest;
import com.ftejero21.traza.mcp.TaskRegistry.Task;
import com.ftejero21.traza.tools.AgentSessions;
import com.ftejero21.traza.tools.AgentToolSession;
import com.ftejero21.traza.ui.FlowView;
import com.ftejero21.traza.ui.Ui;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Herramientas y prompts MCP de Traza. Toda entrada de Copilot se trata como no confiable: se valida contra el esquema y
 * los limites. Ninguna herramienta escribe archivos ni ejecuta nada; las de entrega solo actualizan la vista.
 */
public final class McpTools {
	private static final int AGENT_SEED_CHARS = 45_000;
	private static final int AGENT_TOOL_RESULT_CHARS = 16_000;
	private static final int MAX_ATTEMPTS = 3;
	private static final int MAX_QUESTION = Msg.MAX_QUESTION_CHARS;
	private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

	private McpTools() {
	}

	/* ------------------------------ definiciones ------------------------------ */

	private static JsonObject prop(String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		if (description != null) {
			p.addProperty("description", description);
		}
		return p;
	}

	private static JsonObject str(String description, Integer min, Integer max) {
		JsonObject p = prop("string", description);
		if (min != null) {
			p.addProperty("minLength", min);
		}
		if (max != null) {
			p.addProperty("maxLength", max);
		}
		return p;
	}

	private static JsonObject objOrString(String description) {
		JsonObject p = new JsonObject();
		JsonArray types = new JsonArray();
		types.add("object");
		types.add("string");
		p.add("type", types);
		p.addProperty("description", description);
		return p;
	}

	private static JsonObject tool(String name, String description, JsonObject properties, String... required) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		JsonArray req = new JsonArray();
		for (String r : required) {
			req.add(r);
		}
		if (required.length > 0) {
			schema.add("required", req);
		}
		schema.addProperty("additionalProperties", false);
		JsonObject annotations = new JsonObject();
		annotations.addProperty("readOnlyHint", true);
		annotations.addProperty("openWorldHint", false);
		JsonObject t = new JsonObject();
		t.addProperty("name", name);
		t.addProperty("description", description);
		t.add("inputSchema", schema);
		t.add("annotations", annotations);
		return t;
	}

	private static final List<JsonObject> TOOLS = new ArrayList<>();

	static {
		JsonObject search = new JsonObject();
		search.add("query", str("Text, regex or symbol name to search for (1-200 characters).", 1, 200));
		JsonObject mode = prop("string", "text (default): case-insensitive literal; regex: a regular expression; symbol: class/function/method declaration by name.");
		JsonArray modes = new JsonArray();
		modes.add("text");
		modes.add("regex");
		modes.add("symbol");
		mode.add("enum", modes);
		search.add("mode", mode);
		search.add("path", prop("string", "Optional folder name or directory prefix (as in the map) to restrict the search. Omit to search every folder."));
		search.add("pattern", prop("string", "Optional file glob such as *.java or src/**/*.ts."));
		JsonObject max = prop("integer", "Maximum matching lines (1-80, default 40).");
		max.addProperty("minimum", 1);
		max.addProperty("maximum", 80);
		search.add("maxResults", max);
		TOOLS.add(tool("traza_search",
			"Read-only search of text, regex or symbol declarations in ALL open workspace folders. Returns folder/path:line matches. Use it to find endpoints, services, repositories, constants and callers in any folder.",
			search, "query"));

		JsonObject read = new JsonObject();
		read.add("path", prop("string", "File path as shown in the map or in search results."));
		JsonObject startLine = prop("integer", "First line to read (1-based, minimum 1).");
		startLine.addProperty("minimum", 1);
		read.add("startLine", startLine);
		JsonObject endLine = prop("integer", "Last line to read (minimum 1).");
		endLine.addProperty("minimum", 1);
		read.add("endLine", endLine);
		TOOLS.add(tool("traza_read",
			"Read-only: returns a file (or a line range) of the workspace with line numbers. Path uses the format of the project map (folder/path in multi-root).",
			read, "path"));

		JsonObject list = new JsonObject();
		list.add("path", prop("string", "Optional folder name or directory prefix."));
		list.add("pattern", prop("string", "Optional file glob such as *.java or **/*Controller*."));
		TOOLS.add(tool("traza_list",
			"Read-only: lists the workspace files of any folder, optionally filtered by directory and glob. Without arguments returns an overview of every open folder.",
			list));

		JsonObject flow = new JsonObject();
		flow.add("question", str("The user's question about how a process or feature works, in the user's own words (1-4000 characters).", 1, MAX_QUESTION));
		flow.add("requestId", str("Optional request identifier given in the prompt (letters, digits and hyphens, 8-64 characters).", null, 64));
		flow.add("folder", str("Optional name of the workspace folder (project) to start from.", null, 200));
		TOOLS.add(tool("traza_flow",
			"Start generating an interactive Traza flow diagram (sequence diagram across layers: UI, component, service, API, database) that explains how a process or feature of the open workspace works. "
				+ "Use it when the user asks how a process or functionality works, or mentions Traza, /flujo or @traza. Returns instructions and project context; investigate with traza_search, traza_read and traza_list, "
				+ "then deliver the result with traza_submit_flow.",
			flow, "question"));

		JsonObject submitFlow = new JsonObject();
		submitFlow.add("taskId", str("The taskId returned by traza_flow.", 1, 100));
		submitFlow.add("flow", objOrString("The FlowDiagram JSON object (or its JSON text) that follows the schema in the instructions."));
		TOOLS.add(tool("traza_submit_flow",
			"Deliver the FlowDiagram JSON produced for a traza_flow task. Traza validates it, opens the flow in its view and returns a step summary to answer the user in text.",
			submitFlow, "taskId", "flow"));

		JsonObject change = new JsonObject();
		change.add("request", str("The change the user asks for, in the user's own words (1-4000 characters).", 1, MAX_QUESTION));
		change.add("requestId", str("Optional request identifier given in the prompt (letters, digits and hyphens, 8-64 characters).", null, 64));
		change.add("flowQuestion", str("Optional question used to generate the related flow when none is open (up to 4000 characters).", null, MAX_QUESTION));
		TOOLS.add(tool("traza_change",
			"Start proposing a code change as a Traza diff that can be simulated before/after (it never modifies files). Use it when the user asks to change, add, remove or fix behavior in the project, or mentions /cambio. "
				+ "Returns instructions and context; deliver the result with traza_submit_change.",
			change, "request"));

		JsonObject submitChange = new JsonObject();
		submitChange.add("taskId", str("The taskId returned by traza_change or traza_submit_flow.", 1, 100));
		submitChange.add("proposal", objOrString("The ChangeProposal JSON object (or its JSON text)."));
		submitChange.add("answer", str("A clarification to show to the user instead of a proposal, when the request cannot be resolved.", null, 20_000));
		TOOLS.add(tool("traza_submit_change",
			"Deliver the ChangeProposal JSON produced for a traza_change task (or a clarification in answer). Traza validates it and opens the change in its Simulate changes view.",
			submitChange, "taskId"));

		JsonObject getJob = new JsonObject();
		getJob.add("jobId", str("The jobId given in the prompt (letters, digits, . _ : -).", 1, 128));
		TOOLS.add(tool("traza_get_job",
			"Get the next pending task of a Traza view job (simulate, analyze logs, screen view, data map, change simulation or table explanation). Call it repeatedly until it reports that no tasks are left.",
			getJob, "jobId"));

		JsonObject submitJob = new JsonObject();
		submitJob.add("jobId", str("The jobId of the job.", 1, 128));
		submitJob.add("taskId", str("The taskId returned by traza_get_job.", 1, 100));
		submitJob.add("result", objOrString("The JSON object (or its JSON text) that answers the task, following the schema in the task instructions."));
		TOOLS.add(tool("traza_submit_job", "Deliver the JSON result of a task obtained with traza_get_job. Traza validates it and continues with the next task.",
			submitJob, "jobId", "taskId", "result"));
	}

	public static JsonArray listJson() {
		JsonArray out = new JsonArray();
		TOOLS.forEach(t -> out.add(t.deepCopy()));
		return out;
	}

	public static boolean exists(String name) {
		for (JsonObject t : TOOLS) {
			if (t.get("name").getAsString().equals(name)) {
				return true;
			}
		}
		return false;
	}

	private static JsonObject schemaOf(String name) {
		for (JsonObject t : TOOLS) {
			if (t.get("name").getAsString().equals(name)) {
				return t.getAsJsonObject("inputSchema");
			}
		}
		return null;
	}

	/* ------------------------------ llamada ------------------------------ */

	/** Ejecuta una herramienta. Nunca lanza: los errores se devuelven como resultado con isError. */
	public static ToolText call(String name, JsonObject args) {
		List<String> errors = SchemaValidator.validateWith(schemaOf(name), args);
		if (!errors.isEmpty()) {
			return err("Argumentos no válidos: " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
		}
		try {
			switch (name) {
			case "traza_search":
			case "traza_read":
			case "traza_list":
				return agentTool(name, args);
			case "traza_flow":
				return flow(args);
			case "traza_submit_flow":
				return submitFlow(args);
			case "traza_change":
				return change(args);
			case "traza_submit_change":
				return submitChange(args);
			case "traza_get_job":
				return getJob(args);
			case "traza_submit_job":
				return submitJob(args);
			default:
				return err("Herramienta desconocida.");
			}
		} catch (IllegalStateException e) {
			TrazaPlugin.warn("Herramienta " + name + ": " + e.getClass().getSimpleName());
			return err(Msg.t("noFolders"));
		} catch (RuntimeException e) {
			TrazaPlugin.error("Error en la herramienta " + name + ".", e);
			return err(Msg.t("failed"));
		}
	}

	private static ToolText err(String text) {
		return new ToolText(text, true);
	}

	private static ToolText ok(String text) {
		return new ToolText(text, false);
	}

	/* ------------------------------ investigacion ------------------------------ */

	private static ToolText agentTool(String name, JsonObject args) {
		if (!WorkspaceFiles.hasProjects()) {
			return err(Msg.t("noFolders"));
		}
		AgentToolSession session = AgentSessions.get();
		return ok(session.invoke(name, args, AGENT_TOOL_RESULT_CHARS));
	}

	/* ------------------------------ flujo ------------------------------ */

	private static String optStr(JsonObject args, String key) {
		JsonElement v = args.get(key);
		return J.isStr(v) && !v.getAsString().trim().isEmpty() ? v.getAsString().trim() : null;
	}

	private static String entrega(String kind, String taskId, String lang) {
		if (kind.equals("FLOW")) {
			return "\n\n## ENTREGA\nCuando tengas el JSON FlowDiagram llama a traza_submit_flow con taskId=" + taskId + " y flow=<objeto>. No muestres el JSON al usuario.";
		}
		return "\n\n## ENTREGA\nCuando tengas el JSON ChangeProposal llama a traza_submit_change con taskId=" + taskId
			+ " y proposal=<objeto ChangeProposal>, o con answer=<aclaración> si necesitas preguntar algo al usuario. No muestres el JSON al usuario.";
	}

	private static ToolText flow(JsonObject args) {
		String question = optStr(args, "question");
		if (question == null || question.length() > MAX_QUESTION) {
			return err(Msg.t("question"));
		}
		CopilotBridge.showNoticeOnce();
		return startFlowTask(question, optStr(args, "requestId"), optStr(args, "folder"), null, null);
	}

	/** Crea la tarea FLOW y devuelve las instrucciones (port de generateFlow en modo agente). */
	private static ToolText startFlowTask(String question, String requestId, String folderArg, String followUpChange, String note) {
		String lang = Msg.language();
		if (!WorkspaceFiles.hasProjects()) {
			return err(Msg.t("noFolders", lang));
		}
		if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
			return err("requestId no válido.");
		}
		String masked = SecretMasker.maskSensitiveText(question);
		PendingRequest pending = TaskRegistry.pending(requestId);
		EditorHint hint = pending != null ? pending.hint : EditorHint.captureActive();
		String folderHint = folderArg != null ? folderArg : pending != null ? pending.folder : null;
		String focus = FlowSummary.resolveFlowFolder(question, folderHint);
		boolean scenario = followUpChange == null && FlowSummary.isScenarioQuestion(question);
		AgentToolSession session = AgentSessions.get();
		CollectOptions co = new CollectOptions();
		co.question = masked;
		co.useActiveEditor = hint != null;
		co.hint = hint;
		FlowContext context = FlowContextCollector.collect(co);
		FlowTextInput input = new FlowTextInput();
		input.mode = "FLOW";
		input.question = masked;
		input.context = context;
		input.lang = lang;
		input.scenarioHint = scenario;
		input.overview = session.overview();
		input.focusFolder = focus;
		input.hintFiles = new ArrayList<>();
		if (hint != null && hint.fileId != null) {
			String p = session.pathOfUri(hint.fileId);
			if (p != null) {
				input.hintFiles.add(p);
			}
		}
		String text = PromptBuilders.fitFlowText(input).apply(AGENT_SEED_CHARS);
		Task task = new Task();
		task.kind = "FLOW";
		task.question = masked;
		task.originalQuestion = question;
		task.lang = lang;
		task.scenarioAsked = scenario;
		task.mode = pending != null ? pending.mode : null;
		task.folder = focus;
		task.followUpChange = followUpChange;
		TaskRegistry.add(task);
		return ok(Prompts.systemFor("FLOW", lang, true) + "\n\n" + text + (note != null ? "\n\n" + note : "") + entrega("FLOW", task.id, lang));
	}

	private static ToolText unknownTask() {
		return err("taskId desconocido o caducado: vuelve a empezar llamando a traza_flow o traza_change.");
	}

	private static String isoNow() {
		return Instant.now().toString();
	}

	private static ToolText submitFlow(JsonObject args) {
		Task task = TaskRegistry.get(J.str(args, "taskId"));
		if (task == null || !task.kind.equals("FLOW")) {
			return unknownTask();
		}
		Validation v = Checks.run(args.get("flow"), Checks.flow());
		if (!v.errors.isEmpty()) {
			task.attempts++;
			if (task.attempts >= MAX_ATTEMPTS) {
				TaskRegistry.remove(task.id);
				return err(Msg.t("schema", task.lang));
			}
			return err(PromptBuilders.formatFeedback("FlowDiagram", v.errors, task.attempts + 1, MAX_ATTEMPTS));
		}
		TaskRegistry.remove(task.id);
		JsonObject flow = v.value.getAsJsonObject();
		flow.addProperty("id", UUID.randomUUID().toString());
		flow.addProperty("model", JobRegistry.MODEL);
		flow.addProperty("generatedAt", isoNow());
		flow.addProperty("question", task.originalQuestion);
		String rawTitle = J.isStr(flow.get("title")) ? flow.get("title").getAsString().trim() : "";
		String title = rawTitle.isEmpty() ? "Sin título" : J.truncate(rawTitle, 60);
		GeneratedFlow generated = new GeneratedFlow(flow, title, JobRegistry.MODEL, task.scenarioAsked, task.folder);
		FlowStore.store(generated);
		final String mode = task.mode;
		Ui.async(() -> FlowView.showGenerated(generated, mode));
		if (task.followUpChange != null) {
			return startProposeTask(task.followUpChange, task.originalQuestion, flow, title, generated, task.lang, null);
		}
		String summary = FlowSummary.formatFlowChatMarkdown(generated, true, FlowSummary.PLAIN);
		String intro = task.lang.equals("en") ? "The flow is open in the Traza view." : "El flujo está abierto en la vista de Traza.";
		return ok(intro + "\n\n" + summary + "\n\n" + Prompts.answerNote("FLOW", task.question, task.lang, true));
	}

	/* ------------------------------ cambio ------------------------------ */

	private static ToolText change(JsonObject args) {
		String request = optStr(args, "request");
		if (request == null || request.length() > MAX_QUESTION) {
			return err(Msg.t("question"));
		}
		String flowQuestion = optStr(args, "flowQuestion");
		String requestId = optStr(args, "requestId");
		if (!WorkspaceFiles.hasProjects()) {
			return err(Msg.t("noFolders"));
		}
		CopilotBridge.showNoticeOnce();
		String lang = Msg.language();
		FlowView.CurrentFlow current = Ui.sync(FlowView::currentFlow);
		if (current != null && ChangeRelevance.isFlowRelevant(current.flow, request + " " + (flowQuestion == null ? "" : flowQuestion))) {
			return startProposeTask(request, flowQuestion, current.flow.getAsJsonObject(), current.title, null, lang, null);
		}
		String flowText = flowQuestion != null ? flowQuestion : request;
		String note = lang.equals("en")
			? "NOTE: no related flow is open. First generate the flow with the instructions above; after traza_submit_flow you will receive the instructions to propose the change: \"" + request + "\"."
			: "NOTA: no hay un flujo relacionado abierto. Genera primero el flujo con las instrucciones anteriores; tras traza_submit_flow recibirás las instrucciones para proponer el cambio: «" + request + "».";
		return startFlowTask(flowText, requestId, null, request, note);
	}

	/** Crea la tarea PROPOSE y devuelve las instrucciones (port de proposeChange en modo agente). */
	private static ToolText startProposeTask(String request, String flowQuestion, JsonObject flow, String flowTitle, GeneratedFlow generated, String lang, String note) {
		List<IProject> projects = WorkspaceFiles.openProjects();
		boolean multi = projects.size() > 1;
		Set<String> mountIds = new HashSet<>();
		projects.forEach(p -> mountIds.add(p.getName()));
		List<JsonElement> refs = new ArrayList<>();
		for (JsonElement s : J.arr(flow.get("steps"))) {
			refs.add(J.obj(s) != null ? s.getAsJsonObject().get("ref") : null);
		}
		for (JsonElement f : J.arr(flow.get("files"))) {
			refs.add(f);
		}
		List<String> pinnedPaths = new ArrayList<>();
		for (JsonElement ref : refs) {
			JsonObject r = J.obj(ref);
			if (r == null || !J.isStr(r.get("file")) || r.get("file").getAsString().isEmpty()) {
				continue;
			}
			String mountId = J.isStr(r.get("mountId")) ? r.get("mountId").getAsString() : "";
			String path = multi && !mountId.isEmpty() ? mountId + "/" + r.get("file").getAsString() : r.get("file").getAsString();
			if (!pinnedPaths.contains(path)) {
				pinnedPaths.add(path);
			}
		}
		String masked = SecretMasker.maskSensitiveText(request);
		String activeFlow = PromptBuilders.json(PromptBuilders.stripFlow(flow, false));
		AgentToolSession session = AgentSessions.get();
		CollectOptions co = new CollectOptions();
		co.question = masked;
		co.pinnedPaths = pinnedPaths;
		co.useActiveEditor = false;
		co.prioritizeRequest = true;
		FlowContext context = FlowContextCollector.collect(co);
		FlowTextInput input = new FlowTextInput();
		input.mode = "PROPOSE";
		input.question = masked;
		input.context = context;
		input.activeFlow = activeFlow;
		input.lang = lang;
		input.overview = session.overview();
		input.hintFiles = new ArrayList<>(pinnedPaths.subList(0, Math.min(PromptBuilders.AGENT_MAX_HINT_FILES, pinnedPaths.size())));
		String text = PromptBuilders.fitFlowText(input).apply(AGENT_SEED_CHARS);
		Task task = new Task();
		task.kind = "PROPOSE";
		task.question = masked;
		task.originalQuestion = request;
		task.lang = lang;
		task.flow = flow;
		task.flowTitle = flowTitle;
		task.generated = generated;
		task.multi = multi;
		task.mountIds = mountIds;
		TaskRegistry.add(task);
		return ok(Prompts.systemFor("PROPOSE", lang, true) + "\n\n" + text + (note != null ? "\n\n" + note : "") + entrega("PROPOSE", task.id, lang));
	}

	private static ToolText submitChange(JsonObject args) {
		Task task = TaskRegistry.get(J.str(args, "taskId"));
		if (task == null || !task.kind.equals("PROPOSE")) {
			return unknownTask();
		}
		JsonElement proposalArg = args.get("proposal");
		String answerArg = optStr(args, "answer");
		if (J.isNull(proposalArg) && answerArg != null) {
			return clarification(task, answerArg);
		}
		if (J.isNull(proposalArg)) {
			return err("Falta proposal (objeto ChangeProposal) o answer (aclaración).");
		}
		Validation v = Checks.run(proposalArg, Checks.propose(task.originalQuestion, task.mountIds, task.multi));
		if (!v.errors.isEmpty()) {
			task.attempts++;
			if (task.attempts >= MAX_ATTEMPTS) {
				TaskRegistry.remove(task.id);
				if (task.generated != null) {
					GeneratedFlow g = task.generated;
					Ui.async(() -> FlowView.showGenerated(g, null));
				}
				return err(Msg.t("schema", task.lang));
			}
			return err(PromptBuilders.formatFeedback("ChangeProposal", v.errors, task.attempts + 1, MAX_ATTEMPTS));
		}
		JsonObject value = v.value.getAsJsonObject();
		if (J.isStr(value.get("answer"))) {
			return clarification(task, value.get("answer").getAsString());
		}
		TaskRegistry.remove(task.id);
		JsonObject proposal = value.getAsJsonObject("change").deepCopy();
		proposal.addProperty("id", UUID.randomUUID().toString());
		proposal.addProperty("request", task.originalQuestion);
		String flowId = J.isStr(task.flow.get("id")) ? task.flow.get("id").getAsString() : null;
		if (flowId == null) {
			proposal.add("flowId", com.google.gson.JsonNull.INSTANCE);
		} else {
			proposal.addProperty("flowId", flowId);
		}
		JsonArray cases = PromptBuilders.sanitizeProposalCases(proposal.get("cases"), task.flow);
		if (cases.size() > 0) {
			proposal.add("cases", cases);
		} else {
			proposal.remove("cases");
		}
		proposal.addProperty("createdAt", isoNow());
		if (flowId != null) {
			FlowStore.rememberProposal(flowId, proposal);
		}
		final String fid = flowId;
		final GeneratedFlow generated = task.generated;
		Ui.async(() -> openProposal(generated, fid, proposal));
		String summary = FlowSummary.formatChangeChatMarkdown(proposal, task.flowTitle, generated != null, FlowSummary.PLAIN);
		return ok(summary + "\n\n" + Prompts.answerNote("PROPOSE", task.question, task.lang, true));
	}

	/** Abre la vista en modo changes: addProposal y, si falla, show con las propuestas guardadas (UI). */
	private static void openProposal(GeneratedFlow generated, String flowId, JsonObject proposal) {
		if (generated != null) {
			List<JsonObject> saved = flowId != null ? FlowStore.proposalsFor(flowId) : new ArrayList<>();
			List<JsonElement> proposals = new ArrayList<>(saved.isEmpty() ? List.of(proposal) : saved);
			FlowView.show(new FlowView.FlowPayload(generated.flow, false, generated.title, proposals, generated.model, "changes"));
		} else if (!FlowView.addProposal(proposal) && flowId != null) {
			GeneratedFlow g = FlowStore.flowById(flowId);
			if (g != null) {
				FlowView.show(new FlowView.FlowPayload(g.flow, false, g.title, new ArrayList<JsonElement>(FlowStore.proposalsFor(flowId)), g.model, "changes"));
			}
		}
	}

	private static ToolText clarification(Task task, String answer) {
		TaskRegistry.remove(task.id);
		if (task.generated != null) {
			GeneratedFlow g = task.generated;
			Ui.async(() -> FlowView.showGenerated(g, null));
		}
		return ok(Semantics.cut(answer, 20_000));
	}

	/* ------------------------------ jobs ------------------------------ */

	private static ToolText getJob(JsonObject args) {
		String jobId = J.str(args, "jobId");
		if (!JobRegistry.JOB_ID.matcher(jobId).matches()) {
			return err("jobId no válido.");
		}
		return JobRegistry.getJob(jobId);
	}

	private static ToolText submitJob(JsonObject args) {
		String jobId = J.str(args, "jobId");
		if (!JobRegistry.JOB_ID.matcher(jobId).matches()) {
			return err("jobId no válido.");
		}
		return JobRegistry.submitJob(jobId, J.str(args, "taskId"), args.get("result"));
	}

	/* ------------------------------ prompts ------------------------------ */

	public static JsonArray promptsJson() {
		JsonArray out = new JsonArray();
		out.add(promptDef("flujo", "Explica con Traza como funciona un proceso o funcionalidad del proyecto (flujo interactivo).", "pregunta",
			"La pregunta sobre el proceso o la funcionalidad."));
		out.add(promptDef("cambio", "Propone con Traza un cambio de codigo y lo abre en Simular cambios (no modifica archivos).", "peticion",
			"El cambio que quieres."));
		return out;
	}

	private static JsonObject promptDef(String name, String description, String argName, String argDescription) {
		JsonObject p = new JsonObject();
		p.addProperty("name", name);
		p.addProperty("description", description);
		JsonObject a = new JsonObject();
		a.addProperty("name", argName);
		a.addProperty("description", argDescription);
		a.addProperty("required", true);
		JsonArray args = new JsonArray();
		args.add(a);
		p.add("arguments", args);
		return p;
	}

	/** Mensaje de usuario del prompt, o null si el nombre o los argumentos no son validos. */
	public static JsonObject getPrompt(String name, JsonObject arguments) {
		String text;
		if (name.equals("flujo")) {
			String q = arguments == null ? null : optStr(arguments, "pregunta");
			if (q == null || q.length() > MAX_QUESTION) {
				return null;
			}
			text = "Usa la herramienta traza_flow de Traza con question=" + J.GSON.toJson(q) + " para generar el flujo de esta pregunta, sigue sus instrucciones "
				+ "y, al terminar, responde al usuario en texto: " + q;
		} else if (name.equals("cambio")) {
			String q = arguments == null ? null : optStr(arguments, "peticion");
			if (q == null || q.length() > MAX_QUESTION) {
				return null;
			}
			text = "Usa la herramienta traza_change de Traza con request=" + J.GSON.toJson(q) + " para proponer este cambio, sigue sus instrucciones "
				+ "y, al terminar, explica el cambio al usuario en texto: " + q;
		} else {
			return null;
		}
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", content);
		JsonArray messages = new JsonArray();
		messages.add(message);
		JsonObject out = new JsonObject();
		out.addProperty("description", name.equals("flujo") ? "Flujo de Traza" : "Cambio con Traza");
		out.add("messages", messages);
		return out;
	}
}
