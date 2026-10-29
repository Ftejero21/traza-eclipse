package com.ftejero21.traza.mcp;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.Semantics;
import com.ftejero21.traza.core.BundleFiles;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.mcp.JobRegistry.ToolText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Servidor MCP (JSON-RPC 2.0, streamable HTTP sin sesion): initialize, ping, tools y prompts. Respuestas application/json. */
public final class McpServer {
	private static final Set<String> SUPPORTED = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
	private static final String DEFAULT_VERSION = "2025-06-18";
	private static final AtomicLong CONNECTED_AT = new AtomicLong();

	private static final String INSTRUCTIONS = "Traza explains how code flows through a project and proposes simulated changes. "
		+ "Use traza_flow when the user asks how a process or feature works (or mentions Traza, /flujo or @traza) and traza_change for change requests: "
		+ "investigate with traza_search, traza_read and traza_list, then deliver the JSON with traza_submit_flow or traza_submit_change. "
		+ "Tasks from the Traza view are read with traza_get_job and delivered with traza_submit_job. No tool writes files.";

	/** Respuesta HTTP del endpoint MCP. */
	public static final class Result {
		public final int status;
		public final String body;

		Result(int status, String body) {
			this.status = status;
			this.body = body;
		}
	}

	private McpServer() {
	}

	/** Copilot se ha conectado (se recibio initialize). */
	public static boolean copilotConnected() {
		return CONNECTED_AT.get() > 0;
	}

	public static long connectedAt() {
		return CONNECTED_AT.get();
	}

	private static String version() {
		try {
			return BundleFiles.bundle().getVersion().toString();
		} catch (RuntimeException e) {
			return "0.1.0";
		}
	}

	/* ------------------------------ JSON-RPC ------------------------------ */

	public static Result handleHttp(String body) {
		JsonElement parsed;
		try {
			parsed = Semantics.parseStrict(body);
		} catch (RuntimeException e) {
			return new Result(200, J.GSON.toJson(error(JsonNull.INSTANCE, -32700, "Parse error")));
		}
		if (parsed.isJsonArray()) {
			JsonArray batch = parsed.getAsJsonArray();
			if (batch.size() == 0) {
				return new Result(200, J.GSON.toJson(error(JsonNull.INSTANCE, -32600, "Invalid Request")));
			}
			JsonArray out = new JsonArray();
			for (JsonElement item : batch) {
				JsonObject response = handleOne(item);
				if (response != null) {
					out.add(response);
				}
			}
			return out.size() == 0 ? new Result(202, null) : new Result(200, J.GSON.toJson(out));
		}
		JsonObject response = handleOne(parsed);
		return response == null ? new Result(202, null) : new Result(200, J.GSON.toJson(response));
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject e = new JsonObject();
		e.addProperty("code", code);
		e.addProperty("message", message);
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id == null ? JsonNull.INSTANCE : id);
		r.add("error", e);
		return r;
	}

	private static JsonObject result(JsonElement id, JsonElement result) {
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id);
		r.add("result", result);
		return r;
	}

	/** Atiende un mensaje; null si es una notificacion o una respuesta (sin respuesta). */
	private static JsonObject handleOne(JsonElement item) {
		JsonObject msg = J.obj(item);
		if (msg == null || !J.isStr(msg.get("method"))) {
			if (msg != null && (msg.has("result") || msg.has("error"))) {
				return null;
			}
			return error(JsonNull.INSTANCE, -32600, "Invalid Request");
		}
		String method = msg.get("method").getAsString();
		boolean notification = !msg.has("id");
		JsonElement id = notification ? JsonNull.INSTANCE : msg.get("id");
		JsonObject params = J.obj(msg.get("params"));
		if (notification || method.startsWith("notifications/")) {
			return null;
		}
		try {
			switch (method) {
			case "initialize":
				return result(id, initialize(params));
			case "ping":
				return result(id, new JsonObject());
			case "tools/list": {
				JsonObject r = new JsonObject();
				r.add("tools", McpTools.listJson());
				return result(id, r);
			}
			case "tools/call":
				return toolsCall(id, params);
			case "prompts/list": {
				JsonObject r = new JsonObject();
				r.add("prompts", McpTools.promptsJson());
				return result(id, r);
			}
			case "prompts/get":
				return promptsGet(id, params);
			default:
				return error(id, -32601, "Method not found");
			}
		} catch (RuntimeException e) {
			TrazaPlugin.error("Error al atender " + method + ".", e);
			return error(id, -32603, "Internal error");
		}
	}

	private static JsonObject initialize(JsonObject params) {
		String requested = params == null ? "" : J.str(params, "protocolVersion");
		String version = SUPPORTED.contains(requested) ? requested : DEFAULT_VERSION;
		JsonObject caps = params == null ? null : J.obj(params.get("capabilities"));
		boolean sampling = caps != null && caps.has("sampling");
		JsonObject clientInfo = params == null ? null : J.obj(params.get("clientInfo"));
		String client = clientInfo == null ? "?" : J.truncate(J.str(clientInfo, "name"), 60);
		TrazaPlugin.info("MCP initialize · cliente=" + client + " · protocolo=" + version + " · sampling=" + (sampling ? "sí" : "no"));
		CONNECTED_AT.set(System.currentTimeMillis());
		JsonObject listChanged = new JsonObject();
		listChanged.addProperty("listChanged", false);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", listChanged.deepCopy());
		capabilities.add("prompts", listChanged.deepCopy());
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "traza");
		serverInfo.addProperty("version", version());
		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", version);
		r.add("capabilities", capabilities);
		r.add("serverInfo", serverInfo);
		r.addProperty("instructions", INSTRUCTIONS);
		return r;
	}

	private static JsonObject toolsCall(JsonElement id, JsonObject params) {
		if (params == null || !J.isStr(params.get("name"))) {
			return error(id, -32602, "Invalid params");
		}
		String name = params.get("name").getAsString();
		if (!McpTools.exists(name)) {
			return error(id, -32602, "Unknown tool");
		}
		JsonElement argsEl = params.get("arguments");
		JsonObject args = J.isNull(argsEl) ? new JsonObject() : J.obj(argsEl);
		if (args == null) {
			return error(id, -32602, "Invalid params");
		}
		ToolText out = McpTools.call(name, args);
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", out.text);
		JsonArray list = new JsonArray();
		list.add(content);
		JsonObject r = new JsonObject();
		r.add("content", list);
		r.addProperty("isError", out.error);
		return result(id, r);
	}

	private static JsonObject promptsGet(JsonElement id, JsonObject params) {
		if (params == null || !J.isStr(params.get("name"))) {
			return error(id, -32602, "Invalid params");
		}
		JsonObject args = J.obj(params.get("arguments"));
		JsonObject prompt = McpTools.getPrompt(params.get("name").getAsString(), args);
		if (prompt == null) {
			return error(id, -32602, "Invalid params");
		}
		return result(id, prompt);
	}

	/** Nombres de las herramientas (para la pagina de preferencias). */
	public static List<String> toolNames() {
		List<String> names = new java.util.ArrayList<>();
		for (JsonElement t : McpTools.listJson()) {
			names.add(t.getAsJsonObject().get("name").getAsString());
		}
		return names;
	}
}
