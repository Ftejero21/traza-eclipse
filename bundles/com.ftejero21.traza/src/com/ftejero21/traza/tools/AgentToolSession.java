package com.ftejero21.traza.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.CancelToken;
import com.ftejero21.traza.ai.FlowContextCollector;
import com.ftejero21.traza.ai.Semantics;
import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.SecretMasker;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.core.WorkspaceFiles.ProjectFile;
import com.ftejero21.traza.core.WorkspaceFiles.Snapshot;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Port de host-agent-tools.ts: herramientas de solo lectura (traza_search, traza_read, traza_list) sobre todas las
 * carpetas abiertas. Respetan las exclusiones del contexto, los archivos sensibles y las claves privadas, y enmascaran
 * los secretos. Nunca escriben ni registran rutas o contenido en el log.
 */
public final class AgentToolSession {
	private static final int SEARCH_FILE_BYTES = 512 * 1024;
	private static final int READ_FILE_BYTES = 3 * 1024 * 1024;
	private static final long SEARCH_TIME_MS = 8000;
	private static final int SEARCH_DEFAULT = 40;
	private static final int SEARCH_MAX = 80;
	private static final int MATCHES_PER_FILE = 4;
	private static final int LINE_CHARS = 240;
	private static final int READ_DEFAULT_LINES = 200;
	private static final int READ_WHOLE_LINES = 300;
	private static final int READ_MAX_LINES = 400;
	private static final int READ_MAX_CHARS = 20_000;
	private static final int LIST_MAX = 200;
	private static final long CACHE_MAX_CHARS = 40_000_000L;
	private static final int SCAN_CONCURRENCY = 8;
	private static final int QUERY_MAX = 200;
	private static final int REGEX_LINE_CHARS = 2000;
	private static final int OVERVIEW_DIRS = 20;
	private static final int OVERVIEW_DEPTH = 3;
	private static final int OVERVIEW_MAX_CHARS = 6000;
	private static final int PROGRESS_MAX = 80;
	private static final int AMBIGUOUS_OPTIONS = 5;
	private static final Pattern SYMBOL_NAME = Pattern.compile("^[\\w$]{1,80}$");
	private static final boolean WIN32 = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	private final Map<String, ProjectFile> byPath = new LinkedHashMap<>();
	private final Map<String, List<String>> byLower = new LinkedHashMap<>();
	private final Map<String, String> byUri = new HashMap<>();
	private List<String> mounts = new ArrayList<>();
	private boolean multi;
	private final Map<String, String> contents = new HashMap<>();
	private final Set<String> contentMiss = new HashSet<>();
	private final Map<String, Integer> failedAt = new HashMap<>();
	private final Set<String> denied = new HashSet<>();
	private long cachedChars;
	private final Set<String> readPaths = new HashSet<>();
	private int callsSearch;
	private int callsRead;
	private int callsList;
	private long returned;
	private final CancelToken token;
	private final String lang;
	private final java.util.function.Consumer<String> progress;

	public AgentToolSession(CancelToken token, String lang, java.util.function.Consumer<String> progress) {
		this.token = token == null ? CancelToken.NONE : token;
		this.lang = lang;
		this.progress = progress;
	}

	/* ------------------------------ ciclo de vida ------------------------------ */

	public synchronized void init() {
		Snapshot snapshot = WorkspaceFiles.listFiles();
		token.check();
		if (snapshot.mounts.isEmpty()) {
			throw new IllegalStateException("No hay carpetas abiertas en el workspace.");
		}
		mounts = new ArrayList<>();
		snapshot.mounts.forEach(m -> mounts.add(m.id));
		multi = mounts.size() > 1;
		for (ProjectFile file : snapshot.files) {
			if (!FlowContextCollector.allowed(file)) {
				continue;
			}
			byPath.put(file.path, file);
			byLower.computeIfAbsent(file.path.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(file.path);
			byUri.put(WIN32 ? file.id.toLowerCase(Locale.ROOT) : file.id, file.path);
		}
	}

	private void say(String es, String en) {
		if (progress != null) {
			progress.accept("en".equals(lang) ? en : es);
		}
	}

	private String mountOf(String path) {
		if (!multi) {
			return mounts.isEmpty() ? "" : mounts.get(0);
		}
		int index = path.indexOf('/');
		return index < 0 ? path : path.substring(0, index);
	}

	private String relOf(String path) {
		if (!multi) {
			return path;
		}
		int index = path.indexOf('/');
		return index < 0 ? path : path.substring(index + 1);
	}

	public synchronized void markRead(String path) {
		readPaths.add(path);
	}

	/** Ruta del mapa de un archivo del workspace por su identificador (URI); null si no esta entre los permitidos. */
	public synchronized String pathOfUri(String id) {
		return byUri.get(WIN32 ? id.toLowerCase(Locale.ROOT) : id);
	}

	/** Ruta del mapa de mountId + file (relativo a la carpeta); null si no existe entre los archivos permitidos. */
	public synchronized String pathFor(String mountId, String file) {
		String rel = Guards.normalizeCodePath(file.trim().replace('\\', '/'));
		if (rel == null || rel.isEmpty()) {
			return null;
		}
		String mount = mountId.trim();
		List<String> candidates = multi ? List.of(mount + "/" + rel, rel) : List.of(rel, mount + "/" + rel);
		for (String c : candidates) {
			if (byPath.containsKey(c)) {
				return c;
			}
		}
		for (String c : candidates) {
			List<String> same = byLower.get(c.toLowerCase(Locale.ROOT));
			if (same != null && same.size() == 1) {
				return same.get(0);
			}
		}
		return null;
	}

	/* ------------------------------ resumen ------------------------------ */

	private static int compareLocale(String a, String b) {
		int c = a.compareToIgnoreCase(b);
		return c != 0 ? c : a.compareTo(b);
	}

	public synchronized String overview() {
		List<String> lines = new ArrayList<>();
		for (String mount : mounts) {
			Map<String, Integer> dirs = new LinkedHashMap<>();
			int total = 0;
			for (ProjectFile file : byPath.values()) {
				if (!mountOf(file.path).equals(mount)) {
					continue;
				}
				total++;
				String[] segments = relOf(file.path).split("/");
				int len = Math.max(0, segments.length - 1);
				String dir = String.join("/", java.util.Arrays.asList(segments).subList(0, Math.min(len, OVERVIEW_DEPTH)));
				if (dir.isEmpty()) {
					dir = "(raiz)";
				}
				dirs.merge(dir, 1, Integer::sum);
			}
			List<Map.Entry<String, Integer>> top = new ArrayList<>(dirs.entrySet());
			top.sort((a, b) -> !a.getValue().equals(b.getValue()) ? b.getValue() - a.getValue() : compareLocale(a.getKey(), b.getKey()));
			List<String> parts = new ArrayList<>();
			for (Map.Entry<String, Integer> e : top.subList(0, Math.min(OVERVIEW_DIRS, top.size()))) {
				parts.add(e.getKey() + " (" + e.getValue() + ")");
			}
			lines.add("- " + mount + " (" + total + " archivos): " + String.join(", ", parts));
		}
		return Semantics.cut(String.join("\n", lines), OVERVIEW_MAX_CHARS);
	}

	/** Solo contadores (nunca rutas ni contenido de archivos). */
	public synchronized String summary() {
		Map<String, Integer> perMount = new LinkedHashMap<>();
		mounts.forEach(m -> perMount.put(m, 0));
		for (String path : readPaths) {
			perMount.merge(mountOf(path), 1, Integer::sum);
		}
		StringBuilder read = new StringBuilder();
		for (Map.Entry<String, Integer> e : perMount.entrySet()) {
			if (read.length() > 0) {
				read.append(' ');
			}
			read.append(e.getKey()).append('=').append(e.getValue());
		}
		return "llamadas buscar=" + callsSearch + " leer=" + callsRead + " listar=" + callsList + " · archivos leídos " + read + " · caracteres=" + returned;
	}

	/* ------------------------------ invocacion ------------------------------ */

	private static String text(JsonObject input, String key) {
		JsonElement v = input.get(key);
		return J.isStr(v) ? v.getAsString().trim() : "";
	}

	private static int intArg(JsonObject input, String key, int min, int max, int fallback) {
		Double n = numberArg(input.get(key));
		if (n == null) {
			return fallback;
		}
		return (int) Math.min(max, Math.max(min, n.longValue()));
	}

	private static Double numberArg(JsonElement v) {
		if (J.isNum(v)) {
			Double d = J.num(v);
			return d == null ? null : Double.valueOf(Math.floor(Math.abs(d)) * Math.signum(d));
		}
		if (J.isStr(v) && !v.getAsString().trim().isEmpty()) {
			try {
				double d = Double.parseDouble(v.getAsString().trim());
				return Double.isFinite(d) ? Double.valueOf(d) : null;
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static Integer optionalInt(JsonObject input, String key) {
		Double n = numberArg(input.get(key));
		return n == null ? null : Integer.valueOf((int) n.longValue());
	}

	private static String clip(String value, int max) {
		if (value.length() <= max) {
			return value;
		}
		String note = "\n…(recortado)";
		return Semantics.cut(value, Math.max(0, max - note.length())) + note;
	}

	/** Ejecuta una herramienta y devuelve su texto (recortado a maxChars). */
	public String invoke(String name, JsonElement inputEl, int maxChars) {
		token.check();
		JsonObject args = J.obj(inputEl) != null ? inputEl.getAsJsonObject() : new JsonObject();
		String out;
		try {
			switch (name) {
			case "traza_search":
				callsSearch++;
				out = search(args);
				break;
			case "traza_read":
				callsRead++;
				out = read(args);
				break;
			case "traza_list":
				callsList++;
				out = list(args);
				break;
			default:
				out = "Herramienta desconocida: usa traza_search, traza_read o traza_list.";
			}
		} catch (CancelToken.CancelledException e) {
			throw e;
		} catch (RuntimeException e) {
			TrazaPlugin.warn("Fallo en una herramienta del agente (" + e.getClass().getSimpleName() + ").");
			out = "Error al ejecutar la herramienta; prueba con otra consulta.";
		}
		token.check();
		String result = clip(out, Math.max(0, maxChars));
		synchronized (this) {
			returned += result.length();
		}
		return result;
	}

	/* ------------------------------ cache de contenido ------------------------------ */

	/** Contenido enmascarado; null si no se puede leer, supera maxBytes o lleva una clave privada. */
	private String load(ProjectFile file, int maxBytes) {
		String key = file.path;
		synchronized (this) {
			String hit = contents.get(key);
			if (hit != null) {
				return hit.length() > maxBytes ? null : hit;
			}
			if (contentMiss.contains(key) && (denied.contains(key) || maxBytes <= failedAt.getOrDefault(key, 0))) {
				return null;
			}
		}
		String raw = WorkspaceFiles.readText(file, maxBytes);
		token.check();
		synchronized (this) {
			if (raw == null) {
				failedAt.put(key, maxBytes);
				contentMiss.add(key);
				return null;
			}
			if (FlowContextCollector.PRIVATE_KEY.matcher(raw).find()) {
				denied.add(key);
				contentMiss.add(key);
				return null;
			}
			String masked = SecretMasker.maskConfigSecrets(file.path, raw);
			if (cachedChars + masked.length() > CACHE_MAX_CHARS) {
				contents.clear();
				cachedChars = 0;
			}
			contents.put(key, masked);
			contentMiss.remove(key);
			cachedChars += masked.length();
			return masked;
		}
	}

	/* ------------------------------ resolucion de rutas ------------------------------ */

	private static final class Resolved {
		ProjectFile file;
		String error;
	}

	private static Resolved ok(ProjectFile f) {
		Resolved r = new Resolved();
		r.file = f;
		return r;
	}

	private static Resolved err(String e) {
		Resolved r = new Resolved();
		r.error = e;
		return r;
	}

	private synchronized Resolved resolve(String raw) {
		Resolved notFound = err("La ruta no existe o no está permitida.");
		String slashed = raw.trim().replace('\\', '/');
		if (slashed.isEmpty() || slashed.length() > 520) {
			return err("Ruta no válida.");
		}
		boolean drive = Pattern.compile("^[a-zA-Z]:/").matcher(slashed).find();
		if (drive || slashed.startsWith("/")) {
			String found = null;
			try {
				found = pathOfUri(new java.io.File(slashed).toURI().toString());
			} catch (RuntimeException e) {
				found = null;
			}
			ProjectFile file = found == null ? null : byPath.get(found);
			if (file != null) {
				return ok(file);
			}
			if (drive) {
				return notFound;
			}
		}
		String rel = Guards.normalizeCodePath(slashed.replaceFirst("^(?:\\./)+", "").replaceFirst("^/+", ""));
		if (rel == null || rel.isEmpty()) {
			return err("Ruta no válida.");
		}
		ProjectFile exact = byPath.get(rel);
		if (exact != null) {
			return ok(exact);
		}
		String lower = rel.toLowerCase(Locale.ROOT);
		List<String> same = byLower.get(lower);
		if (same != null && same.size() == 1) {
			return ok(byPath.get(same.get(0)));
		}
		if (same != null && same.size() > 1) {
			return ambiguous(same);
		}
		if (multi) {
			List<String> prefixed = new ArrayList<>();
			for (String m : mounts) {
				prefixed.addAll(byLower.getOrDefault((m + "/" + rel).toLowerCase(Locale.ROOT), List.of()));
			}
			if (prefixed.size() == 1) {
				return ok(byPath.get(prefixed.get(0)));
			}
			if (prefixed.size() > 1) {
				return ambiguous(prefixed);
			}
		}
		List<String> suffix = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : byLower.entrySet()) {
			if (e.getKey().endsWith("/" + lower)) {
				suffix.addAll(e.getValue());
			}
		}
		if (suffix.size() == 1) {
			return ok(byPath.get(suffix.get(0)));
		}
		if (suffix.size() > 1) {
			return ambiguous(suffix);
		}
		return notFound;
	}

	private static Resolved ambiguous(List<String> paths) {
		String options = String.join(", ", paths.subList(0, Math.min(AMBIGUOUS_OPTIONS, paths.size())));
		return err("Ruta ambigua; indica la carpeta. Opciones: " + options + (paths.size() > AMBIGUOUS_OPTIONS ? " (+" + (paths.size() - AMBIGUOUS_OPTIONS) + ")" : ""));
	}

	/* ------------------------------ filtros ------------------------------ */

	/** Glob a regex sin distinguir mayusculas: ** y / = carpetas opcionales, ** = todo, * y ? = un segmento, {a,b} = alternativa. */
	private static Pattern globToRegExp(String glob) {
		String g = glob.trim().replace('\\', '/');
		if (g.isEmpty() || g.length() > QUERY_MAX) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		int braces = 0;
		for (int i = 0; i < g.length(); i++) {
			if (g.startsWith("**/", i)) {
				out.append("(?:.*/)?");
				i += 2;
			} else if (g.startsWith("**", i)) {
				out.append(".*");
				i += 1;
			} else if (g.charAt(i) == '*') {
				out.append("[^/]*");
			} else if (g.charAt(i) == '?') {
				out.append("[^/]");
			} else if (g.charAt(i) == '{') {
				braces++;
				out.append("(?:");
			} else if (g.charAt(i) == '}' && braces > 0) {
				braces--;
				out.append(')');
			} else if (g.charAt(i) == ',' && braces > 0) {
				out.append('|');
			} else {
				out.append(Pattern.quote(String.valueOf(g.charAt(i))));
			}
		}
		out.append(")".repeat(braces));
		try {
			return Pattern.compile("^" + out + "$", Pattern.CASE_INSENSITIVE);
		} catch (PatternSyntaxException e) {
			TrazaPlugin.warn("Glob no válido.");
			return null;
		}
	}

	private static final class Filtered {
		List<ProjectFile> files = new ArrayList<>();
		String error;
	}

	private synchronized Filtered filter(String pathRaw, String patternRaw) {
		Filtered result = new Filtered();
		String prefix = "";
		if (!pathRaw.isEmpty()) {
			String normalized = Guards.normalizeCodePath(pathRaw.replaceFirst("^(?:\\./)+", "").replaceFirst("^/+", ""));
			if (normalized == null) {
				result.error = "path no válido.";
				return result;
			}
			prefix = normalized.toLowerCase(Locale.ROOT);
			if (!multi && prefix.equals((mounts.isEmpty() ? "" : mounts.get(0)).toLowerCase(Locale.ROOT))) {
				prefix = "";
			}
		}
		Pattern glob = null;
		if (!patternRaw.isEmpty()) {
			glob = globToRegExp(patternRaw);
			if (glob == null) {
				result.error = "pattern no válido.";
				return result;
			}
		}
		boolean baseOnly = !patternRaw.contains("/");
		final String pfx = prefix;
		Predicate<String> under = value -> value.equals(pfx) || value.startsWith(pfx + "/");
		List<String> paths = new ArrayList<>();
		for (ProjectFile file : byPath.values()) {
			if (!pfx.isEmpty()) {
				String lower = file.path.toLowerCase(Locale.ROOT);
				if (!under.test(lower) && !(multi && under.test(relOf(lower)))) {
					continue;
				}
			}
			if (glob != null) {
				boolean matched = baseOnly ? glob.matcher(file.name).find()
					: glob.matcher(file.path).find() || (multi && glob.matcher(relOf(file.path)).find());
				if (!matched) {
					continue;
				}
			}
			paths.add(file.path);
		}
		paths.sort(AgentToolSession::compareLocale);
		for (String p : FlowContextCollector.interleaveByMount(paths, multi)) {
			result.files.add(byPath.get(p));
		}
		return result;
	}

	private String folderLabel(String pathRaw) {
		String normalized = !pathRaw.isEmpty() ? Guards.normalizeCodePath(pathRaw) : null;
		if (normalized != null && multi) {
			String first = normalized.split("/")[0].toLowerCase(Locale.ROOT);
			for (String m : mounts) {
				if (m.toLowerCase(Locale.ROOT).equals(first)) {
					return m;
				}
			}
		}
		return Semantics.cut(String.join(", ", mounts), PROGRESS_MAX);
	}

	/* ------------------------------ traza_search ------------------------------ */

	/** CharSequence que aborta una busqueda con regex demasiado costosa. */
	private static final class Deadline implements CharSequence {
		private final CharSequence inner;
		private final long deadline;
		private int count;

		Deadline(CharSequence inner, long deadline) {
			this.inner = inner;
			this.deadline = deadline;
		}

		@Override
		public char charAt(int index) {
			if ((++count & 0xFFFF) == 0 && System.currentTimeMillis() > deadline) {
				throw new IllegalStateException("regex-timeout");
			}
			return inner.charAt(index);
		}

		@Override
		public int length() {
			return inner.length();
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new Deadline(inner.subSequence(start, end), deadline);
		}

		@Override
		public String toString() {
			return inner.toString();
		}
	}

	private interface LineMatcher {
		boolean test(String line);

		default boolean prefilter(String content) {
			return true;
		}
	}

	private Object matcher(String query, String mode, long deadline) {
		if (mode.equals("regex")) {
			try {
				Pattern re = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
				return (LineMatcher) line -> re.matcher(new Deadline(line.length() > REGEX_LINE_CHARS ? line.substring(0, REGEX_LINE_CHARS) : line, deadline)).find();
			} catch (PatternSyntaxException e) {
				return "Regex no válida (" + Semantics.cut(String.valueOf(e.getDescription()), 120) + ").";
			}
		}
		if (mode.equals("symbol")) {
			if (!SYMBOL_NAME.matcher(query).matches()) {
				return "En modo symbol, query debe ser un nombre (letras, dígitos, _ o $; máx. 80).";
			}
			String name = Pattern.quote(query);
			Pattern type = Pattern.compile("\\b(?:class|interface|enum|record|type|function|def|fun|struct|trait|object)\\s+" + name + "\\b", Pattern.CASE_INSENSITIVE);
			Pattern method = Pattern.compile("^\\s*(?:(?:public|private|protected|static|async|override|final|abstract|export|default|readonly|synchronized)\\s+)*[\\w<>\\[\\],.?\\s]*\\b"
				+ name + "\\s*\\(", Pattern.CASE_INSENSITIVE);
			String lowerName = query.toLowerCase(Locale.ROOT);
			return new LineMatcher() {
				@Override
				public boolean test(String line) {
					String l = line.length() > REGEX_LINE_CHARS ? line.substring(0, REGEX_LINE_CHARS) : line;
					return type.matcher(new Deadline(l, deadline)).find() || method.matcher(new Deadline(l, deadline)).find();
				}

				@Override
				public boolean prefilter(String content) {
					return content.toLowerCase(Locale.ROOT).contains(lowerName);
				}
			};
		}
		String lower = query.toLowerCase(Locale.ROOT);
		return new LineMatcher() {
			@Override
			public boolean test(String line) {
				return line.toLowerCase(Locale.ROOT).contains(lower);
			}

			@Override
			public boolean prefilter(String content) {
				return content.toLowerCase(Locale.ROOT).contains(lower);
			}
		};
	}

	private String search(JsonObject input) {
		String query = text(input, "query");
		if (query.isEmpty() || query.length() > QUERY_MAX) {
			return "query debe tener entre 1 y " + QUERY_MAX + " caracteres.";
		}
		String modeRaw = text(input, "mode");
		String mode = modeRaw.equals("regex") || modeRaw.equals("symbol") ? modeRaw : "text";
		long deadline = System.currentTimeMillis() + SEARCH_TIME_MS;
		Object m = matcher(query, mode, deadline);
		if (m instanceof String) {
			return (String) m;
		}
		LineMatcher matcher = (LineMatcher) m;
		int maxResults = intArg(input, "maxResults", 1, SEARCH_MAX, SEARCH_DEFAULT);
		String pathRaw = text(input, "path");
		Filtered filtered = filter(pathRaw, text(input, "pattern"));
		if (filtered.error != null) {
			return filtered.error;
		}
		if (filtered.files.isEmpty()) {
			return "Ningún archivo permitido cumple path/pattern.";
		}
		say("Buscando en " + folderLabel(pathRaw) + "…", "Searching in " + folderLabel(pathRaw) + "…");

		List<ProjectFile> files = filtered.files;
		List<List<int[]>> hitLines = new ArrayList<>();
		List<List<String>> hitTexts = new ArrayList<>();
		for (int i = 0; i < files.size(); i++) {
			hitLines.add(null);
			hitTexts.add(null);
		}
		AtomicInteger found = new AtomicInteger();
		AtomicInteger scanned = new AtomicInteger();
		AtomicInteger skipped = new AtomicInteger();
		boolean[] stopped = new boolean[1];
		Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < files.size(); i++) {
			index.put(files.get(i).path, i);
		}
		FlowContextCollector.pool(files, SCAN_CONCURRENCY, file -> {
			if (stopped[0]) {
				return;
			}
			if (System.currentTimeMillis() > deadline || found.get() >= maxResults) {
				stopped[0] = true;
				return;
			}
			String content = load(file, SEARCH_FILE_BYTES);
			if (content == null) {
				skipped.incrementAndGet();
				return;
			}
			scanned.incrementAndGet();
			if (!matcher.prefilter(content)) {
				return;
			}
			String[] lines = content.split("\r?\n", -1);
			List<int[]> nums = new ArrayList<>();
			List<String> texts = new ArrayList<>();
			int all = 0;
			try {
				for (int i = 0; i < lines.length; i++) {
					if (!matcher.test(lines[i])) {
						continue;
					}
					all++;
					if (nums.size() < MATCHES_PER_FILE) {
						nums.add(new int[] { i + 1 });
						texts.add(Semantics.cut(lines[i].trim(), LINE_CHARS));
					}
				}
			} catch (IllegalStateException e) {
				if ("regex-timeout".equals(e.getMessage())) {
					stopped[0] = true;
					return;
				}
				throw e;
			}
			if (all == 0) {
				return;
			}
			int at = index.get(file.path);
			synchronized (hitLines) {
				hitLines.set(at, nums);
				hitTexts.set(at, texts);
			}
			found.addAndGet(nums.size());
		}, token);
		token.check();

		Map<String, Integer> perFolder = new LinkedHashMap<>();
		mounts.forEach(mm -> perFolder.put(mm, 0));
		List<String> body = new ArrayList<>();
		int shown = 0;
		int filesWithHits = 0;
		for (int i = 0; i < files.size(); i++) {
			List<int[]> list = hitLines.get(i);
			if (list == null) {
				continue;
			}
			int take = Math.min(list.size(), Math.max(0, maxResults - shown));
			if (take <= 0) {
				break;
			}
			String path = files.get(i).path;
			filesWithHits++;
			perFolder.merge(mountOf(path), 1, Integer::sum);
			body.add(path);
			for (int k = 0; k < take; k++) {
				body.add("  " + list.get(k)[0] + "| " + hitTexts.get(i).get(k));
				markRead(path);
			}
			shown += take;
		}
		StringBuilder counts = new StringBuilder();
		for (Map.Entry<String, Integer> e : perFolder.entrySet()) {
			if (counts.length() > 0) {
				counts.append(' ');
			}
			counts.append(e.getKey()).append('=').append(e.getValue()).append(" archivos");
		}
		String out = shown + " coincidencias en " + filesWithHits + " archivos · " + counts + "\n" + String.join("\n", body);
		if (shown == 0) {
			out = "0 coincidencias · " + counts;
		}
		if (stopped[0] && found.get() >= maxResults) {
			out += "\n(límite de resultados alcanzado: refina con path o pattern)";
		} else if (stopped[0]) {
			out += "\n(búsqueda parcial: " + (scanned.get() + skipped.get()) + " de " + files.size() + " archivos)";
		}
		if (skipped.get() > 0) {
			out += "\n(" + skipped.get() + " archivos omitidos por tamaño o no legibles)";
		}
		return out;
	}

	/* ------------------------------ traza_read ------------------------------ */

	private String read(JsonObject input) {
		String rawPath = text(input, "path");
		if (rawPath.isEmpty()) {
			return "path es obligatorio.";
		}
		Resolved resolved = resolve(rawPath);
		if (resolved.error != null) {
			return resolved.error;
		}
		ProjectFile file = resolved.file;
		say("Leyendo " + Semantics.cut(file.name, PROGRESS_MAX) + "…", "Reading " + Semantics.cut(file.name, PROGRESS_MAX) + "…");
		String content = load(file, READ_FILE_BYTES);
		if (content == null) {
			synchronized (this) {
				return denied.contains(file.path) ? "Archivo no permitido." : "No se pudo leer el archivo (binario, ilegible o demasiado grande).";
			}
		}
		List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\r?\n", -1)));
		if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		int total = lines.size();
		Integer startIn = optionalInt(input, "startLine");
		Integer endIn = optionalInt(input, "endLine");
		int start;
		int end;
		if (startIn == null && endIn == null) {
			start = 1;
			end = total <= READ_WHOLE_LINES ? total : READ_DEFAULT_LINES;
		} else if (startIn != null) {
			start = Math.max(1, startIn);
			end = endIn != null ? endIn : start + READ_DEFAULT_LINES - 1;
		} else {
			end = Math.max(1, endIn);
			start = Math.max(1, end - READ_DEFAULT_LINES + 1);
		}
		if (start > total) {
			return "El archivo tiene " + total + " líneas; startLine " + start + " está fuera de rango.";
		}
		if (end < start) {
			return "endLine debe ser mayor o igual que startLine.";
		}
		end = Math.min(Math.min(end, total), start + READ_MAX_LINES - 1);
		List<String> slice = new ArrayList<>(lines.subList(start - 1, end));
		int chars = 0;
		int keep = 0;
		for (String line : slice) {
			if (keep > 0 && chars + line.length() + 1 > READ_MAX_CHARS) {
				break;
			}
			chars += line.length() + 1;
			keep++;
		}
		slice = new ArrayList<>(slice.subList(0, keep));
		if (slice.size() == 1 && slice.get(0).length() > READ_MAX_CHARS) {
			slice = new ArrayList<>(List.of(Semantics.cut(slice.get(0), READ_MAX_CHARS)));
		}
		end = start + slice.size() - 1;
		markRead(file.path);
		String out = "### " + file.path + ":L" + start + "-L" + end + " (de " + total + " lineas)\n" + Semantics.numbered(String.join("\n", slice), start);
		if (end < total) {
			out += "\n(quedan líneas hasta la " + total + ": pide otro rango con startLine/endLine)";
		}
		return out;
	}

	/* ------------------------------ traza_list ------------------------------ */

	private String list(JsonObject input) {
		String pathRaw = text(input, "path");
		String patternRaw = text(input, "pattern");
		if (pathRaw.isEmpty() && patternRaw.isEmpty()) {
			say("Explorando " + Semantics.cut(String.join(", ", mounts), PROGRESS_MAX) + "…", "Exploring " + Semantics.cut(String.join(", ", mounts), PROGRESS_MAX) + "…");
			return overview();
		}
		Filtered filtered = filter(pathRaw, patternRaw);
		if (filtered.error != null) {
			return filtered.error;
		}
		String label = folderLabel(pathRaw);
		say("Explorando " + label + "…", "Exploring " + label + "…");
		if (filtered.files.isEmpty()) {
			return "Ningún archivo permitido cumple path/pattern.";
		}
		List<ProjectFile> shown = filtered.files.subList(0, Math.min(LIST_MAX, filtered.files.size()));
		StringBuilder sb = new StringBuilder(filtered.files.size() + " archivos (mostrando " + shown.size() + "); refina con path o pattern");
		for (ProjectFile f : shown) {
			sb.append('\n').append(f.path);
		}
		return sb.toString();
	}

	/** Conjunto de rutas (utilidad para pruebas de pertenencia desde fuera). */
	public synchronized Set<String> paths() {
		return new LinkedHashSet<>(byPath.keySet());
	}
}
