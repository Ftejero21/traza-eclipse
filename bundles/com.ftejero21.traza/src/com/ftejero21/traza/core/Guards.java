package com.ftejero21.traza.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Port de host-guards.ts: validacion de los mensajes de la webview (no confiables) y utilidades de rutas
 * (`normalizeCodePath`, `isSensitiveCodeFile`, `codeExtension`, `BINARY_EXTENSIONS`).
 */
public final class Guards {
	public static final int CODE_MAX_NAME_LENGTH = 100;
	public static final int CODE_MAX_PATH_LENGTH = 260;
	public static final int MAX_CONTEXT_BASE64 = 400_000;
	public static final int MAX_COPY_CHARS = 10_000;
	public static final int MAX_ASK_CHARS = 4_000;
	public static final int MAX_NOTIFY_CHARS = 500;
	public static final int MAX_SAMPLE_CHARS = 2 * 1024 * 1024;
	public static final int MAX_APPLY_FILES = 12;
	public static final int MAX_APPLY_FILE_CHARS = 3 * 1024 * 1024;
	public static final int MAX_APPLY_TOTAL_CHARS = 8 * 1024 * 1024;
	public static final int MAX_APPLY_PATH = 520;
	public static final int MAX_DB_EXPLAIN_BASE64 = 80_000;
	public static final Pattern DB_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
	/** Hosts locales (http:// y conexiones sin TLS solo se permiten aqui). */
	public static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

	private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\u0000-\\u001f]");

	public static final Set<String> BINARY_EXTENSIONS = Set.of(
		"png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "avif",
		"woff", "woff2", "ttf", "otf", "eot",
		"mp3", "wav", "ogg", "mp4", "webm", "pdf", "zip",
		"jar", "war", "class", "exe", "dll", "so", "dylib", "bin", "gz", "tar", "7z", "rar", "glb", "psd", "docx", "xlsx", "pptx");

	private static final Set<String> OPS = Set.of("listFiles", "readFile", "readMountFile", "flow", "copy",
		"compareChange", "dbExplain", "applyFiles", "confirm", "dbConnections", "dbMetadata");
	private static final Set<String> KINDS = Set.of("simulate", "logs", "ui-plan", "ui-mock", "db-map", "db-map-sql");
	private static final Pattern PROPOSAL_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
	private static final Set<String> LANGUAGES = Set.of("es", "en");
	private static final Set<String> LEVELS = Set.of("info", "success", "warning", "danger");
	private static final Set<String> READ_AS = Set.of("text", "base64");
	private static final int MAX_ID = 128;
	private static final int MAX_DB_MAP_CHARS = 300_000;
	private static final Pattern FLOW_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

	private Guards() {
	}

	/** Normaliza una ruta relativa; devuelve null si intenta salir de la raiz o es invalida. */
	public static String normalizeCodePath(String raw) {
		String[] parts = (raw == null ? "" : raw).replace('\\', '/').split("/", -1);
		List<String> out = new ArrayList<>();
		for (String part : parts) {
			String segment = part.trim();
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..") || INVALID_NAME_CHARS.matcher(segment).find() || segment.length() > CODE_MAX_NAME_LENGTH) {
				return null;
			}
			out.add(segment);
		}
		String path = String.join("/", out);
		return path.length() > CODE_MAX_PATH_LENGTH ? null : path;
	}

	public static String codeExtension(String name) {
		int index = name.lastIndexOf('.');
		return index <= 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
	}

	public static boolean isBinaryCodeExtension(String extension) {
		return BINARY_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
	}

	private static final Pattern ENV_SAMPLE = Pattern.compile("^\\.env\\.(example|sample|template|dist)$");
	private static final Pattern KEY_EXT = Pattern.compile(".*\\.(pem|key|p12|pfx|jks|keystore)$");
	private static final Pattern SSH_KEY = Pattern.compile("^id_(rsa|dsa|ecdsa|ed25519).*");

	/** Claves y secretos: nunca se leen ni se listan. */
	public static boolean isSensitiveCodeFile(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (ENV_SAMPLE.matcher(lower).matches()) {
			return false;
		}
		return lower.equals(".env") || lower.startsWith(".env.") || KEY_EXT.matcher(lower).matches()
			|| SSH_KEY.matcher(lower).matches();
	}

	public static String baseName(String path) {
		int index = path.lastIndexOf('/');
		return index < 0 ? path : path.substring(index + 1);
	}

	/* ---------------------------- type guards ---------------------------- */

	private static boolean str(JsonElement v, int max) {
		return J.isStr(v) && v.getAsString().length() <= max;
	}

	private static String language(JsonElement v) {
		return J.isStr(v) && LANGUAGES.contains(v.getAsString()) ? v.getAsString() : null;
	}

	private static String base64(JsonElement v, int max) {
		return str(v, max) && J.isBase64(v.getAsString()) ? v.getAsString() : null;
	}

	/** Escrituras de applyFiles: 1-12, ruta <= 520, contenido <= 3 MiB (o null), linea >= 1 opcional, total <= 8 MiB. */
	private static JsonArray writes(JsonElement value) {
		if (!J.isArr(value)) {
			return null;
		}
		JsonArray in = value.getAsJsonArray();
		if (in.size() < 1 || in.size() > MAX_APPLY_FILES) {
			return null;
		}
		JsonArray out = new JsonArray();
		long total = 0;
		for (JsonElement item : in) {
			JsonObject w = J.obj(item);
			if (w == null || !str(w.get("path"), MAX_APPLY_PATH) || w.get("path").getAsString().isEmpty()) {
				return null;
			}
			JsonElement content = w.get("content");
			if (content == null) {
				return null;
			}
			if (!content.isJsonNull() && !str(content, MAX_APPLY_FILE_CHARS)) {
				return null;
			}
			total += J.isStr(content) ? content.getAsString().length() : 0;
			if (total > MAX_APPLY_TOTAL_CHARS) {
				return null;
			}
			JsonElement line = w.get("line");
			if (line != null && !line.isJsonNull()) {
				Long n = J.integer(line);
				if (n == null || n < 1) {
					return null;
				}
			} else if (line != null) {
				return null;
			}
			JsonObject clean = new JsonObject();
			clean.addProperty("path", w.get("path").getAsString());
			clean.add("content", content);
			if (J.isNum(line)) {
				clean.addProperty("line", J.integer(line));
			}
			out.add(clean);
		}
		return out;
	}

	/** Devuelve: 0 = ausente, >0 valido, -1 = invalido. */
	private static long maxBytes(JsonElement v) {
		if (v == null) {
			return 0;
		}
		Long n = J.integer(v);
		return n != null && n > 0 ? n : -1;
	}

	/** Peticion del puente con todos sus campos validados; null si algo no cumple la lista blanca. */
	public static JsonObject guardRequest(JsonObject o) {
		JsonElement opEl = o.get("op");
		if (!J.isStr(opEl) || !OPS.contains(opEl.getAsString())) {
			return null;
		}
		String op = opEl.getAsString();
		JsonObject r = new JsonObject();
		r.addProperty("op", op);
		switch (op) {
		case "listFiles":
		case "dbConnections":
			return r;
		case "readFile": {
			long max = maxBytes(o.get("maxBytes"));
			if (!str(o.get("fileId"), 4096) || !READ_AS.contains(J.str(o.get("as"))) || max < 0) {
				return null;
			}
			r.addProperty("fileId", o.get("fileId").getAsString());
			r.addProperty("as", o.get("as").getAsString());
			if (max > 0) {
				r.addProperty("maxBytes", max);
			}
			return r;
		}
		case "readMountFile": {
			long max = maxBytes(o.get("maxBytes"));
			if (!str(o.get("mountId"), 200) || !str(o.get("path"), CODE_MAX_PATH_LENGTH * 2) || !READ_AS.contains(J.str(o.get("as"))) || max <= 0) {
				return null;
			}
			r.addProperty("mountId", o.get("mountId").getAsString());
			r.addProperty("path", o.get("path").getAsString());
			r.addProperty("as", o.get("as").getAsString());
			r.addProperty("maxBytes", max);
			return r;
		}
		case "flow": {
			JsonElement kind = o.get("kind");
			JsonObject body = J.obj(o.get("body"));
			if (!J.isStr(kind) || !KINDS.contains(kind.getAsString()) || body == null) {
				return null;
			}
			JsonElement lang = body.get("language");
			JsonElement ctx = body.get("contextBase64");
			if (!J.isStr(lang) || !LANGUAGES.contains(lang.getAsString())) {
				return null;
			}
			if (!str(ctx, MAX_CONTEXT_BASE64) || !J.isBase64(ctx.getAsString())) {
				return null;
			}
			r.addProperty("kind", kind.getAsString());
			JsonObject b = new JsonObject();
			b.addProperty("language", lang.getAsString());
			b.addProperty("contextBase64", ctx.getAsString());
			r.add("body", b);
			return r;
		}
		case "copy":
			if (!str(o.get("text"), MAX_COPY_CHARS)) {
				return null;
			}
			r.addProperty("text", o.get("text").getAsString());
			return r;
		case "compareChange": {
			JsonObject body = J.obj(o.get("body"));
			JsonElement proposalId = o.get("proposalId");
			if (!J.isStr(proposalId) || !PROPOSAL_ID.matcher(proposalId.getAsString()).matches() || body == null) {
				return null;
			}
			JsonElement flowId = body.get("flowId");
			String lang = language(body.get("language"));
			String ctx = base64(body.get("contextBase64"), MAX_CONTEXT_BASE64);
			if (!str(flowId, 200) || flowId.getAsString().isEmpty() || lang == null || ctx == null) {
				return null;
			}
			r.addProperty("proposalId", proposalId.getAsString());
			JsonObject b = new JsonObject();
			b.addProperty("flowId", flowId.getAsString());
			b.addProperty("language", lang);
			b.addProperty("contextBase64", ctx);
			r.add("body", b);
			return r;
		}
		case "dbExplain": {
			JsonObject body = J.obj(o.get("body"));
			if (body == null) {
				return null;
			}
			String lang = language(body.get("language"));
			String ctx = base64(body.get("contextBase64"), MAX_DB_EXPLAIN_BASE64);
			if (lang == null || ctx == null) {
				return null;
			}
			JsonObject b = new JsonObject();
			b.addProperty("language", lang);
			b.addProperty("contextBase64", ctx);
			r.add("body", b);
			return r;
		}
		case "applyFiles": {
			JsonArray w = writes(o.get("writes"));
			if (w == null) {
				return null;
			}
			r.add("writes", w);
			return r;
		}
		case "confirm": {
			if (!str(o.get("title"), 200) || !str(o.get("message"), 1000) || !str(o.get("confirm"), 60)
				|| o.get("confirm").getAsString().trim().isEmpty()) {
				return null;
			}
			r.addProperty("title", o.get("title").getAsString());
			r.addProperty("message", o.get("message").getAsString());
			r.addProperty("confirm", o.get("confirm").getAsString());
			return r;
		}
		case "dbMetadata": {
			JsonElement key = o.get("key");
			if (!J.isStr(key) || !DB_KEY_PATTERN.matcher(key.getAsString()).matches()) {
				return null;
			}
			r.addProperty("key", key.getAsString());
			return r;
		}
		default:
			return null;
		}
	}

	/** Valida un mensaje de la webview. Tipos desconocidos o campos invalidos -> null (se ignora). */
	public static JsonObject guardMessage(JsonElement raw) {
		JsonObject o = J.obj(raw);
		if (o == null) {
			return null;
		}
		String type = J.str(o.get("type"));
		JsonObject m = new JsonObject();
		switch (type) {
		case "ready":
			m.addProperty("type", "ready");
			return m;
		case "request": {
			if (!str(o.get("id"), MAX_ID) || o.get("id").getAsString().isEmpty()) {
				return null;
			}
			JsonObject req = guardRequest(o);
			if (req == null) {
				return null;
			}
			m.addProperty("type", "request");
			m.addProperty("id", o.get("id").getAsString());
			m.add("req", req);
			return m;
		}
		case "cancel":
			if (!str(o.get("id"), MAX_ID) || o.get("id").getAsString().isEmpty()) {
				return null;
			}
			m.addProperty("type", "cancel");
			m.addProperty("id", o.get("id").getAsString());
			return m;
		case "openRef": {
			Double line = J.num(o.get("line"));
			if (!str(o.get("fileId"), 4096) || line == null) {
				return null;
			}
			m.addProperty("type", "openRef");
			m.addProperty("fileId", o.get("fileId").getAsString());
			m.addProperty("line", (long) Math.max(1, Math.floor(line)));
			if (o.has("sample") && !o.get("sample").isJsonNull()) {
				JsonObject s = J.obj(o.get("sample"));
				if (s == null || !str(s.get("path"), CODE_MAX_PATH_LENGTH * 2) || !str(s.get("content"), MAX_SAMPLE_CHARS)
					|| !str(s.get("language"), 40)) {
					return null;
				}
				JsonObject sample = new JsonObject();
				sample.addProperty("path", s.get("path").getAsString());
				sample.addProperty("content", s.get("content").getAsString());
				sample.addProperty("language", s.get("language").getAsString());
				m.add("sample", sample);
			}
			return m;
		}
		case "notify": {
			String level = J.str(o.get("level"));
			if (!LEVELS.contains(level) || !J.isStr(o.get("text"))) {
				return null;
			}
			m.addProperty("type", "notify");
			m.addProperty("level", level);
			m.addProperty("text", J.truncate(o.get("text").getAsString(), MAX_NOTIFY_CHARS));
			return m;
		}
		case "ask":
			if (!str(o.get("text"), MAX_ASK_CHARS) || o.get("text").getAsString().trim().isEmpty()) {
				return null;
			}
			m.addProperty("type", "ask");
			m.addProperty("text", o.get("text").getAsString());
			return m;
		case "saveDbMap": {
			String flowId = J.str(o.get("flowId"));
			JsonObject dbMap = J.obj(o.get("dbMap"));
			if (!FLOW_ID.matcher(flowId).matches() || dbMap == null) {
				return null;
			}
			if (J.GSON.toJson(dbMap).length() > MAX_DB_MAP_CHARS) {
				return null;
			}
			m.addProperty("type", "saveDbMap");
			m.addProperty("flowId", flowId);
			m.add("dbMap", dbMap);
			return m;
		}
		default:
			return null;
		}
	}
}
