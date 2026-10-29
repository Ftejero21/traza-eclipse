package com.ftejero21.traza.ai;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ftejero21.traza.core.J;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Port de ai/semantics.ts: validacion semantica y textos de usuario. Funciones puras sobre JSON ya parseado. Nunca
 * registran contenido.
 */
public final class Semantics {
	private static final int MAX_ERRORS = 20;
	private static final int MAX_CHANGE_CHARS = 20000;
	private static final int MAX_PATH_CHARS = 400;
	private static final int MAX_HUNKS = 40;
	private static final int MAX_HUNK_LINES = 400;
	private static final int MAX_LINE_TEXT = 2000;
	private static final int DB_MAX_VALUES = 16;

	private Semantics() {
	}

	public static boolean isObj(JsonElement v) {
		return v != null && v.isJsonObject();
	}

	/** JsonNode.path(key): valor de la clave o null. */
	static JsonElement at(JsonElement value, String key) {
		return isObj(value) ? value.getAsJsonObject().get(key) : null;
	}

	static JsonArray list(JsonElement value) {
		return J.arr(value);
	}

	/** asText(""): texto, numero o booleano como texto; lo demas, vacio. */
	public static String txt(JsonElement v) {
		if (v == null || !v.isJsonPrimitive()) {
			return "";
		}
		JsonPrimitive p = v.getAsJsonPrimitive();
		if (p.isString()) {
			return p.getAsString();
		}
		if (p.isNumber()) {
			double d = p.getAsDouble();
			if (d == Math.rint(d) && Math.abs(d) < 1e15) {
				return Long.toString((long) d);
			}
			return p.getAsString();
		}
		return p.getAsString();
	}

	public static String cut(String value, int max) {
		return value.length() <= max ? value : value.substring(0, Math.max(0, max));
	}

	private static List<String> limit(List<String> errors) {
		return errors.size() > MAX_ERRORS ? new ArrayList<>(errors.subList(0, MAX_ERRORS)) : errors;
	}

	private static Set<String> stepIds(JsonElement flow) {
		Set<String> steps = new HashSet<>();
		for (JsonElement step : list(at(flow, "steps"))) {
			steps.add(txt(at(step, "id")));
		}
		return steps;
	}

	/* ------------------------------- parseo ------------------------------- */

	/** Excepcion con un codigo corto (equivale a los `new Error(...)` de parseModelJson). */
	public static final class ModelJsonException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ModelJsonException(String message) {
			super(message);
		}
	}

	/** Parseo estricto (como JSON.parse). */
	public static JsonElement parseStrict(String text) {
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.setStrictness(Strictness.STRICT);
			JsonElement e = J.GSON.getAdapter(JsonElement.class).read(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new com.google.gson.JsonSyntaxException("Texto adicional tras el JSON");
			}
			return e;
		} catch (com.google.gson.JsonSyntaxException e) {
			throw e;
		} catch (Exception e) {
			throw new com.google.gson.JsonSyntaxException(String.valueOf(e.getMessage()));
		}
	}

	private static final Pattern COLUMN = Pattern.compile("column (\\d+)");

	/** Port de parseJson: quita la valla ``` y toma del primer `{` al ultimo `}`. */
	public static JsonObject parseModelJson(String content) {
		if (content == null || content.trim().isEmpty()) {
			throw new ModelJsonException("contenido vacio");
		}
		String s = content.trim();
		if (s.startsWith("```")) {
			int nl = s.indexOf('\n');
			s = nl < 0 ? "" : s.substring(nl + 1);
			if (s.endsWith("```")) {
				s = s.substring(0, s.length() - 3);
			}
		}
		int start = s.indexOf('{');
		int end = s.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new ModelJsonException("sin objeto JSON");
		}
		String raw = s.substring(start, end + 1);
		JsonElement node;
		try {
			node = parseStrict(raw);
		} catch (RuntimeException first) {
			Repaired repaired = repairJson(raw);
			try {
				node = parseStrict(repaired.text);
			} catch (RuntimeException second) {
				if (repaired.truncated) {
					throw new ModelJsonException(
						"JSON cortado: la respuesta es demasiado larga; devuelve una version mas breve (explicaciones de una frase, sin repetir codigo)");
				}
				Matcher m = COLUMN.matcher(String.valueOf(second.getMessage()));
				throw new ModelJsonException(m.find() ? "JSON invalido cerca de la posicion " + m.group(1) : "JSON invalido");
			}
		}
		if (!isObj(node)) {
			throw new ModelJsonException("no es un objeto");
		}
		return node.getAsJsonObject();
	}

	static final class Repaired {
		final String text;
		final boolean truncated;

		Repaired(String text, boolean truncated) {
			this.text = text;
			this.truncated = truncated;
		}
	}

	/**
	 * Errores habituales de los modelos al escribir JSON: caracteres de control sin escapar dentro de cadenas, escapes no
	 * validos, comas antes de } o ] y respuestas cortadas (se cierran cadenas, objetos y arrays pendientes).
	 */
	public static Repaired repairJson(String text) {
		StringBuilder out = new StringBuilder();
		boolean inString = false;
		boolean escaped = false;
		List<Character> open = new ArrayList<>();
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (inString) {
				if (escaped) {
					escaped = false;
					out.append(ch);
				} else if (ch == '\\') {
					boolean valid = i + 1 >= text.length() || "\"\\/bfnrtu".indexOf(text.charAt(i + 1)) >= 0;
					if (valid) {
						escaped = true;
					} else {
						out.append('\\');
					}
					out.append(ch);
				} else if (ch == '"') {
					inString = false;
					out.append(ch);
				} else if (ch == '\n') {
					out.append("\\n");
				} else if (ch == '\r') {
					out.append("\\r");
				} else if (ch == '\t') {
					out.append("\\t");
				} else if (ch < ' ') {
					out.append(String.format("\\u%04x", (int) ch));
				} else {
					out.append(ch);
				}
				continue;
			}
			if (ch == '"') {
				inString = true;
			}
			if (ch == ',') {
				int j = i + 1;
				while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
					j++;
				}
				if (j < text.length() && (text.charAt(j) == '}' || text.charAt(j) == ']')) {
					continue;
				}
			}
			if (ch == '{' || ch == '[') {
				open.add(ch == '{' ? '}' : ']');
			} else if (ch == '}' || ch == ']') {
				if (open.isEmpty() || open.get(open.size() - 1) != ch) {
					continue;
				}
				open.remove(open.size() - 1);
			}
			out.append(ch);
		}
		boolean truncated = inString || !open.isEmpty();
		if (inString) {
			out.append('"');
		}
		if (!open.isEmpty()) {
			int len = out.length();
			while (len > 0 && (Character.isWhitespace(out.charAt(len - 1)) || out.charAt(len - 1) == ',' || out.charAt(len - 1) == ':')) {
				len--;
			}
			out.setLength(len);
			// Una clave sin valor al final dentro de un objeto se descarta.
			if (open.get(open.size() - 1) == '}') {
				dropTrailingKey(out);
				int l2 = out.length();
				int k = l2;
				while (k > 0 && Character.isWhitespace(out.charAt(k - 1))) {
					k--;
				}
				if (k > 0 && out.charAt(k - 1) == ',') {
					out.setLength(k - 1);
				}
			}
			for (int i = open.size() - 1; i >= 0; i--) {
				out.append(open.get(i));
			}
		}
		return new Repaired(out.toString(), truncated);
	}

	/** Equivale a replace(/([{,])\s*"(?:[^"\\]|\\.)*"$/, '$1'). */
	private static void dropTrailingKey(StringBuilder out) {
		int n = out.length();
		if (n == 0 || out.charAt(n - 1) != '"') {
			return;
		}
		int i = n - 2;
		int quote = -1;
		while (i >= 0) {
			if (out.charAt(i) == '"') {
				int bs = 0;
				int j = i - 1;
				while (j >= 0 && out.charAt(j) == '\\') {
					bs++;
					j--;
				}
				if (bs % 2 == 0) {
					quote = i;
					break;
				}
			}
			i--;
		}
		if (quote < 0) {
			return;
		}
		int k = quote - 1;
		while (k >= 0 && Character.isWhitespace(out.charAt(k))) {
			k--;
		}
		if (k >= 0 && (out.charAt(k) == '{' || out.charAt(k) == ',')) {
			out.setLength(k + 1);
		}
	}

	/* ------------------------------- textos ------------------------------- */

	/** Contenido con cada linea precedida de su numero (`N| texto`). */
	public static String numbered(String content, Integer startLine) {
		if (content == null) {
			return "";
		}
		int n = startLine == null || startLine < 1 ? 1 : startLine;
		String[] lines = content.split("\r?\n", -1);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				sb.append('\n');
			}
			sb.append(n + i).append("| ").append(lines[i]);
		}
		return sb.toString();
	}

	/** Corta el CSS a max caracteres en el ultimo '}' (o en max si no hay ninguno). */
	public static String cutCss(String css, int max) {
		if (css == null) {
			return "";
		}
		if (css.length() <= max) {
			return css;
		}
		int end = css.lastIndexOf('}', max - 1) + 1;
		return css.substring(0, end > 0 ? end : max);
	}

	/** Diff unificado compacto del cambio propuesto. */
	public static String changeDiff(JsonElement change) {
		if (!isObj(change)) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		JsonObject c = change.getAsJsonObject();
		String title = txt(c.get("title")).trim();
		String summary = txt(c.get("summary")).trim();
		if (!title.isEmpty()) {
			sb.append(title).append('\n');
		}
		if (!summary.isEmpty()) {
			sb.append(summary).append('\n');
		}
		for (JsonElement file : list(c.get("files"))) {
			String path = txt(at(file, "path"));
			for (JsonElement hunk : list(at(file, "hunks"))) {
				JsonElement oldStart = at(hunk, "oldStart");
				long os = J.isNum(oldStart) ? (long) oldStart.getAsDouble() : 0;
				sb.append("@@ ").append(path).append(':').append(os).append('\n');
				for (JsonElement line : list(at(hunk, "lines"))) {
					String kind = txt(at(line, "kind"));
					String prefix = kind.equals("add") ? "+" : kind.equals("del") ? "-" : " ";
					sb.append(prefix).append(txt(at(line, "text"))).append('\n');
				}
				if (sb.length() > MAX_CHANGE_CHARS) {
					return cut(sb.toString(), MAX_CHANGE_CHARS);
				}
			}
		}
		return cut(sb.toString(), MAX_CHANGE_CHARS);
	}

	/* --------------------------- SIMULATE / LOGS --------------------------- */

	/** En LOGS se quitan las evidencias con index fuera del numero de lineas de los logs. */
	public static void repairDiagnosis(JsonObject result, int logLines, boolean simulate) {
		if (simulate) {
			return;
		}
		JsonElement evidence = result.get("evidence");
		if (!J.isArr(evidence)) {
			return;
		}
		JsonArray kept = new JsonArray();
		for (JsonElement e : evidence.getAsJsonArray()) {
			JsonElement index = at(e, "index");
			long n = J.isNum(index) ? (long) index.getAsDouble() : -1;
			if (n < logLines) {
				kept.add(e);
			}
		}
		result.add("evidence", kept);
	}

	/* --------------------------------- FLOW --------------------------------- */

	/** Ids de carriles y pasos unicos y no vacios; from/to de los pasos deben ser carriles. */
	public static List<String> flowSemantics(JsonElement flow) {
		List<String> errors = new ArrayList<>();
		Set<String> lanes = new HashSet<>();
		JsonArray la = list(at(flow, "lanes"));
		for (int i = 0; i < la.size(); i++) {
			String id = txt(at(la.get(i), "id"));
			if (id.isEmpty() || lanes.contains(id)) {
				errors.add("$.lanes[" + i + "].id: id vacio o duplicado");
			} else {
				lanes.add(id);
			}
		}
		Set<String> steps = new HashSet<>();
		JsonArray sa = list(at(flow, "steps"));
		for (int i = 0; i < sa.size(); i++) {
			JsonElement step = sa.get(i);
			String id = txt(at(step, "id"));
			if (id.isEmpty() || steps.contains(id)) {
				errors.add("$.steps[" + i + "].id: id vacio o duplicado");
			} else {
				steps.add(id);
			}
			if (!lanes.contains(txt(at(step, "from")))) {
				errors.add("$.steps[" + i + "].from: no es un id de lanes");
			}
			if (!lanes.contains(txt(at(step, "to")))) {
				errors.add("$.steps[" + i + "].to: no es un id de lanes");
			}
		}
		return limit(errors);
	}

	/** "scenario" es opcional: si no cumple el esquema FlowScenario, se quita en silencio. */
	public static void dropInvalidScenario(JsonObject root) {
		if (!root.has("scenario")) {
			return;
		}
		List<String> errors = SchemaValidator.validate("FlowScenario", root.get("scenario"));
		if (!errors.isEmpty()) {
			root.remove("scenario");
			com.ftejero21.traza.TrazaPlugin.warn("Escenario del flujo descartado (" + errors.size() + " errores).");
		}
	}

	/* -------------------------------- PROPOSE -------------------------------- */

	private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:");
	private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f-\\u009f]");

	/** Port de normalizePath de tejechat: ruta relativa valida o null. */
	public static String normalizeProposalPath(String raw) {
		if (raw == null) {
			return null;
		}
		String p = raw.trim().replace('\\', '/');
		while (p.startsWith("./")) {
			p = p.substring(2);
		}
		if (p.isEmpty() || p.length() > MAX_PATH_CHARS || p.startsWith("/") || DRIVE.matcher(p).find()) {
			return null;
		}
		if (CONTROL.matcher(p).find()) {
			return null;
		}
		for (String segment : p.split("/", -1)) {
			if (segment.equals("..")) {
				return null;
			}
		}
		if (p.endsWith("/")) {
			return null;
		}
		return p;
	}

	/** Reglas de ChangeProposal que el esquema no expresa. En un archivo `added` se fija oldStart = 0. */
	public static List<String> proposeSemantics(JsonElement change, Set<String> mountIds) {
		List<String> errors = new ArrayList<>();
		Set<String> pairs = new HashSet<>();
		JsonArray files = list(at(change, "files"));
		for (int i = 0; i < files.size(); i++) {
			JsonElement file = files.get(i);
			String base = "$.files[" + i + "]";
			String path = normalizeProposalPath(txt(at(file, "path")));
			if (path == null) {
				errors.add(base + ".path: ruta no valida (relativa, sin '..' ni ruta absoluta)");
			}
			String mountId = txt(at(file, "mountId")).trim();
			if (!mountId.isEmpty() && !mountIds.contains(mountId)) {
				errors.add(base + ".mountId: no es un id de MONTAJES ni \"\"");
			}
			if (path != null) {
				String key = mountId + "\u0000" + path;
				if (pairs.contains(key)) {
					errors.add(base + ": archivo repetido (mismo mountId y path)");
				}
				pairs.add(key);
			}
			String status = txt(at(file, "status"));
			boolean changes = false;
			JsonArray hunks = list(at(file, "hunks"));
			if (hunks.size() > MAX_HUNKS) {
				errors.add(base + ".hunks: como mucho " + MAX_HUNKS + " bloques");
			}
			for (int j = 0; j < hunks.size(); j++) {
				JsonElement hunk = hunks.get(j);
				if (status.equals("added") && isObj(hunk)) {
					hunk.getAsJsonObject().addProperty("oldStart", 0);
				}
				JsonArray lines = list(at(hunk, "lines"));
				if (lines.size() < 1 || lines.size() > MAX_HUNK_LINES) {
					errors.add(base + ".hunks[" + j + "].lines: entre 1 y " + MAX_HUNK_LINES + " lineas");
				}
				for (int k = 0; k < lines.size(); k++) {
					JsonElement text = at(lines.get(k), "text");
					if (J.isStr(text)) {
						String t = text.getAsString();
						if (t.length() > MAX_LINE_TEXT || t.contains("\n") || t.contains("\r")) {
							errors.add(base + ".hunks[" + j + "].lines[" + k + "].text: una sola linea de como mucho " + MAX_LINE_TEXT + " caracteres");
						}
					}
				}
				for (JsonElement line : lines) {
					String kind = txt(at(line, "kind"));
					if (!kind.equals("context")) {
						changes = true;
					}
					if (status.equals("added") && !kind.equals("add")) {
						errors.add(base + ".hunks[" + j + "]: un archivo nuevo solo admite lineas \"add\"");
						break;
					}
					if (status.equals("deleted") && !kind.equals("del")) {
						errors.add(base + ".hunks[" + j + "]: un archivo eliminado solo admite lineas \"del\"");
						break;
					}
				}
			}
			if (status.equals("modified") && !changes) {
				errors.add(base + ": un archivo modificado necesita al menos una linea \"add\" o \"del\"");
			}
		}
		return limit(errors);
	}

	/* -------------------------------- DB_MAP -------------------------------- */

	private static String defang(String text) {
		return text.replace('<', '‹');
	}

	private static void checkNoMarkup(JsonElement value, String path, List<String> errors) {
		if (J.isStr(value) && value.getAsString().contains("<")) {
			errors.add(path + ": no puede contener '<'");
		}
	}

	/** Repara un FlowDbMap ya valido contra el esquema. */
	public static void repairDbMap(JsonObject map, JsonElement flow) {
		if (J.isStr(map.get("summary"))) {
			map.addProperty("summary", defang(map.get("summary").getAsString()));
		}
		Set<String> steps = stepIds(flow);
		Set<String> tables = new HashSet<>();
		for (JsonElement table : list(map.get("tables"))) {
			tables.add(txt(at(table, "name")));
		}
		JsonArray relations = new JsonArray();
		for (JsonElement rel : list(map.get("relations"))) {
			if (tables.contains(txt(at(rel, "from"))) && tables.contains(txt(at(rel, "to")))) {
				relations.add(rel);
			}
		}
		map.add("relations", relations);
		Set<String> seen = new HashSet<>();
		JsonArray kept = new JsonArray();
		for (JsonElement nodeEl : list(map.get("accesses"))) {
			String stepId = txt(at(nodeEl, "stepId"));
			if (!isObj(nodeEl) || !steps.contains(stepId) || !tables.contains(txt(nodeEl.getAsJsonObject().get("table"))) || seen.contains(stepId)) {
				continue;
			}
			seen.add(stepId);
			JsonObject a = nodeEl.getAsJsonObject().deepCopy();
			if (J.isStr(a.get("note"))) {
				a.addProperty("note", defang(a.get("note").getAsString()));
			}
			if (J.isStr(a.get("fn"))) {
				a.addProperty("fn", defang(a.get("fn").getAsString()));
			}
			if (J.isStr(a.get("via")) && !tables.contains(a.get("via").getAsString())) {
				a.add("via", JsonNull.INSTANCE);
			}
			JsonElement values = a.get("values");
			if (isObj(values)) {
				JsonObject clean = new JsonObject();
				for (Map.Entry<String, JsonElement> e : values.getAsJsonObject().entrySet()) {
					if (clean.size() >= DB_MAX_VALUES || e.getValue().isJsonNull()) {
						continue;
					}
					String v = J.isStr(e.getValue()) ? e.getValue().getAsString() : J.GSON.toJson(e.getValue());
					clean.addProperty(e.getKey(), defang(v.length() > 40 ? v.substring(0, 39) + "…" : v));
				}
				a.add("values", clean);
			}
			kept.add(a);
		}
		map.add("accesses", kept);
	}

	/** Reglas del FlowDbMap que el esquema no expresa. */
	public static List<String> dbMapErrors(JsonElement map, JsonElement flow) {
		List<String> errors = new ArrayList<>();
		if (!isObj(map)) {
			errors.add("$: debe ser un objeto");
			return errors;
		}
		JsonObject m = map.getAsJsonObject();
		Set<String> steps = stepIds(flow);
		Set<String> tables = new HashSet<>();
		for (JsonElement table : list(m.get("tables"))) {
			tables.add(txt(at(table, "name")));
		}
		checkNoMarkup(m.get("summary"), "$.summary", errors);
		JsonArray rels = list(m.get("relations"));
		for (int i = 0; i < rels.size(); i++) {
			if (!tables.contains(txt(at(rels.get(i), "from")))) {
				errors.add("$.relations[" + i + "].from: no es una tabla de tables");
			}
			if (!tables.contains(txt(at(rels.get(i), "to")))) {
				errors.add("$.relations[" + i + "].to: no es una tabla de tables");
			}
		}
		Set<String> seen = new HashSet<>();
		JsonArray accesses = list(m.get("accesses"));
		for (int i = 0; i < accesses.size(); i++) {
			JsonElement a = accesses.get(i);
			String path = "$.accesses[" + i + "]";
			String stepId = txt(at(a, "stepId"));
			if (!steps.contains(stepId)) {
				errors.add(path + ".stepId: no es un id de steps del FLUJO");
			} else if (seen.contains(stepId)) {
				errors.add(path + ".stepId: ya hay un acceso para ese paso");
			} else {
				seen.add(stepId);
			}
			if (!tables.contains(txt(at(a, "table")))) {
				errors.add(path + ".table: no es una tabla de tables");
			}
			JsonElement via = at(a, "via");
			if (J.isStr(via) && !tables.contains(via.getAsString())) {
				errors.add(path + ".via: no es una tabla de tables ni null");
			}
			checkNoMarkup(at(a, "note"), path + ".note", errors);
			checkNoMarkup(at(a, "fn"), path + ".fn", errors);
			JsonElement values = at(a, "values");
			if (isObj(values)) {
				JsonObject vo = values.getAsJsonObject();
				if (vo.size() > DB_MAX_VALUES) {
					errors.add(path + ".values: mas de " + DB_MAX_VALUES + " claves");
				}
				for (Map.Entry<String, JsonElement> e : vo.entrySet()) {
					String key = path + ".values." + cut(e.getKey(), 64);
					JsonElement v = e.getValue();
					if (!J.isStr(v) || v.getAsString().length() > 40) {
						errors.add(key + ": debe ser un texto de como mucho 40 caracteres");
					} else {
						checkNoMarkup(v, key, errors);
					}
				}
			}
		}
		return limit(errors);
	}

	private static final Pattern DB_NAME_EXPRESSION = Pattern.compile("[+\"'`\\s()]|\\$\\{|#\\{");
	private static final Pattern DB_NAME_CLASS_CONST = Pattern.compile("(^|\\.)[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*\\.[A-Z][A-Z0-9_]*$");
	private static final Pattern DB_NAME_CONST_PREFIX = Pattern.compile("^(TABLA|TABLE|TABLE_NAME|COL|COLUMN|COLUMNA|CAMPO|FIELD|SQL|QUERY)_[A-Z0-9_]+$");

	private static boolean unresolvedDbName(String name) {
		String value = name.trim();
		return DB_NAME_EXPRESSION.matcher(value).find() || value.startsWith("this.") || DB_NAME_CLASS_CONST.matcher(value).find()
			|| DB_NAME_CONST_PREFIX.matcher(value).find();
	}

	/** Solo DB_MAP_SQL: errores por cada nombre que parezca una constante o expresion sin resolver. */
	public static List<String> dbMapUnresolvedNameErrors(JsonElement map) {
		List<String> errors = new ArrayList<>();
		if (!isObj(map)) {
			return errors;
		}
		JsonObject m = map.getAsJsonObject();
		java.util.function.BiConsumer<JsonElement, String> check = (value, path) -> {
			if (!J.isStr(value) || !unresolvedDbName(value.getAsString())) {
				return;
			}
			errors.add(path + ": «" + cut(value.getAsString().trim(), 64)
				+ "» parece una constante, variable o expresión sin resolver; busca su definición (misma clase, superclase, interfaz, clase de constantes, .properties/.yml o XML) y usa el VALOR real como nombre de tabla/columna");
		};
		JsonArray tables = list(m.get("tables"));
		for (int i = 0; i < tables.size(); i++) {
			check.accept(at(tables.get(i), "name"), "$.tables[" + i + "].name");
			JsonArray cols = list(at(tables.get(i), "columns"));
			for (int j = 0; j < cols.size(); j++) {
				check.accept(at(cols.get(j), "name"), "$.tables[" + i + "].columns[" + j + "].name");
			}
		}
		JsonArray rels = list(m.get("relations"));
		for (int i = 0; i < rels.size(); i++) {
			for (String key : new String[] { "from", "fromColumn", "to", "toColumn" }) {
				check.accept(at(rels.get(i), key), "$.relations[" + i + "]." + key);
			}
		}
		JsonArray accesses = list(m.get("accesses"));
		for (int i = 0; i < accesses.size(); i++) {
			check.accept(at(accesses.get(i), "table"), "$.accesses[" + i + "].table");
			JsonArray cols = list(at(accesses.get(i), "columns"));
			for (int j = 0; j < cols.size(); j++) {
				check.accept(cols.get(j), "$.accesses[" + i + "].columns[" + j + "]");
			}
			check.accept(at(accesses.get(i), "via"), "$.accesses[" + i + "].via");
		}
		return limit(errors);
	}

	/* -------------------------------- UI_PLAN -------------------------------- */

	private static void uiPlanBodyErrors(JsonElement body, String path, List<String> errors) {
		JsonElement mark = at(body, "mark");
		JsonElement inject = at(body, "inject");
		checkNoMarkup(at(body, "root"), path + ".root", errors);
		checkNoMarkup(at(mark, "selector"), path + ".mark.selector", errors);
		checkNoMarkup(at(mark, "label"), path + ".mark.label", errors);
		checkNoMarkup(at(inject, "after"), path + ".inject.after", errors);
		checkNoMarkup(at(inject, "component"), path + ".inject.component", errors);
		checkNoMarkup(at(body, "caption"), path + ".caption", errors);
		JsonElement bindings = at(body, "bindings");
		if (isObj(bindings)) {
			JsonObject b = bindings.getAsJsonObject();
			if (b.size() > 60) {
				errors.add(path + ".bindings: mas de 60 claves");
			}
			for (String key : b.keySet()) {
				if (key.length() > 120) {
					errors.add(path + ".bindings: clave mayor de 120 caracteres");
				}
			}
			if (J.GSON.toJson(b).length() > 4000) {
				errors.add(path + ".bindings: mayor de 4000 caracteres");
			}
		}
		JsonElement inputs = at(inject, "inputs");
		if (isObj(inputs) && inputs.getAsJsonObject().size() > 20) {
			errors.add(path + ".inject.inputs: mas de 20 claves");
		}
	}

	/** Neutraliza '<' en caption y mark.label del plan, en la raiz y en `after`. */
	public static void repairUiPlan(JsonObject plan) {
		fixPlan(plan);
		fixPlan(plan.get("after"));
	}

	private static void fixPlan(JsonElement bodyEl) {
		if (!isObj(bodyEl)) {
			return;
		}
		JsonObject body = bodyEl.getAsJsonObject();
		if (J.isStr(body.get("caption"))) {
			body.addProperty("caption", defang(body.get("caption").getAsString()));
		}
		JsonElement mark = body.get("mark");
		if (isObj(mark) && J.isStr(mark.getAsJsonObject().get("label"))) {
			mark.getAsJsonObject().addProperty("label", defang(mark.getAsJsonObject().get("label").getAsString()));
		}
	}

	/** Maqueta de pantalla (UI_MOCK): css ausente = '' y recorte de espacios en html, css y caption (tambien en `after`). */
	public static void repairUiMock(JsonObject root) {
		fixMock(root);
		JsonElement afterEl = root.get("after");
		if (isObj(afterEl)) {
			JsonObject after = afterEl.getAsJsonObject();
			boolean hadCss = J.isStr(after.get("css"));
			fixMock(after);
			if (!hadCss) {
				after.remove("css");
			}
		}
	}

	private static void fixMock(JsonObject body) {
		if (!J.isStr(body.get("css"))) {
			body.addProperty("css", "");
		}
		for (String key : new String[] { "html", "css", "caption" }) {
			JsonElement value = body.get(key);
			if (J.isStr(value)) {
				body.addProperty(key, value.getAsString().trim());
			}
		}
	}

	/** Reglas de FORMATO de la maqueta que el esquema no expresa. */
	public static List<String> uiMockErrors(JsonElement root) {
		List<String> errors = new ArrayList<>();
		if (!isObj(root)) {
			errors.add("$: debe ser un objeto");
			return errors;
		}
		JsonObject r = root.getAsJsonObject();
		checkMockHtml(r, "$", errors);
		JsonElement after = r.get("after");
		if (isObj(after)) {
			checkMockHtml(after.getAsJsonObject(), "$.after", errors);
		}
		return limit(errors);
	}

	private static void checkMockHtml(JsonObject body, String path, List<String> errors) {
		JsonElement html = body.get("html");
		if (!J.isStr(html) || !html.getAsString().trim().contains("<")) {
			errors.add(path + ".html: debe ser HTML con etiquetas");
		}
	}

	/** Reglas del UiPreviewPlan que el esquema no expresa. */
	public static List<String> uiPlanErrors(JsonElement plan) {
		List<String> errors = new ArrayList<>();
		if (!isObj(plan)) {
			errors.add("$: debe ser un objeto");
			return errors;
		}
		uiPlanBodyErrors(plan, "$", errors);
		JsonElement after = plan.getAsJsonObject().get("after");
		if (!J.isNull(after)) {
			if (!isObj(after)) {
				errors.add("$.after: debe ser un objeto");
			} else {
				if (after.getAsJsonObject().has("after")) {
					errors.add("$.after.after: no permitido");
				}
				uiPlanBodyErrors(after, "$.after", errors);
			}
		}
		return limit(errors);
	}

	/** Mapa ordenado auxiliar. */
	static <K, V> Map<K, V> linked() {
		return new LinkedHashMap<>();
	}
}
