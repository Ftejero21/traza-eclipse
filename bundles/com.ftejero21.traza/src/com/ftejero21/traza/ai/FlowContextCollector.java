package com.ftejero21.traza.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.ContextExclude;
import com.ftejero21.traza.core.EditorHint;
import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.SecretMasker;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.core.WorkspaceFiles.Mount;
import com.ftejero21.traza.core.WorkspaceFiles.ProjectFile;
import com.ftejero21.traza.core.WorkspaceFiles.Snapshot;

/**
 * Port de host-context.ts: recogida de contexto local para generar un flujo (FLOW) o proponer un cambio (PROPOSE):
 * mapa del proyecto puntuado por palabras clave, fragmentos con secretos enmascarados, archivo activo y seleccion,
 * puente HTTP front -> back y anclas de metodos. Nunca incluye archivos sensibles, excluidos ni binarios.
 */
public final class FlowContextCollector {
	private static final int MAX_SCAN_FILES = 4000;
	private static final int MAX_SCAN_BYTES = 256 * 1024;
	private static final int SCAN_CONCURRENCY = 8;
	private static final int MAX_TERM_HITS = 20;
	private static final int MAP_FLOW_CHARS = 20_000;
	private static final int KEYWORD_TOP = 3;
	private static final int MAX_FILES = 20;
	private static final int MAX_BASE_FILES = 14;
	private static final int MAX_REF_FILES = 8;
	private static final int MAX_REF_FILES_PER_MOUNT = 4;
	private static final int MAX_BRIDGE_FILES = 3;
	private static final int MAX_HTTP_CALLS = 24;
	private static final int MAX_BRIDGE_SOURCES = 8;
	private static final double FLOW_MOUNT_RELEVANCE = 0.2;
	private static final int KEYWORD_TOP_PER_MOUNT_FLOW = 3;
	private static final int REF_DEPTH = 2;
	private static final int MAX_FORCED = 6;
	private static final int FORCED_BONUS = 30;
	private static final int KEYWORD_TOP_PER_MOUNT = 2;
	private static final int MAX_FILE_CHARS = 14_000;
	private static final int MIN_TRIM_CHARS = 400;
	private static final List<String> REF_EXTENSIONS = List.of("java", "kt", "ts", "tsx", "js", "jsx", "cs", "py", "go", "php", "scala", "groovy");
	private static final int READ_BYTES = 3 * 1024 * 1024;
	private static final int MAX_IDENTIFIERS = 8;
	private static final int MAX_REF_TYPES = 40;
	private static final int MAX_REF_SCAN_CHARS = 400_000;
	private static final int MAX_QUESTION_ANCHORS = 12;
	private static final int MAX_ANCHOR_METHODS = 20;
	private static final int MAX_ANCHOR_WINDOWS = 3;
	private static final int ANCHOR_MAX_LINES = 120;
	private static final int ANCHOR_LEAD_LINES = 12;
	private static final int ANCHOR_BUDGET = 48_000;
	private static final int ANCHOR_ROUNDS = 3;
	private static final int MAX_FIELD_TYPES = 40;
	private static final List<String> BRACE_EXTENSIONS = REF_EXTENSIONS.stream().filter(e -> !e.equals("py")).collect(Collectors.toList());
	private static final String REF_TYPE_SOURCE = "[A-Z][A-Za-z0-9]{2,60}?(?:ServiceImpl|Service|Repository|Repo|Dao|Mapper|Client|Gateway|Manager|Facade|Provider|Store|Api|Adapter|UseCase)";
	private static final int FULL_FILE_CHARS = 8000;
	private static final int WINDOW_RADIUS = 50;
	private static final int WINDOWS_PER_FILE = 3;
	private static final int HEAD_LINES = 40;
	private static final int MAX_FRAGMENTS = 40;
	private static final int MAX_FRAGMENT_CHARS = 8000;
	private static final int MAX_FRAGMENTS_TOTAL = 80_000;
	private static final int ACTIVE_FULL_CHARS = 16_000;
	private static final int ACTIVE_RADIUS = 150;
	private static final int MAX_SELECTION_CHARS = 4000;
	private static final String TRUNCATED = "\n\u2026(truncado)";
	public static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
	private static final int RESERVED_REQUEST_FILES = 6;
	private static final int FOCUS_SPAN = 2;
	private static final int FOCUS_RADIUS = 15;
	private static final int FOCUS_WINDOWS = 3;
	private static final int FOCUS_MIN_TERMS = 2;
	private static final int MAX_LOCAL_ANCHORS = 2;
	private static final int SIBLING_SOURCES = 2;
	private static final int MAX_SIBLINGS = 4;
	private static final List<String> SIBLING_SOURCE_EXTENSIONS = List.of("ts", "tsx", "js", "jsx", "html");
	private static final List<String> SIBLING_EXTENSIONS = List.of("html", "css", "scss", "sass", "less", "ts");
	private static final Pattern LOCAL_CALL = Pattern.compile("\\bthis\\s*\\.\\s*([A-Za-z_$][\\w$]{1,59})\\s*\\(");
	private static final int MAX_BRIDGE_HINTS = 12;
	private static final List<String> VISUAL_WORDS = List.of(
		"color", "colour", "colores", "fondo", "background", "estilo", "estilos", "style", "styles", "css", "scss", "visual", "rojo", "azul",
		"verde", "amarillo", "negro", "blanco", "gris", "naranja", "morado", "borde", "border", "margen", "margin", "padding", "fuente", "font",
		"tama\u00f1o", "icono", "icon", "sombra", "shadow", "animacion", "animation", "dise\u00f1o", "design", "layout", "tema", "theme", "oscuro", "dark",
		"opacidad", "opacity", "hover", "boton", "button", "plantilla", "template", "html");
	private static final Set<String> LOW_WEIGHT_EXTENSIONS = Set.of("css", "scss", "sass", "less", "json", "md", "markdown", "txt", "svg", "xml", "csv", "lock", "map");

	private FlowContextCollector() {
	}

	/* ---------------------------------- tokens ---------------------------------- */

	/** Quita tildes y diacriticos (solo si el texto no es ASCII puro). */
	public static String foldAccents(String s) {
		boolean ascii = true;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) > 0x7f) {
				ascii = false;
				break;
			}
		}
		return ascii ? s : Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "");
	}

	private static final Set<String> STOPWORDS = new HashSet<>();

	static {
		String[] words = {
			"de", "la", "el", "en", "que", "los", "las", "un", "una", "unos", "unas", "por", "con", "para", "del", "al", "lo", "se", "es",
			"su", "sus", "como", "mas", "m\u00e1s", "pero", "sin", "sobre", "este", "esta", "esto", "estos", "estas", "ese", "esa", "eso", "hay",
			"ya", "muy", "cuando", "donde", "d\u00f3nde", "qu\u00e9", "c\u00f3mo", "cual", "cu\u00e1l", "me", "mi", "te", "tu", "le", "les", "nos", "ni", "no",
			"si", "s\u00ed", "yo", "\u00e9l", "ella", "hace", "hacer", "puede", "puedes", "quiero", "favor", "archivo", "codigo", "c\u00f3digo",
			"necesito", "explica", "explicar", "explicame", "expl\u00edcame", "expliques", "explicacion", "explicaci\u00f3n", "detalle", "detalles", "detallado",
			"funciona", "funcionan", "funcionamiento", "funcionalidad", "funcionalidades", "describe", "describir", "quisiera", "saber", "entender",
			"the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "be", "it", "this", "that", "these",
			"those", "as", "at", "by", "from", "how", "what", "where", "when", "why", "which", "do", "does", "can", "could", "should", "would",
			"my", "me", "you", "your", "we", "our", "not", "if", "then", "there", "here", "into", "about", "please", "file", "code",
			"explain", "detail", "details", "works", "work", "functionality", "feature" };
		for (String w : words) {
			STOPWORDS.add(foldAccents(w));
		}
	}

	private static final String[] STEM_SUFFIXES;

	static {
		String[] s = { "amientos", "imientos", "amiento", "imiento", "aciones", "iciones", "adores", "edores", "idores", "ations",
			"acion", "icion", "iendo", "erias", "ation", "ador", "edor", "idor", "ando", "ados", "idos", "adas", "idas", "ales", "eria",
			"ings", "edly", "ado", "ido", "ada", "ida", "ing", "ers", "al", "ar", "er", "ir", "es", "os", "as", "ed", "o", "a", "e", "s" };
		List<String> l = new ArrayList<>(Arrays.asList(s));
		l.sort((x, y) -> y.length() - x.length());
		STEM_SUFFIXES = l.toArray(new String[0]);
	}

	private static String stemTerm(String term) {
		if (term.length() < 4) {
			return term;
		}
		if (term.endsWith("ies") && term.length() - 3 >= 3) {
			return term.substring(0, term.length() - 3) + "y";
		}
		for (String suffix : STEM_SUFFIXES) {
			if (term.length() - suffix.length() >= 3 && term.endsWith(suffix)) {
				return term.substring(0, term.length() - suffix.length());
			}
		}
		return term;
	}

	private static final int MAX_TOKEN_CHARS = 40;
	private static final int MAX_NUMERIC_CHARS = 6;
	private static final Pattern CAMEL1 = Pattern.compile("([a-z0-9])([A-Z])");
	private static final Pattern CAMEL2 = Pattern.compile("([A-Z]+)([A-Z][a-z])");
	private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
	private static final Pattern DIGITS = Pattern.compile("^\\d+$");

	/** Separa por no alfanumericos, camelCase y snake_case; pliega tildes; minusculas; sin stopwords; stemming ligero. */
	public static List<String> tokenize(String text) {
		String spaced = foldAccents(text == null ? "" : text);
		spaced = CAMEL1.matcher(spaced).replaceAll("$1 $2");
		spaced = CAMEL2.matcher(spaced).replaceAll("$1 $2");
		List<String> out = new ArrayList<>();
		for (String raw : NON_ALNUM.split(spaced)) {
			if (raw.length() < 2 || raw.length() > MAX_TOKEN_CHARS) {
				continue;
			}
			String token = raw.toLowerCase(Locale.ROOT);
			if (STOPWORDS.contains(token)) {
				continue;
			}
			if (token.length() > MAX_NUMERIC_CHARS && DIGITS.matcher(token).matches()) {
				continue;
			}
			out.add(stemTerm(token));
		}
		return out;
	}

	private static final String[][] SYNONYM_GROUPS = {
		{ "enviar", "envio", "send", "dispatch", "publish", "emit" }, { "mensaje", "message", "msg" }, { "grupo", "grupal", "group" },
		{ "usuario", "user" }, { "chat", "conversacion", "conversation" }, { "borrar", "eliminar", "delete", "remove" },
		{ "crear", "create" }, { "editar", "modificar", "actualizar", "edit", "update" }, { "buscar", "busqueda", "search", "find" },
		{ "individual", "privado", "private", "direct" }, { "notificacion", "notification" }, { "imagen", "image" },
		{ "subir", "upload" }, { "descargar", "download" }, { "contrasena", "password" },
		{ "registro", "registrar", "register", "signup" }, { "amigo", "friend" }, { "reaccion", "reaction" }, { "llamada", "call" },
		{ "perfil", "profile" }, { "pagina", "page" }, { "ruta", "route" }, { "guardar", "save", "store" }, { "leer", "read" },
		{ "recibir", "receive" }, { "respuesta", "response", "reply" }, { "error", "fallo", "exception" }, { "seguridad", "security" },
		{ "autenticacion", "authentication", "login", "auth" }, { "cifrar", "cifrado", "encrypt" }, { "descifrar", "decrypt" },
		{ "programado", "programar", "schedule", "scheduled" }, { "temporal", "temporary", "ephemeral" }, { "encuesta", "poll" },
		{ "comentario", "comment" }, { "documento", "document" }, { "tarea", "task" }, { "evento", "event" },
		{ "suscribir", "suscripcion", "subscribe", "subscription" },
		{ "incorrecto", "erroneo", "invalido", "incorrect", "wrong", "invalid" }, { "fondo", "background" }, { "estilo", "style" },
		{ "boton", "button" } };

	private static final Map<String, Set<String>> SYNONYMS = new HashMap<>();

	static {
		for (String[] group : SYNONYM_GROUPS) {
			Set<String> stems = new LinkedHashSet<>();
			for (String w : group) {
				stems.add(stemTerm(foldAccents(w).toLowerCase(Locale.ROOT)));
			}
			for (String stem : stems) {
				Set<String> set = SYNONYMS.computeIfAbsent(stem, k -> new LinkedHashSet<>());
				for (String other : stems) {
					if (!other.equals(stem)) {
						set.add(other);
					}
				}
			}
		}
	}

	private static final Set<String> VISUAL_STEMS = new HashSet<>();

	static {
		for (String w : VISUAL_WORDS) {
			VISUAL_STEMS.addAll(tokenize(w));
		}
	}

	/* --------------------------------- tipos --------------------------------- */

	public static class ContextMount {
		public String id;
		public String prefix;
		public String name;
	}

	public static class ContextFragment {
		public String path;
		public int startLine;
		public int endLine;
		public String content;
		/** Prioridad de recorte (mayor se conserva antes); no se incluye en el prompt. */
		public int priority;
	}

	public static final class ContextActive extends ContextFragment {
		public int totalLines;
	}

	public static final class ContextSelection {
		public int startLine;
		public int endLine;
		public String text;
	}

	public static final class FolderRole {
		public String mount;
		public List<String> roles = new ArrayList<>();
		public List<String> tech = new ArrayList<>();
	}

	public static final class BridgeHint {
		public String front;
		public int line;
		public String method;
		public String back;
		public String handler;
	}

	public static final class FlowContext {
		public List<ContextMount> mounts = new ArrayList<>();
		public String mapText = "";
		public List<ContextFragment> fragments = new ArrayList<>();
		public ContextActive active;
		public ContextSelection selection;
		public List<FolderRole> roles = new ArrayList<>();
		public List<BridgeHint> bridge = new ArrayList<>();
	}

	public static final class CollectOptions {
		public String question = "";
		/** Rutas del mapa (con prefijo de carpeta en multi-root) que se fijan primero (archivos del flujo en PROPOSE). */
		public List<String> pinnedPaths = new ArrayList<>();
		public boolean useActiveEditor;
		public EditorHint hint;
		public String folder;
		public CancelToken token = CancelToken.NONE;
		public Consumer<String> progress;
		/** PROPOSE: reserva plazas y ventanas foco para lo que pide la peticion frente a los archivos fijados del flujo. */
		public boolean prioritizeRequest;
	}

	private static final class Scored {
		final ProjectFile file;
		final double score;

		Scored(ProjectFile file, double score) {
			this.file = file;
			this.score = score;
		}
	}

	private static final class CallSite {
		int line;
		String method;
		List<String> targets;
	}

	private static final class MethodLocation {
		int decl;
		int end;
	}

	private static final class CodeScan {
		String path;
		String mount;
		String key;
		String ext;
		String[] lines;
		List<CallSite> calls;
		Map<String, MethodLocation> located = new HashMap<>();
		Set<String> locatedMiss = new HashSet<>();
	}

	private static class Range0 {
		int start;
		int end;

		Range0(int start, int end) {
			this.start = start;
			this.end = end;
		}
	}

	private static final class Planned extends Range0 {
		int priority;
		boolean anchor;
		boolean focus;

		Planned(int start, int end, int priority, boolean anchor, boolean focus) {
			super(start, end);
			this.priority = priority;
			this.anchor = anchor;
			this.focus = focus;
		}
	}

	private static final class Frag extends ContextFragment {
		boolean anchor;
		boolean focus;
	}

	/* -------------------------------- utilidades -------------------------------- */

	private static String baseName(String path) {
		return Guards.baseName(path);
	}

	private static double weightOf(String path) {
		return LOW_WEIGHT_EXTENSIONS.contains(Guards.codeExtension(baseName(path))) ? 0.4 : 1;
	}

	private static int countHits(String haystack, String term) {
		int count = 0;
		int from = 0;
		while (count < MAX_TERM_HITS) {
			int index = haystack.indexOf(term, from);
			if (index < 0) {
				break;
			}
			count++;
			from = index + term.length();
		}
		return count;
	}

	private static List<String> termsOf(List<String> texts) {
		Set<String> terms = new LinkedHashSet<>();
		for (String text : texts) {
			for (String token : tokenize(text)) {
				if (token.length() < 3) {
					continue;
				}
				terms.add(token);
				for (String syn : SYNONYMS.getOrDefault(token, Collections.emptySet())) {
					if (syn.length() >= 3) {
						terms.add(syn);
					}
				}
			}
		}
		List<String> list = new ArrayList<>(terms);
		return list.size() > 40 ? new ArrayList<>(list.subList(0, 40)) : list;
	}

	public static boolean allowed(ProjectFile file) {
		return !file.binary && !ContextExclude.isContextExcludedPath(file.path) && !Guards.isSensitiveCodeFile(file.name);
	}

	public static String readText(ProjectFile file, int maxBytes) {
		return WorkspaceFiles.readText(file, maxBytes);
	}

	/** Ejecuta `run` sobre cada elemento con hasta `size` hilos; no empieza elementos nuevos si se cancela. */
	public static <T> void pool(List<T> items, int size, Consumer<T> run, CancelToken token) {
		if (items.isEmpty()) {
			return;
		}
		AtomicInteger next = new AtomicInteger();
		int workers = Math.min(size, items.size());
		ExecutorService ex = Executors.newFixedThreadPool(workers, r -> {
			Thread t = new Thread(r, "traza-scan");
			t.setDaemon(true);
			return t;
		});
		try {
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (int w = 0; w < workers; w++) {
				futures.add(ex.submit(() -> {
					while (!token.isCancelled()) {
						int i = next.getAndIncrement();
						if (i >= items.size()) {
							return;
						}
						try {
							run.accept(items.get(i));
						} catch (CancelToken.CancelledException e) {
							return;
						} catch (RuntimeException e) {
							TrazaPlugin.warn("Fallo al procesar un archivo del contexto (" + e.getClass().getSimpleName() + ").");
						}
					}
				}));
			}
			for (java.util.concurrent.Future<?> f : futures) {
				try {
					f.get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					token.cancel();
					return;
				} catch (java.util.concurrent.ExecutionException e) {
					TrazaPlugin.warn("Fallo en un hilo de escaneo (" + e.getCause().getClass().getSimpleName() + ").");
				}
			}
		} finally {
			ex.shutdownNow();
			try {
				ex.awaitTermination(1, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static String mapText(List<Scored> scored, int max) {
		StringBuilder out = new StringBuilder();
		for (Scored s : scored) {
			String line = s.file.path + "\n";
			if (out.length() + line.length() > max) {
				return out + TRUNCATED.stripLeading();
			}
			out.append(line);
		}
		return out.toString().stripTrailing();
	}

	/* ------------------------- reparto por carpeta y referencias ------------------------- */

	private static String mountOf(String path, boolean multi) {
		if (!multi) {
			return "";
		}
		int index = path.indexOf('/');
		return index < 0 ? path : path.substring(0, index);
	}

	private static String relPath(String path, boolean multi) {
		if (!multi) {
			return path;
		}
		int index = path.indexOf('/');
		return index < 0 ? path : path.substring(index + 1);
	}

	private static String normKey(String text) {
		return text.replaceAll("[.\\-_]", "").toLowerCase(Locale.ROOT);
	}

	private static String fileKey(String name) {
		int dot = name.lastIndexOf('.');
		return normKey(dot > 0 ? name.substring(0, dot) : name);
	}

	/** Reparto max-min: demandas de menor a mayor, cada una recibe min(demanda, restante / pendientes, tope). */
	private static int[] fairShares(int[] demands, int total, int cap) {
		int[] out = new int[demands.length];
		Integer[] order = new Integer[demands.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingInt(i -> demands[i]));
		int remaining = total;
		for (int position = 0; position < order.length; position++) {
			int index = order[position];
			int pending = order.length - position;
			int share = Math.max(0, Math.min(Math.min(demands[index], remaining / pending), cap));
			out[index] = share;
			remaining -= share;
		}
		return out;
	}

	private static final Pattern TEST_DIR = Pattern.compile("(^|/)(test|tests|__tests__)/", Pattern.CASE_INSENSITIVE);
	private static final Pattern TEST_SPEC = Pattern.compile("\\.(spec|test)\\.", Pattern.CASE_INSENSITIVE);
	private static final Pattern TEST_CLASS = Pattern.compile("Tests?\\.(java|kt)$");

	private static boolean isTestPath(String path) {
		return TEST_DIR.matcher(path).find() || TEST_SPEC.matcher(path).find() || TEST_CLASS.matcher(path).find();
	}

	/** Round-robin entre carpetas conservando el orden dentro de cada una. */
	public static List<String> interleaveByMount(List<String> paths, boolean multi) {
		if (!multi) {
			return paths;
		}
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (String path : paths) {
			groups.computeIfAbsent(mountOf(path, true), k -> new ArrayList<>()).add(path);
		}
		List<List<String>> lists = new ArrayList<>(groups.values());
		List<String> out = new ArrayList<>();
		for (int round = 0; out.size() < paths.size(); round++) {
			for (List<String> list : lists) {
				if (round < list.size()) {
					out.add(list.get(round));
				}
			}
		}
		return out;
	}

	private static final Pattern IDENT_PASCAL = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+\\b");
	private static final Pattern IDENT_FILE = Pattern.compile("\\b[a-z][a-z0-9-]*\\.(?:component|service|controller|module|guard|interceptor|store|repository|resolver)\\b", Pattern.CASE_INSENSITIVE);

	private static final class MentionResult {
		String text;
		Set<String> mentioned = new HashSet<>();
	}

	/** Quita de la pregunta los nombres de carpeta del workspace (solo multi-root) y devuelve las carpetas nombradas. */
	private static MentionResult stripMountNames(String question, List<Mount> mounts) {
		MentionResult r = new MentionResult();
		r.text = question;
		if (mounts.size() <= 1) {
			return r;
		}
		for (Mount mount : mounts) {
			for (String name : new LinkedHashSet<>(Arrays.asList(mount.id, mount.name))) {
				if (name == null || name.isEmpty()) {
					continue;
				}
				Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
				r.text = J.replace(p, r.text, m -> {
					r.mentioned.add(mount.id);
					return " ";
				});
			}
		}
		return r;
	}

	private static List<String> identifiersOf(String text) {
		List<int[]> idx = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		for (Pattern p : new Pattern[] { IDENT_PASCAL, IDENT_FILE }) {
			Matcher m = p.matcher(text);
			while (m.find()) {
				String key = normKey(m.group());
				if (key.length() >= 3 && key.length() <= 60) {
					idx.add(new int[] { m.start(), keys.size() });
					keys.add(key);
				}
			}
		}
		idx.sort(Comparator.comparingInt(a -> a[0]));
		List<String> out = new ArrayList<>();
		for (int[] i : idx) {
			String key = keys.get(i[1]);
			if (!out.contains(key)) {
				out.add(key);
			}
			if (out.size() >= MAX_IDENTIFIERS) {
				break;
			}
		}
		return out;
	}

	private static final Pattern REF_TYPE = Pattern.compile("\\b(" + REF_TYPE_SOURCE + ")\\b");
	private static final Pattern REPO_ENTITY = Pattern.compile("\\b(?:Jpa|Crud|PagingAndSorting|Mongo|Reactive\\w*)?Repository<\\s*([A-Z]\\w{1,60})");

	private static final class RefParts {
		List<String> types = new ArrayList<>();
		String entity;
	}

	private static RefParts referencedParts(String content, String ext) {
		String text = content.length() > MAX_REF_SCAN_CHARS ? content.substring(0, MAX_REF_SCAN_CHARS) : content;
		RefParts parts = new RefParts();
		Set<String> seen = new HashSet<>();
		Matcher m = REF_TYPE.matcher(text);
		while (m.find()) {
			String key = normKey(m.group(1));
			if (!seen.add(key)) {
				continue;
			}
			if (!key.endsWith("impl")) {
				parts.types.add(key + "impl");
			}
			parts.types.add(key);
			if (seen.size() >= MAX_REF_TYPES) {
				break;
			}
		}
		if (ext.equals("java") || ext.equals("kt")) {
			Matcher e = REPO_ENTITY.matcher(text);
			if (e.find()) {
				parts.entity = normKey(e.group(1));
			}
		}
		return parts;
	}

	/* -------------------------------- fragmentos -------------------------------- */

	private static int lengthOf(String[] lines, int start, int end) {
		int n = 0;
		for (int i = start; i <= end && i < lines.length; i++) {
			n += lines[i].length() + 1;
		}
		return n;
	}

	private static Range0 clampWindow(String[] lines, Range0 range, int center) {
		int start = range.start;
		int end = range.end;
		while (lengthOf(lines, start, end) > MAX_FRAGMENT_CHARS && end > start) {
			if (end - center >= center - start) {
				end--;
			} else {
				start++;
			}
		}
		return new Range0(start, end);
	}

	private static final Pattern DECL_STATEMENT = Pattern.compile("^\\s*(return|await|if|else|while|for|switch|throw|new|yield|case)\\b");
	private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(\\([^)]*\\))?");
	private static final Set<String> NON_METHOD_NAMES = Set.of("html", "css", "scss", "json", "md", "xml", "yml", "yaml", "properties");
	private static final String CALL_SOURCE = "(?:^|[^\\w$.])(?:this\\.)?([a-z_$][A-Za-z0-9_$]{1,59})\\s*\\.\\s*([A-Za-z_$][A-Za-z0-9_$]{1,59})\\s*\\(";
	private static final Pattern CALL_RE = Pattern.compile(CALL_SOURCE);
	private static final String JAVA_FIELD_SOURCE = "\\b(" + REF_TYPE_SOURCE + ")(?:<[^<>;(){}]{0,80}>)?\\s+([a-z_$][\\w$]{0,59})\\s*[;=,)]";
	private static final String COLON_FIELD_SOURCE = "\\b([a-z_$][\\w$]{1,59})\\s*[?!]?\\s*:\\s*(" + REF_TYPE_SOURCE + ")\\b(?![\\w$])";
	private static final String INJECT_FIELD_SOURCE = "\\b([a-z_$][\\w$]{1,59})\\s*=\\s*inject\\s*(?:<[^<>()]{0,80}>)?\\s*\\(\\s*(" + REF_TYPE_SOURCE + ")\\b";
	private static final Pattern JAVA_FIELD_RE = Pattern.compile(JAVA_FIELD_SOURCE);
	private static final Pattern COLON_FIELD_RE = Pattern.compile(COLON_FIELD_SOURCE);
	private static final Pattern INJECT_FIELD_RE = Pattern.compile(INJECT_FIELD_SOURCE);
	private static final Pattern METHOD_CAMEL = Pattern.compile("\\b[a-z][a-z0-9]+(?:[A-Z][a-zA-Z0-9]*)+\\b");
	private static final Pattern METHOD_SCOPED = Pattern.compile("\\b([A-Z][A-Za-z0-9]{1,60})\\.([a-z_$][A-Za-z0-9_$]{1,59})\\b");

	private static final class QuestionAnchors {
		List<String> global = new ArrayList<>();
		Map<String, List<String>> scoped = new LinkedHashMap<>();
	}

	private static QuestionAnchors methodAnchorsOf(String text) {
		List<Object[]> found = new ArrayList<>();
		Matcher m = METHOD_CAMEL.matcher(text);
		while (m.find()) {
			if (m.group().length() >= 2 && m.group().length() <= 60) {
				found.add(new Object[] { m.start(), m.group(), null });
			}
		}
		Matcher s = METHOD_SCOPED.matcher(text);
		while (s.find()) {
			String method = s.group(2);
			if (REF_EXTENSIONS.contains(method) || NON_METHOD_NAMES.contains(method)) {
				continue;
			}
			found.add(new Object[] { s.start(), method, normKey(s.group(1)) });
		}
		found.sort(Comparator.comparingInt(a -> (Integer) a[0]));
		QuestionAnchors out = new QuestionAnchors();
		int count = 0;
		for (Object[] f : found) {
			if (count >= MAX_QUESTION_ANCHORS) {
				break;
			}
			String method = (String) f[1];
			String scope = (String) f[2];
			if (scope == null) {
				if (out.global.contains(method)) {
					continue;
				}
				out.global.add(method);
			} else {
				List<String> list = out.scoped.get(scope);
				if (list != null && list.contains(method)) {
					continue;
				}
				if (list != null) {
					list.add(method);
				} else {
					out.scoped.put(scope, new ArrayList<>(List.of(method)));
				}
			}
			count++;
		}
		return out;
	}

	private static Map<String, List<String>> fieldTypesOf(String content, String ext) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		String text = content.length() > MAX_REF_SCAN_CHARS ? content.substring(0, MAX_REF_SCAN_CHARS) : content;
		List<Object[]> patterns = new ArrayList<>();
		if (ext.equals("java") || ext.equals("cs") || ext.equals("groovy")) {
			patterns.add(new Object[] { JAVA_FIELD_RE, 2, 1 });
		}
		if (ext.equals("kt") || ext.equals("scala") || ext.equals("ts") || ext.equals("tsx") || ext.equals("js") || ext.equals("jsx")) {
			patterns.add(new Object[] { COLON_FIELD_RE, 1, 2 });
		}
		if (ext.equals("ts") || ext.equals("tsx") || ext.equals("js") || ext.equals("jsx")) {
			patterns.add(new Object[] { INJECT_FIELD_RE, 1, 2 });
		}
		for (Object[] p : patterns) {
			Matcher m = ((Pattern) p[0]).matcher(text);
			while (m.find()) {
				String field = m.group((Integer) p[1]);
				String key = normKey(m.group((Integer) p[2]));
				List<String> targets = out.get(field);
				if (targets == null) {
					if (out.size() >= MAX_FIELD_TYPES) {
						continue;
					}
					targets = new ArrayList<>();
					out.put(field, targets);
				}
				if (!targets.contains(key)) {
					targets.add(key);
				}
				if (!key.endsWith("impl") && !targets.contains(key + "impl")) {
					targets.add(key + "impl");
				}
			}
		}
		return out;
	}

	private static List<CallSite> callsOf(String[] lines, Map<String, List<String>> fields) {
		List<CallSite> out = new ArrayList<>();
		if (fields.isEmpty()) {
			return out;
		}
		int chars = 0;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			chars += line.length() + 1;
			if (chars > MAX_REF_SCAN_CHARS) {
				break;
			}
			if (line.indexOf('.') < 0 || line.indexOf('(') < 0) {
				continue;
			}
			String trimmed = line.trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
				continue;
			}
			Matcher m = CALL_RE.matcher(line);
			int from = 0;
			while (from <= line.length() && m.find(from)) {
				List<String> targets = fields.get(m.group(1));
				if (targets != null) {
					CallSite c = new CallSite();
					c.line = i;
					c.method = m.group(2);
					c.targets = targets;
					out.add(c);
				}
				from = Math.max(m.start() + 1, m.start() + m.group().length() - 1);
			}
		}
		return out;
	}

	/** Quita literales de texto y comentarios de linea (sin regex recursiva). */
	private static String cleanLine(String line) {
		StringBuilder sb = new StringBuilder(line.length());
		int n = line.length();
		int i = 0;
		while (i < n) {
			char q = line.charAt(i);
			if (q == '"' || q == '\'' || q == '`') {
				int j = i + 1;
				int end = -1;
				while (j < n) {
					char c = line.charAt(j);
					if (c == '\\' && j + 1 < n) {
						j += 2;
						continue;
					}
					if (c == q) {
						end = j;
						break;
					}
					j++;
				}
				if (end >= 0) {
					sb.append("\"\"");
					i = end + 1;
					continue;
				}
			}
			sb.append(q);
			i++;
		}
		String s = sb.toString();
		int c = s.indexOf("//");
		return c >= 0 ? s.substring(0, c) : s;
	}

	private static Integer braceBody(String[] lines, int start) {
		int depth = 0;
		boolean opened = false;
		int last = Math.min(lines.length - 1, start + ANCHOR_MAX_LINES - 1);
		for (int i = start; i <= last; i++) {
			String clean = cleanLine(lines[i]);
			for (int k = 0; k < clean.length(); k++) {
				char ch = clean.charAt(k);
				if (ch == '{') {
					depth++;
					opened = true;
				} else if (ch == '}') {
					depth--;
					if (opened && depth <= 0) {
						return i;
					}
				} else if (ch == ';' && !opened) {
					return null;
				}
			}
		}
		return opened ? Integer.valueOf(last) : null;
	}

	private static int signatureEnd(String[] lines, int start) {
		for (int i = start; i < Math.min(lines.length, start + 6); i++) {
			String clean = cleanLine(lines[i]);
			int semi = clean.indexOf(';');
			int brace = clean.indexOf('{');
			if (brace >= 0 && (semi < 0 || brace < semi)) {
				return -1;
			}
			if (semi >= 0) {
				return i;
			}
		}
		return -1;
	}

	private static final Pattern PREFIX_BAD = Pattern.compile("[=(,+!&|]");
	private static final Pattern ANGLE = Pattern.compile("<[^<>]*>");

	private static MethodLocation locateMethod(String[] lines, String method, String ext) {
		if (!BRACE_EXTENSIONS.contains(ext)) {
			return null;
		}
		String name = Pattern.quote(method);
		Pattern call = Pattern.compile("(^|[^.\\w$])" + name + "\\s*(<[^>]*>)?\\s*\\(");
		Pattern assigned = Pattern.compile("(^|[^.\\w$])" + name + "\\s*[=:]\\s*(async\\s*)?(function\\b|\\(|[\\w$]+\\s*=>)");
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (!line.contains(method) || DECL_STATEMENT.matcher(line).find()) {
				continue;
			}
			Matcher m = call.matcher(line);
			boolean ok = m.find();
			if (!ok) {
				m = assigned.matcher(line);
				ok = m.find();
			}
			if (!ok) {
				continue;
			}
			String prefix = ANNOTATION.matcher(line.substring(0, m.start() + m.group(1).length())).replaceAll(" ");
			for (int n = 0; n < 3; n++) {
				prefix = ANGLE.matcher(prefix).replaceAll("");
			}
			if (PREFIX_BAD.matcher(prefix).find()) {
				continue;
			}
			Integer body = braceBody(lines, i);
			if (body != null) {
				MethodLocation loc = new MethodLocation();
				loc.decl = i;
				loc.end = body;
				return loc;
			}
			if (!prefix.trim().isEmpty()) {
				int signature = signatureEnd(lines, i);
				if (signature >= 0) {
					MethodLocation loc = new MethodLocation();
					loc.decl = i;
					loc.end = signature;
					return loc;
				}
			}
		}
		return null;
	}

	private static int findDeclaration(String[] lines, String method, String ext) {
		MethodLocation loc = locateMethod(lines, method, ext);
		return loc == null ? -1 : loc.decl;
	}

	private static final Pattern ENDS_SEP = Pattern.compile("[;{}]$");

	private static Range0 anchorWindow(String[] lines, String method, String ext) {
		MethodLocation found = locateMethod(lines, method, ext);
		if (found == null) {
			return null;
		}
		int start = found.decl;
		for (int lead = 0; start > 0 && lead < ANCHOR_LEAD_LINES; lead++) {
			String previous = lines[start - 1].trim();
			if (previous.isEmpty() || ENDS_SEP.matcher(previous).find()) {
				break;
			}
			start--;
		}
		return clampWindow(lines, new Range0(start, found.end), start);
	}

	private static boolean anyWindow(List<Planned> windows, int from, int s, int e) {
		for (int i = from; i < windows.size(); i++) {
			Planned w = windows.get(i);
			if (w.start <= s && w.end >= e) {
				return true;
			}
		}
		return false;
	}

	private static List<Frag> fragmentsOf(String path, String content, List<String> terms, List<int[]> ranges, List<String> anchors, Integer focusMinTerms) {
		String masked = SecretMasker.maskConfigSecrets(path, content);
		if (PRIVATE_KEY.matcher(masked).find()) {
			return new ArrayList<>();
		}
		String[] lines = masked.split("\r?\n", -1);
		String ext = Guards.codeExtension(baseName(path));
		boolean useAnchors = !anchors.isEmpty() && BRACE_EXTENSIONS.contains(ext);
		boolean focusOn = focusMinTerms != null && !terms.isEmpty();
		int minTerms = focusMinTerms == null ? 0 : focusMinTerms;
		List<List<Integer>> lineTerms = new ArrayList<>();
		int[] neigh = new int[lines.length];
		if (focusOn) {
			for (String line : lines) {
				String folded = foldAccents(line).toLowerCase(Locale.ROOT);
				List<Integer> hit = new ArrayList<>();
				for (int t = 0; t < terms.size(); t++) {
					if (folded.contains(terms.get(t))) {
						hit.add(t);
					}
				}
				lineTerms.add(hit);
			}
			for (int i = 0; i < lines.length; i++) {
				Set<Integer> distinct = new HashSet<>();
				for (int j = Math.max(0, i - FOCUS_SPAN); j <= Math.min(lines.length - 1, i + FOCUS_SPAN); j++) {
					distinct.addAll(lineTerms.get(j));
				}
				neigh[i] = distinct.size();
			}
		}
		final boolean fOn = focusOn;
		final int fMin = minTerms;
		java.util.function.IntPredicate isFocusLine = i -> fOn && !lineTerms.get(i).isEmpty() && neigh[i] >= fMin;
		if (masked.length() <= FULL_FILE_CHARS) {
			boolean anchored = false;
			if (useAnchors) {
				for (String name : anchors) {
					if (findDeclaration(lines, name, ext) >= 0) {
						anchored = true;
						break;
					}
				}
			}
			boolean focused = false;
			if (focusOn) {
				for (int i = 0; i < lines.length; i++) {
					if (isFocusLine.test(i)) {
						focused = true;
						break;
					}
				}
			}
			Frag f = new Frag();
			f.path = path;
			f.startLine = 1;
			f.endLine = lines.length;
			f.content = masked;
			f.priority = anchored || focused ? 3 : 2;
			f.anchor = anchored;
			f.focus = focused;
			List<Frag> one = new ArrayList<>();
			one.add(f);
			return one;
		}
		List<Planned> windows = new ArrayList<>();
		windows.add(new Planned(0, Math.min(lines.length, HEAD_LINES) - 1, 2, false, false));
		List<Integer> fixed = new ArrayList<>();
		for (int[] r : ranges) {
			fixed.add((r[0] + r[1]) / 2);
		}
		List<Integer> fixedUsed = fixed.subList(0, Math.min(WINDOWS_PER_FILE, fixed.size()));
		for (int center : fixedUsed) {
			Range0 w = around(lines, center);
			windows.add(new Planned(w.start, w.end, 3, false, false));
		}
		List<Range0> focusWindows = new ArrayList<>();
		List<Integer> focusCenters = new ArrayList<>();
		if (focusOn) {
			List<Integer> candidates = new ArrayList<>();
			for (int i = 0; i < lines.length; i++) {
				if (!isFocusLine.test(i)) {
					continue;
				}
				boolean inFixed = false;
				for (int k = 1; k < windows.size(); k++) {
					if (i >= windows.get(k).start && i <= windows.get(k).end) {
						inFixed = true;
						break;
					}
				}
				if (!inFixed) {
					candidates.add(i);
				}
			}
			candidates.sort((a, b) -> {
				int c = neigh[b] - neigh[a];
				if (c != 0) {
					return c;
				}
				c = lineTerms.get(b).size() - lineTerms.get(a).size();
				return c != 0 ? c : a - b;
			});
			for (int c : candidates) {
				if (focusCenters.size() >= FOCUS_WINDOWS) {
					break;
				}
				boolean far = true;
				for (int other : focusCenters) {
					if (Math.abs(other - c) <= FOCUS_RADIUS * 2) {
						far = false;
						break;
					}
				}
				if (!far) {
					continue;
				}
				Range0 w = clampWindow(lines, new Range0(Math.max(0, c - FOCUS_RADIUS), Math.min(lines.length - 1, c + FOCUS_RADIUS)), c);
				focusCenters.add(c);
				focusWindows.add(w);
				windows.add(new Planned(w.start, w.end, 3, false, true));
			}
		}
		List<Range0> anchoredWindows = new ArrayList<>();
		if (useAnchors) {
			for (String name : anchors.subList(0, Math.min(MAX_ANCHOR_METHODS, anchors.size()))) {
				if (anchoredWindows.size() >= MAX_ANCHOR_WINDOWS) {
					break;
				}
				Range0 w = anchorWindow(lines, name, ext);
				if (w == null) {
					continue;
				}
				if (anyWindow(windows, 1, w.start, w.end)) {
					continue;
				}
				windows.add(new Planned(w.start, w.end, 3, true, false));
				anchoredWindows.add(w);
			}
		}
		List<Range0> localWindows = new ArrayList<>();
		if (!focusCenters.isEmpty() && BRACE_EXTENSIONS.contains(ext)) {
			Set<String> tried = new HashSet<>();
			for (int c : focusCenters) {
				if (localWindows.size() >= MAX_LOCAL_ANCHORS) {
					break;
				}
				StringBuilder near = new StringBuilder();
				for (int i = Math.max(0, c - FOCUS_SPAN); i < Math.min(lines.length, c + FOCUS_SPAN + 1); i++) {
					if (near.length() > 0) {
						near.append('\n');
					}
					near.append(lines[i]);
				}
				Matcher m = LOCAL_CALL.matcher(near);
				while (m.find()) {
					if (localWindows.size() >= MAX_LOCAL_ANCHORS) {
						break;
					}
					String name = m.group(1);
					if (!tried.add(name)) {
						continue;
					}
					Range0 w = anchorWindow(lines, name, ext);
					if (w == null || (w.start <= c && w.end >= c)) {
						continue;
					}
					if (anyWindow(windows, 1, w.start, w.end)) {
						continue;
					}
					windows.add(new Planned(w.start, w.end, 3, true, false));
					localWindows.add(w);
				}
			}
		}
		int gaps = WINDOWS_PER_FILE - fixedUsed.size() - anchoredWindows.size();
		if (gaps > 0 && !terms.isEmpty()) {
			int[] scores = new int[lines.length];
			for (int i = 0; i < lines.length; i++) {
				String folded = foldAccents(lines[i]).toLowerCase(Locale.ROOT);
				int n = 0;
				for (String term : terms) {
					if (folded.contains(term)) {
						n++;
					}
				}
				scores[i] = n;
			}
			List<Integer> order = new ArrayList<>();
			for (int i = 0; i < lines.length; i++) {
				if (scores[i] > 0) {
					order.add(i);
				}
			}
			order.sort((a, b) -> scores[b] != scores[a] ? scores[b] - scores[a] : a - b);
			List<Integer> centers = new ArrayList<>();
			for (int index : order) {
				if (centers.size() >= gaps) {
					break;
				}
				if (inAny(anchoredWindows, index) || inAny(focusWindows, index) || inAny(localWindows, index)) {
					continue;
				}
				boolean ok = true;
				for (int c : fixed) {
					if (Math.abs(c - index) <= WINDOW_RADIUS * 2) {
						ok = false;
					}
				}
				for (int c : centers) {
					if (Math.abs(c - index) <= WINDOW_RADIUS * 2) {
						ok = false;
					}
				}
				if (ok) {
					centers.add(index);
				}
			}
			for (int center : centers) {
				Range0 w = around(lines, center);
				windows.add(new Planned(w.start, w.end, 1, false, false));
			}
		}
		windows.sort((a, b) -> a.start != b.start ? a.start - b.start : b.priority - a.priority);
		List<Planned> merged = new ArrayList<>();
		for (Planned w : windows) {
			Planned last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			if (last != null && w.start <= last.end) {
				last.priority = Math.max(last.priority, w.priority);
				if (w.focus) {
					last.focus = true;
				}
				if (w.end <= last.end) {
					last.anchor = last.anchor || w.anchor;
				} else {
					place(merged, lines, new Planned(last.end + 1, w.end, w.priority, w.anchor, w.focus));
				}
			} else {
				place(merged, lines, new Planned(w.start, w.end, w.priority, w.anchor, w.focus));
			}
		}
		List<Frag> out = new ArrayList<>();
		for (Planned w : merged) {
			Frag f = new Frag();
			f.path = path;
			f.startLine = w.start + 1;
			f.endLine = w.end + 1;
			f.content = String.join("\n", Arrays.asList(lines).subList(w.start, Math.min(lines.length, w.end + 1)));
			f.priority = w.priority;
			f.anchor = w.anchor;
			f.focus = w.focus;
			out.add(f);
		}
		return out;
	}

	private static boolean inAny(List<Range0> list, int index) {
		for (Range0 w : list) {
			if (index >= w.start && index <= w.end) {
				return true;
			}
		}
		return false;
	}

	private static Range0 around(String[] lines, int center) {
		return clampWindow(lines, new Range0(Math.max(0, center - WINDOW_RADIUS), Math.min(lines.length - 1, center + WINDOW_RADIUS)), center);
	}

	private static void place(List<Planned> merged, String[] lines, Planned w) {
		Planned last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
		if (last != null && w.start == last.end + 1 && w.priority == last.priority && lengthOf(lines, last.start, w.end) <= MAX_FRAGMENT_CHARS) {
			last.end = w.end;
			last.anchor = last.anchor || w.anchor;
			if (w.focus) {
				last.focus = true;
			}
		} else {
			merged.add(w);
		}
	}

	/* ------------------------------ archivo activo ------------------------------ */

	private static final class ActiveInfo {
		ContextActive active;
		ContextSelection selection;
		ProjectFile file;
	}

	private static ActiveInfo activeOf(List<ProjectFile> files, EditorHint hint) {
		if (hint == null || hint.fileId == null) {
			return null;
		}
		ProjectFile file = null;
		for (ProjectFile f : files) {
			if (f.id.equals(hint.fileId)) {
				file = f;
				break;
			}
		}
		if (file == null || !allowed(file)) {
			return null;
		}
		String raw = hint.text != null ? hint.text : WorkspaceFiles.readText(file, READ_BYTES);
		if (raw == null) {
			return null;
		}
		String text = SecretMasker.maskConfigSecrets(file.path, raw);
		if (PRIVATE_KEY.matcher(text).find()) {
			return null;
		}
		String[] lines = text.split("\r?\n", -1);
		int start = 0;
		int end = lines.length - 1;
		if (text.length() > ACTIVE_FULL_CHARS) {
			int center = (hint.selStartLine + hint.selEndLine) / 2;
			start = Math.max(0, center - ACTIVE_RADIUS);
			end = Math.min(lines.length - 1, center + ACTIVE_RADIUS);
		}
		ActiveInfo info = new ActiveInfo();
		info.file = file;
		ContextActive active = new ContextActive();
		active.path = file.path;
		active.startLine = start + 1;
		active.endLine = end + 1;
		active.totalLines = lines.length;
		active.content = String.join("\n", Arrays.asList(lines).subList(Math.max(0, start), Math.min(lines.length, end + 1)));
		info.active = active;
		if (hint.hasSelection()) {
			String selText = J.truncate(SecretMasker.maskSensitiveText(hint.selectedText), MAX_SELECTION_CHARS);
			if (!selText.trim().isEmpty() && !PRIVATE_KEY.matcher(selText).find()) {
				ContextSelection sel = new ContextSelection();
				sel.startLine = hint.selStartLine + 1;
				sel.endLine = hint.selEndLine + 1;
				sel.text = selText;
				info.selection = sel;
			}
		}
		return info;
	}

	/* ------------------------------ puente HTTP front -> back ------------------------------ */

	private static final class RouteDecl {
		String path;
		String httpMethod;
		List<String> exprs;
		String handler;
		int line;
	}

	private static final class HttpCall {
		String httpMethod;
		List<String> segments;
		boolean partial;
		int line;
	}

	private static final class BridgeRoute {
		String path;
		String httpMethod;
		List<String> segments;
		String handler;
	}

	private static final List<String> ROUTE_EXTENSIONS = List.of("java", "kt", "ts", "js", "py");
	private static final int MAX_ROUTE_DECLS = 2000;
	private static final int MAX_ROUTE_LINES = 6000;
	private static final Pattern ROUTE_MARKERS = Pattern.compile("@(?:Get|Post|Put|Patch|Delete|Request)Mapping\\b|@Controller\\s*\\(|\\b(?:router|app|\\w{1,40}Router)\\s*\\.\\s*(?:get|post|put|patch|delete|all)\\s*\\(|@\\w{1,40}\\s*\\.\\s*(?:get|post|put|patch|delete|route)\\s*\\(");
	private static final Pattern SPRING_CONTROLLER = Pattern.compile("@(?:Rest)?Controller\\b");
	private static final Pattern SPRING_MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete|Request)Mapping\\b");
	private static final Pattern SPRING_HANDLER = Pattern.compile("(?:public|protected|private|fun)\\b[^=;(){]{0,200}?\\b([A-Za-z_]\\w*)\\s*\\(");
	private static final Pattern NEST_CONTROLLER = Pattern.compile("@Controller\\s*\\(\\s*(?:(['\"`])([^'\"`\\n]{0,200})\\1)?");
	private static final Pattern NEST_MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete|All)\\s*\\(\\s*(?:(['\"`])([^'\"`\\n]{0,200})\\2)?");
	private static final Pattern NEST_HANDLER = Pattern.compile("^\\s*(?:(?:public|private|protected|async|static)\\s+)*([A-Za-z_$][\\w$]{0,60})\\s*\\(");
	private static final Pattern EXPRESS_ROUTE = Pattern.compile("\\b(?:router|app|\\w{1,40}Router)\\s*\\.\\s*(get|post|put|patch|delete|all)\\s*\\(\\s*(['\"`])([^'\"`\\n]{0,300})\\2");
	private static final Pattern PY_ROUTE = Pattern.compile("@\\w{1,40}\\s*\\.\\s*(get|post|put|patch|delete|route)\\s*\\(\\s*(['\"])([^'\"\\n]{0,300})\\2([^\\n]{0,200})");
	private static final Pattern PY_HANDLER = Pattern.compile("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w{0,60})");
	private static final Pattern JAVA_CONST = Pattern.compile("\\bstatic\\s+final\\s+String\\s+([A-Za-z_]\\w{0,80})\\s*=\\s*([^;\\n]{1,300});");
	private static final Pattern JAVA_IFACE_CONST = Pattern.compile("\\bString\\s+([A-Z][A-Z0-9_]{0,80})\\s*=\\s*([^;\\n]{1,300});");
	private static final Pattern KOTLIN_CONST = Pattern.compile("\\bconst\\s+val\\s+([A-Za-z_]\\w{0,80})\\s*(?::\\s*String)?\\s*=\\s*([^\\n]{1,300})");
	private static final Pattern TS_CONST = Pattern.compile("\\bexport\\s+const\\s+([A-Za-z_$][\\w$]{0,80})\\s*(?::\\s*string)?\\s*=\\s*([^;\\n]{1,300})");
	private static final Pattern HTTP_CLIENT_MARKERS = Pattern.compile("HttpClient|\\bfetch\\s*\\(|axios|\\bhttp\\s*\\.|\\$http");
	private static final Pattern HTTP_CALL_LITERAL = Pattern.compile("\\.\\s*(get|post|put|patch|delete|request)\\s*(?:<[^()]{0,200}>)?\\s*\\(\\s*(['\"`])((?:(?!\\2)[\\s\\S]){1,300})\\2");
	private static final Pattern HTTP_CALL_PLUS = Pattern.compile("\\.\\s*(get|post|put|patch|delete|request)\\s*(?:<[^()]{0,200}>)?\\s*\\(\\s*((?:this\\.)?[A-Za-z_$][\\w$]{0,60})\\s*\\+\\s*(['\"`])((?:(?!\\3)[\\s\\S]){1,300})\\3");
	private static final Pattern HTTP_CALL_IDENT = Pattern.compile("\\.\\s*(get|post|put|patch|delete|request)\\s*(?:<[^()]{0,200}>)?\\s*\\(\\s*(?:this\\.)?([A-Za-z_$][\\w$]{0,60})\\s*[,)]");
	private static final Pattern FETCH_CALL = Pattern.compile("\\bfetch\\s*\\(\\s*(['\"`])((?:(?!\\1)[\\s\\S]){1,300})\\1");
	private static final Pattern FIELD_LITERAL = Pattern.compile("^\\s*(?:(?:export|private|public|protected|readonly|static|const|let|var)\\s+)*([A-Za-z_$][\\w$]{0,60})\\s*(?::[^=;]{1,80})?=\\s*(['\"`])((?:(?!\\2).){0,300})\\2");
	private static final Pattern FIELD_PLUS = Pattern.compile("^\\s*(?:(?:export|private|public|protected|readonly|static|const|let|var)\\s+)*([A-Za-z_$][\\w$]{0,60})\\s*(?::[^=;]{1,80})?=\\s*([A-Za-z_$][\\w$.]{0,80})\\s*\\+\\s*(['\"`])((?:(?!\\3).){0,300})\\3");
	private static final int MAX_HTTP_MATCHES = 200;

	private static String verbOf(String name) {
		String upper = name.toUpperCase(Locale.ROOT);
		return List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(upper) ? upper : "ANY";
	}

	private static boolean isCommentLine(String line) {
		String t = line.stripLeading();
		return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("#");
	}

	private static final class Annotation {
		String text;
		int end;
		String rest;
	}

	private static final Pattern ANN_NAME = Pattern.compile("^@\\w+\\s*");

	private static Annotation annotationAt(String[] lines, int start, int from) {
		StringBuilder text = new StringBuilder();
		int depth = 0;
		boolean opened = false;
		Annotation res = new Annotation();
		for (int i = start; i < Math.min(lines.length, start + 6); i++) {
			String part = i == start ? lines[i].substring(Math.min(from, lines[i].length())) : lines[i];
			if (part.length() > 1000) {
				part = part.substring(0, 1000);
			}
			if (i == start) {
				Matcher an = ANN_NAME.matcher(part);
				boolean has = an.find();
				if (!has || an.end() >= part.length() || part.charAt(an.end()) != '(') {
					res.text = has ? an.group() : part;
					res.end = i;
					res.rest = has ? part.substring(an.end()) : "";
					return res;
				}
			}
			for (int c = 0; c < part.length(); c++) {
				char ch = part.charAt(c);
				text.append(ch);
				if (ch == '(') {
					depth++;
					opened = true;
				} else if (ch == ')') {
					depth--;
					if (opened && depth <= 0) {
						res.text = text.toString();
						res.end = i;
						res.rest = part.substring(c + 1);
						return res;
					}
				}
			}
			text.append('\n');
		}
		res.text = text.toString();
		res.end = Math.min(lines.length - 1, start + 5);
		res.rest = "";
		return res;
	}

	private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\s*\\.\\s*(GET|POST|PUT|PATCH|DELETE)\\b");
	private static final Pattern NAMED_ARG = Pattern.compile("\\b(?:value|path)\\s*=\\s*[{\\[]?\\s*");
	private static final Pattern ASSIGN_START = Pattern.compile("^[A-Za-z_]\\w*\\s*=(?!=)");
	private static final String EXPR_ATOM = "(?:\"[^\"\\n]{0,300}\"|'[^'\\n]{0,300}'|[A-Za-z_$][\\w$.]{0,120})";
	private static final Pattern EXPR_START = Pattern.compile("^\\s*" + EXPR_ATOM + "(?:\\s*\\+\\s*" + EXPR_ATOM + "){0,8}");

	private static String takeExpr(String text) {
		Matcher m = EXPR_START.matcher(text);
		return m.find() ? m.group().trim() : "";
	}

	private static String[] mappingArgs(String annotation) {
		int open = annotation.indexOf('(');
		int close = annotation.lastIndexOf(')');
		if (open < 0 || close < open) {
			return new String[] { "", null };
		}
		String inner = annotation.substring(open + 1, close);
		Matcher rm = REQUEST_METHOD.matcher(inner);
		String method = rm.find() ? rm.group(1) : null;
		Matcher named = NAMED_ARG.matcher(inner);
		if (named.find()) {
			return new String[] { takeExpr(inner.substring(named.end())), method };
		}
		String first = inner.stripLeading().replaceFirst("^[{\\[]\\s*", "");
		if (first.isEmpty() || ASSIGN_START.matcher(first).find()) {
			return new String[] { "", method };
		}
		return new String[] { takeExpr(first), method };
	}

	private static String jsq(String s) {
		return J.GSON.toJson(s == null ? "" : s);
	}

	private static List<String> splitLines(String content, int max) {
		String[] all = content.split("\r?\n", -1);
		return new ArrayList<>(Arrays.asList(all).subList(0, Math.min(max, all.length)));
	}

	private static List<RouteDecl> extractRouteDecls(String path, String content, String ext) {
		List<RouteDecl> out = new ArrayList<>();
		if (!ROUTE_MARKERS.matcher(content).find()) {
			return out;
		}
		String[] lines = splitLines(content, MAX_ROUTE_LINES).toArray(new String[0]);
		java.util.function.Consumer<RouteDecl> push = d -> {
			if (out.size() < 200) {
				d.path = path;
				out.add(d);
			}
		};
		if (ext.equals("java") || ext.equals("kt")) {
			if (!SPRING_CONTROLLER.matcher(content).find()) {
				return out;
			}
			Pattern classDecl = Pattern.compile("\\b(?:class|interface|object)\\s+[A-Za-z_]\\w*");
			int classIdx = -1;
			for (int i = 0; i < lines.length; i++) {
				if (!isCommentLine(lines[i]) && classDecl.matcher(lines[i]).find()) {
					classIdx = i;
					break;
				}
			}
			if (classIdx < 0) {
				return out;
			}
			String classExpr = null;
			Pattern reqMap = Pattern.compile("@RequestMapping\\b");
			for (int i = 0; i <= classIdx && classExpr == null; i++) {
				if (isCommentLine(lines[i])) {
					continue;
				}
				Matcher m = reqMap.matcher(lines[i]);
				if (m.find()) {
					classExpr = mappingArgs(annotationAt(lines, i, m.start()).text)[0];
				}
			}
			Pattern classWord = Pattern.compile("\\b(?:class|interface|object)\\s");
			for (int i = classIdx; i < lines.length; i++) {
				if (isCommentLine(lines[i])) {
					continue;
				}
				Matcher m = SPRING_MAPPING.matcher(lines[i]);
				if (!m.find()) {
					continue;
				}
				if (i == classIdx) {
					Matcher cw = classWord.matcher(lines[i]);
					int pos = cw.find() ? cw.start() : -1;
					if (m.start() < Math.max(pos, 0)) {
						continue;
					}
				}
				Annotation ann = annotationAt(lines, i, m.start());
				String[] args = mappingArgs(ann.text);
				String handler = null;
				for (int j = ann.end; j < Math.min(lines.length, ann.end + 9); j++) {
					String candidate = j == ann.end ? ann.rest : lines[j];
					if (j > ann.end && SPRING_MAPPING.matcher(candidate).find()) {
						break;
					}
					Matcher h = SPRING_HANDLER.matcher(candidate);
					if (h.find()) {
						handler = h.group(1);
						break;
					}
				}
				RouteDecl d = new RouteDecl();
				d.httpMethod = m.group(1).equals("Request") ? (args[1] != null ? args[1] : "ANY") : m.group(1).toUpperCase(Locale.ROOT);
				d.exprs = classExpr == null ? List.of(args[0]) : List.of(classExpr, args[0]);
				d.handler = handler;
				d.line = i;
				push.accept(d);
			}
			return out;
		}
		if (ext.equals("ts") || ext.equals("js")) {
			if (content.contains("@angular/")) {
				return out;
			}
			Matcher ctrl = NEST_CONTROLLER.matcher(content);
			if (ctrl.find()) {
				String prefix = jsq(ctrl.group(2));
				for (int i = 0; i < lines.length; i++) {
					Matcher m = NEST_MAPPING.matcher(lines[i]);
					if (!m.find() || isCommentLine(lines[i])) {
						continue;
					}
					String handler = null;
					for (int j = i + 1; j < Math.min(lines.length, i + 7); j++) {
						if (lines[j].stripLeading().startsWith("@")) {
							continue;
						}
						Matcher h = NEST_HANDLER.matcher(lines[j]);
						if (h.find()) {
							handler = h.group(1);
						}
						break;
					}
					RouteDecl d = new RouteDecl();
					d.httpMethod = verbOf(m.group(1));
					d.exprs = List.of(prefix, jsq(m.group(3)));
					d.handler = handler;
					d.line = i;
					push.accept(d);
				}
				return out;
			}
			for (int i = 0; i < lines.length; i++) {
				Matcher m = EXPRESS_ROUTE.matcher(lines[i]);
				if (m.find() && !isCommentLine(lines[i])) {
					RouteDecl d = new RouteDecl();
					d.httpMethod = verbOf(m.group(1));
					d.exprs = List.of(jsq(m.group(3)));
					d.line = i;
					push.accept(d);
				}
			}
			return out;
		}
		if (ext.equals("py")) {
			Pattern pyMethods = Pattern.compile("methods\\s*=\\s*\\[\\s*['\"](GET|POST|PUT|PATCH|DELETE)", Pattern.CASE_INSENSITIVE);
			for (int i = 0; i < lines.length; i++) {
				Matcher m = PY_ROUTE.matcher(lines[i]);
				if (!m.find()) {
					continue;
				}
				String httpMethod = verbOf(m.group(1));
				if (m.group(1).equals("route")) {
					Matcher pm = pyMethods.matcher(m.group(4));
					httpMethod = pm.find() ? pm.group(1).toUpperCase(Locale.ROOT) : "ANY";
				}
				String handler = null;
				for (int j = i + 1; j < Math.min(lines.length, i + 6); j++) {
					Matcher h = PY_HANDLER.matcher(lines[j]);
					if (h.find()) {
						handler = h.group(1);
						break;
					}
				}
				RouteDecl d = new RouteDecl();
				d.httpMethod = httpMethod;
				d.exprs = List.of(jsq(m.group(3)));
				d.handler = handler;
				d.line = i;
				push.accept(d);
			}
		}
		return out;
	}

	private static final Pattern HAS_QUOTE = Pattern.compile("[\"'`]");

	private static Map<String, String> extractStringConstants(String content, String ext, String fileBase) {
		Map<String, String> out = new LinkedHashMap<>();
		List<Pattern> patterns = ext.equals("java") ? List.of(JAVA_CONST, JAVA_IFACE_CONST)
			: ext.equals("kt") ? List.of(KOTLIN_CONST) : (ext.equals("ts") || ext.equals("js")) ? List.of(TS_CONST) : List.of();
		for (Pattern p : patterns) {
			Matcher m = p.matcher(content);
			int found = 0;
			while (m.find() && found < 200) {
				found++;
				String expr = m.group(2).trim();
				if (!HAS_QUOTE.matcher(expr).find() || !(expr.contains("/") || expr.contains("+"))) {
					continue;
				}
				out.put(fileBase + "." + m.group(1), expr);
				if (!out.containsKey(m.group(1))) {
					out.put(m.group(1), expr);
				}
			}
		}
		return out;
	}

	private static final Pattern IDENT_PATH = Pattern.compile("^[A-Za-z_$][\\w$.]{0,120}$");

	private static String resolveRouteExpr(String expr, Map<String, String> constants, int depth) {
		String text = expr.trim();
		if (text.isEmpty()) {
			return "";
		}
		if (depth > 3) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (String rawPart : text.split("\\+", -1)) {
			String part = rawPart.trim().replaceFirst("^\\(+", "").replaceFirst("\\)+$", "").trim();
			if (part.isEmpty()) {
				return null;
			}
			char quote = part.charAt(0);
			if ((quote == '"' || quote == '\'' || quote == '`') && part.length() >= 2 && part.endsWith(String.valueOf(quote))) {
				out.append(part, 1, part.length() - 1);
				continue;
			}
			if (!IDENT_PATH.matcher(part).find()) {
				return null;
			}
			String[] ids = part.split("\\.");
			String last = ids[ids.length - 1];
			String found = constants.get(part);
			if (found == null) {
				found = constants.get(ids[0] + "." + last);
			}
			if (found == null) {
				found = constants.get(last);
			}
			if (found == null) {
				return null;
			}
			String value = resolveRouteExpr(found, constants, depth + 1);
			if (value == null) {
				return null;
			}
			out.append(value);
		}
		return out.toString();
	}

	private static List<String> routeSegments(String path) {
		List<String> out = new ArrayList<>();
		for (String s : path.split("/")) {
			String t = s.trim();
			if (t.isEmpty()) {
				continue;
			}
			if (t.contains("{") || t.contains("}") || t.startsWith(":") || t.equals("*") || t.contains("${")) {
				out.add("*");
			} else {
				out.add(t.toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	private static final Pattern FIELD_REF = Pattern.compile("\\$\\{\\s*(?:this\\.)?([A-Za-z_$][\\w$]{0,60})\\s*\\}");

	private static String expandFields(String template, Map<String, String> fields, int depth) {
		if (depth >= 2) {
			return template;
		}
		return J.replace(FIELD_REF, template, m -> {
			String value = fields.get(m.group(1));
			return value == null ? m.group() : expandFields(value, fields, depth + 1);
		});
	}

	private static final Pattern LEAD_VARS = Pattern.compile("^(?:\\$\\{[^}]{0,120}\\})+");
	private static final Pattern URL_HOST = Pattern.compile("^https?://[^/]{1,200}", Pattern.CASE_INSENSITIVE);
	private static final Pattern REL_PATH = Pattern.compile("^\\.{1,2}/");

	private static List<HttpCall> extractHttpCalls(String content) {
		List<HttpCall> calls = new ArrayList<>();
		if (!HTTP_CLIENT_MARKERS.matcher(content).find()) {
			return calls;
		}
		Map<String, String> fields = new HashMap<>();
		for (String line : splitLines(content, MAX_ROUTE_LINES)) {
			if (line.length() > 600 || line.indexOf('=') < 0) {
				continue;
			}
			Matcher lit = FIELD_LITERAL.matcher(line);
			if (lit.find()) {
				fields.putIfAbsent(lit.group(1), lit.group(3));
				continue;
			}
			Matcher plus = FIELD_PLUS.matcher(line);
			if (plus.find()) {
				fields.putIfAbsent(plus.group(1), "${" + plus.group(2) + "}" + plus.group(4));
			}
		}
		Set<String> seen = new HashSet<>();
		java.util.function.BiConsumer<String[], Integer> add = (args, index) -> {
			if (calls.size() >= MAX_HTTP_CALLS) {
				return;
			}
			String value = expandFields(args[1], fields, 0);
			boolean partial = false;
			Matcher lead = LEAD_VARS.matcher(value);
			if (lead.find()) {
				value = value.substring(lead.end());
				partial = true;
			}
			value = URL_HOST.matcher(value).replaceFirst("");
			int cut = -1;
			for (int i = 0; i < value.length(); i++) {
				if (value.charAt(i) == '?' || value.charAt(i) == '#') {
					cut = i;
					break;
				}
			}
			if (cut >= 0) {
				value = value.substring(0, cut);
			}
			if (!value.contains("/") || REL_PATH.matcher(value).find()) {
				return;
			}
			List<String> segments = routeSegments(value);
			if (segments.isEmpty()) {
				return;
			}
			String httpMethod = verbOf(args[0]);
			String key = httpMethod + "|" + String.join("/", segments);
			if (!seen.add(key)) {
				return;
			}
			HttpCall call = new HttpCall();
			call.httpMethod = httpMethod;
			call.segments = segments;
			call.partial = partial;
			int n = 0;
			for (int i = content.indexOf('\n'); i >= 0 && i < index; i = content.indexOf('\n', i + 1)) {
				n++;
			}
			call.line = n;
			calls.add(call);
		};
		scan(HTTP_CALL_LITERAL, content, calls, m -> add.accept(new String[] { m.group(1), m.group(3) }, m.start()));
		scan(HTTP_CALL_PLUS, content, calls, m -> add.accept(new String[] { m.group(1), "${" + m.group(2).replaceFirst("^this\\.", "") + "}" + m.group(4) }, m.start()));
		scan(HTTP_CALL_IDENT, content, calls, m -> {
			String value = fields.get(m.group(2));
			if (value != null) {
				add.accept(new String[] { m.group(1), value }, m.start());
			}
		});
		scan(FETCH_CALL, content, calls, m -> add.accept(new String[] { "request", m.group(2) }, m.start()));
		return calls;
	}

	private static void scan(Pattern regex, String content, List<HttpCall> calls, Consumer<Matcher> handle) {
		Matcher m = regex.matcher(content);
		int n = 0;
		while (m.find() && n < MAX_HTTP_MATCHES && calls.size() < MAX_HTTP_CALLS) {
			n++;
			handle.accept(m);
		}
	}

	private static boolean same(String x, String y) {
		return x.equals(y) || x.equals("*") || y.equals("*");
	}

	private static boolean aligned(List<String> lng, List<String> shrt) {
		for (int i = 0; i < shrt.size(); i++) {
			if (!same(lng.get(lng.size() - shrt.size() + i), shrt.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static int matchScore(HttpCall front, BridgeRoute back) {
		List<String> f = front.segments;
		List<String> b = back.segments;
		if (f.isEmpty() || b.isEmpty()) {
			return 0;
		}
		int base = 0;
		boolean literal = false;
		if (f.size() == b.size() && aligned(f, b)) {
			base = 3;
			for (int i = 0; i < f.size(); i++) {
				if (!f.get(i).equals("*") && f.get(i).equals(b.get(i))) {
					literal = true;
				}
			}
		} else if (front.partial && b.size() > f.size() && aligned(b, f)) {
			base = 2;
			for (int i = 0; i < f.size(); i++) {
				if (!f.get(i).equals("*") && f.get(i).equals(b.get(b.size() - f.size() + i))) {
					literal = true;
				}
			}
		} else if (f.size() > b.size() && aligned(f, b)) {
			base = 2;
			for (int i = 0; i < b.size(); i++) {
				if (!b.get(i).equals("*") && b.get(i).equals(f.get(f.size() - b.size() + i))) {
					literal = true;
				}
			}
		}
		if (base == 0 || !literal) {
			return 0;
		}
		return base + (front.httpMethod.equals(back.httpMethod) ? 1 : 0);
	}

	/* ------------------------------- roles de carpeta ------------------------------- */

	private static final Pattern DATOS_NAME = Pattern.compile("(Repository|Repo|Dao|Entity)\\.(java|kt|ts|js|py|cs)$");
	private static final Pattern DATOS_DIR = Pattern.compile("(^|/)(repository|repositories|dao|entity|entities|migrations?|changelog)/", Pattern.CASE_INSENSITIVE);
	private static final Pattern BACK_CTRL = Pattern.compile("\\.controller\\.(ts|js)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern FRONT_TS = Pattern.compile("\\.(component|service|guard|interceptor|pipe|store)\\.ts$", Pattern.CASE_INSENSITIVE);

	/** Capa a la que pertenece una ruta (relativa a su carpeta) por nombre y extension; null si no encaja. */
	public static String layerOfPath(String path) {
		String name = baseName(path);
		String ext = Guards.codeExtension(name);
		if (ext.equals("sql") || ext.equals("prisma")) {
			return "datos";
		}
		if (DATOS_NAME.matcher(name).find()) {
			return "datos";
		}
		if (DATOS_DIR.matcher(path).find()) {
			return "datos";
		}
		if (List.of("java", "kt", "scala", "py", "cs", "go", "rb", "php").contains(ext) || BACK_CTRL.matcher(name).find()) {
			return "back";
		}
		if (List.of("html", "tsx", "jsx", "vue", "svelte", "css", "scss").contains(ext) || FRONT_TS.matcher(name).find()) {
			return "front";
		}
		return null;
	}

	private static final class Acc {
		Set<String> roles = new LinkedHashSet<>();
		Set<String> tech = new LinkedHashSet<>();
		boolean build;
		boolean jvm;
	}

	private static final Pattern FRONT_CFG = Pattern.compile("^(vite|next|nuxt|svelte|astro)\\.config\\.", Pattern.CASE_INSENSITIVE);
	private static final Pattern SVELTE_CFG = Pattern.compile("^svelte", Pattern.CASE_INSENSITIVE);
	private static final Pattern COMPONENT_TS = Pattern.compile("\\.component\\.ts$", Pattern.CASE_INSENSITIVE);
	private static final Pattern GRADLE = Pattern.compile("^build\\.gradle(\\.kts)?$");
	private static final Pattern CONTROLLER_JVM = Pattern.compile("Controller\\.(java|kt|cs)$");

	/** Roles (front, back, datos) y tecnologias de cada carpeta, en una sola pasada por los nombres de archivo. */
	public static List<FolderRole> detectFolderRoles(List<ProjectFile> files, List<String> mountIds, boolean prefixed) {
		Map<String, Acc> acc = new LinkedHashMap<>();
		for (String id : mountIds) {
			acc.put(id, new Acc());
		}
		for (ProjectFile file : files) {
			String mount = prefixed ? mountOf(file.path, true) : mountIds.get(0);
			Acc a = acc.get(mount);
			if (a == null) {
				continue;
			}
			String rel = relPath(file.path, prefixed);
			String name = file.name;
			String ext = Guards.codeExtension(name);
			if ("datos".equals(layerOfPath(rel))) {
				a.roles.add("datos");
			}
			if (name.equals("angular.json") || COMPONENT_TS.matcher(name).find()) {
				a.roles.add("front");
				a.tech.add("Angular");
			} else if (FRONT_CFG.matcher(name).find()) {
				a.roles.add("front");
				if (SVELTE_CFG.matcher(name).find()) {
					a.tech.add("Svelte");
				}
			} else if (ext.equals("tsx") || ext.equals("jsx")) {
				a.roles.add("front");
				a.tech.add("React");
			} else if (ext.equals("vue")) {
				a.roles.add("front");
				a.tech.add("Vue");
			} else if (ext.equals("svelte")) {
				a.roles.add("front");
				a.tech.add("Svelte");
			}
			if (name.equals("pom.xml") || GRADLE.matcher(name).find()) {
				a.roles.add("back");
				a.build = true;
			} else if (name.equals("manage.py")) {
				a.roles.add("back");
				a.tech.add("Django");
			} else if (name.equals("go.mod")) {
				a.roles.add("back");
				a.tech.add("Go");
			} else if (ext.equals("csproj")) {
				a.roles.add("back");
				a.tech.add(".NET");
			} else if (CONTROLLER_JVM.matcher(name).find()) {
				a.roles.add("back");
				if (ext.equals("cs")) {
					a.tech.add(".NET");
				} else {
					a.jvm = true;
				}
			} else if (BACK_CTRL.matcher(name).find() && name.toLowerCase(Locale.ROOT).endsWith(".controller.ts")) {
				a.roles.add("back");
				a.tech.add("NestJS");
			}
			if (ext.equals("java") || ext.equals("kt")) {
				a.jvm = true;
			}
		}
		List<FolderRole> out = new ArrayList<>();
		for (Map.Entry<String, Acc> e : acc.entrySet()) {
			Acc a = e.getValue();
			if (a.build && a.jvm) {
				a.tech.add("Spring Boot");
			}
			if (a.roles.isEmpty()) {
				continue;
			}
			FolderRole r = new FolderRole();
			r.mount = e.getKey();
			for (String role : List.of("front", "back", "datos")) {
				if (a.roles.contains(role)) {
					r.roles.add(role);
				}
			}
			r.tech = new ArrayList<>(a.tech);
			out.add(r);
		}
		return out;
	}

	/* --------------------------------- principal --------------------------------- */

	private static <K> void inc(Map<K, Integer> m, K key) {
		m.merge(key, 1, Integer::sum);
	}

	/** Contexto del proyecto para FLOW/PROPOSE. Lanza CancelledException si se cancela. */
	public static FlowContext collect(CollectOptions options) {
		CancelToken token = options.token;
		Consumer<String> report = msg -> {
			if (options.progress != null) {
				options.progress.accept(msg);
			}
		};
		report.accept("Leyendo el proyecto\u2026");
		Snapshot snapshot = WorkspaceFiles.listFiles();
		token.check();
		if (snapshot.mounts.isEmpty()) {
			throw new IllegalStateException("No hay carpetas abiertas en el workspace.");
		}
		int listed = snapshot.files.size();
		List<ProjectFile> candidates = snapshot.files.stream().filter(FlowContextCollector::allowed).collect(Collectors.toList());
		List<String> allMountIds = snapshot.mounts.stream().map(m -> m.id).collect(Collectors.toList());
		List<FolderRole> roles = detectFolderRoles(candidates, allMountIds, snapshot.mounts.size() > 1);
		int excluded = listed - candidates.size();
		List<Mount> mountInfos = snapshot.mounts;
		Mount only = null;
		if (options.folder != null && !options.folder.isEmpty() && snapshot.mounts.size() > 1) {
			for (Mount m : snapshot.mounts) {
				if (m.id.equals(options.folder)) {
					only = m;
				}
			}
		}
		if (only != null) {
			String prefix = only.id + "/";
			candidates = candidates.stream().filter(f -> f.path.startsWith(prefix)).collect(Collectors.toList());
			mountInfos = List.of(only);
		}
		List<ContextMount> mounts = new ArrayList<>();
		for (Mount m : mountInfos) {
			ContextMount cm = new ContextMount();
			cm.id = m.id;
			cm.prefix = m.path.isEmpty() ? "(raiz)" : m.path;
			cm.name = m.name;
			mounts.add(cm);
		}
		final boolean prefixed = snapshot.mounts.size() > 1;
		final boolean multi = mountInfos.size() > 1;
		final List<ProjectFile> cands = candidates;
		final List<Mount> mInfos = mountInfos;

		ActiveInfo activeInfo = options.useActiveEditor ? activeOf(cands, options.hint) : null;
		MentionResult mr = stripMountNames(options.question, multi ? mInfos : new ArrayList<>());
		String questionText = mr.text;
		List<String> ids = identifiersOf(questionText);
		List<String> termSources = new ArrayList<>();
		termSources.add(questionText);
		termSources.add(activeInfo != null && activeInfo.selection != null ? activeInfo.selection.text : "");
		termSources.add(activeInfo != null ? baseName(activeInfo.file.path) : "");
		List<String> terms = termsOf(termSources);

		Map<String, Double> scores = new HashMap<>();
		Map<String, List<ProjectFile>> forcedByKey = new HashMap<>();
		Set<String> forcedSeen = new HashSet<>();
		for (ProjectFile file : cands) {
			String rel = relPath(file.path, prefixed);
			Set<String> pathTokens = new HashSet<>(tokenize(rel));
			String foldedPath = foldAccents(rel).toLowerCase(Locale.ROOT);
			double score = 0;
			for (String term : terms) {
				if (pathTokens.contains(term) || foldedPath.contains(term)) {
					score += 3;
				}
			}
			score *= weightOf(file.path);
			String key = fileKey(file.name);
			if (ids.contains(key) && !isTestPath(file.path) && weightOf(file.path) >= 1) {
				String unique = mountOf(file.path, prefixed) + "|" + key + "|" + Guards.codeExtension(file.name);
				if (forcedSeen.add(unique)) {
					score += FORCED_BONUS;
					forcedByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
				}
			}
			scores.put(file.path, score);
		}
		List<ProjectFile> forced = new ArrayList<>();
		for (String id : ids) {
			forced.addAll(forcedByKey.getOrDefault(id, Collections.emptyList()));
		}
		if (forced.size() > MAX_FORCED) {
			forced = new ArrayList<>(forced.subList(0, MAX_FORCED));
		}

		Map<String, List<ProjectFile>> groups = new LinkedHashMap<>();
		for (ProjectFile file : cands) {
			groups.computeIfAbsent(mountOf(file.path, prefixed), k -> new ArrayList<>()).add(file);
		}
		List<List<ProjectFile>> groupList = new ArrayList<>();
		for (List<ProjectFile> group : groups.values()) {
			group.sort((a, b) -> {
				int c = Double.compare(scores.getOrDefault(b.path, 0.0), scores.getOrDefault(a.path, 0.0));
				return c != 0 ? c : Double.compare(weightOf(b.path), weightOf(a.path));
			});
			groupList.add(group);
		}
		int[] scanShares = fairShares(groupList.stream().mapToInt(List::size).toArray(), MAX_SCAN_FILES, Integer.MAX_VALUE);
		List<ProjectFile> toScan = new ArrayList<>();
		for (int i = 0; i < groupList.size(); i++) {
			toScan.addAll(groupList.get(i).subList(0, Math.min(scanShares[i], groupList.get(i).size())));
		}
		Set<String> scanSet = toScan.stream().map(f -> f.path).collect(Collectors.toCollection(HashSet::new));
		for (ProjectFile file : forced) {
			if (scanSet.add(file.path)) {
				toScan.add(file);
			}
		}
		List<RouteDecl> routeDecls = java.util.Collections.synchronizedList(new ArrayList<>());
		Map<String, String> constants = new java.util.concurrent.ConcurrentHashMap<>();
		if (!terms.isEmpty()) {
			report.accept("Buscando en el c\u00f3digo\u2026");
			final List<String> fTerms = terms;
			pool(toScan, SCAN_CONCURRENCY, file -> {
				String content = readText(file, MAX_SCAN_BYTES);
				if (content == null) {
					return;
				}
				String ext = Guards.codeExtension(file.name);
				if (ROUTE_EXTENSIONS.contains(ext) && !isTestPath(file.path)) {
					for (Map.Entry<String, String> e : extractStringConstants(content, ext, file.name.replaceFirst("\\.[^.]+$", "")).entrySet()) {
						constants.putIfAbsent(e.getKey(), e.getValue());
					}
					if (routeDecls.size() < MAX_ROUTE_DECLS) {
						List<RouteDecl> decls = extractRouteDecls(file.path, content, ext);
						synchronized (routeDecls) {
							for (RouteDecl d : decls) {
								if (routeDecls.size() < MAX_ROUTE_DECLS) {
									routeDecls.add(d);
								}
							}
						}
					}
				}
				String folded = foldAccents(content).toLowerCase(Locale.ROOT);
				double score = 0;
				for (String term : fTerms) {
					score += Math.log(1 + countHits(folded, term)) / Math.log(2);
				}
				synchronized (scores) {
					scores.put(file.path, scores.getOrDefault(file.path, 0.0) + score * weightOf(file.path));
				}
			}, token);
			token.check();
		}
		List<Scored> ranked = new ArrayList<>();
		for (ProjectFile f : cands) {
			ranked.add(new Scored(f, scores.getOrDefault(f.path, 0.0)));
		}
		ranked.sort((a, b) -> {
			int c = Double.compare(b.score, a.score);
			return c != 0 ? c : compareLocale(a.file.path, b.file.path);
		});
		Map<String, ProjectFile> byPath = new HashMap<>();
		for (ProjectFile f : cands) {
			byPath.put(f.path, f);
		}
		List<Scored> mapRanked = ranked;
		if (multi) {
			Map<String, Scored> scoredByPath = new HashMap<>();
			for (Scored r : ranked) {
				scoredByPath.put(r.file.path, r);
			}
			List<Scored> a = new ArrayList<>();
			for (String p : interleaveByMount(ranked.stream().filter(r -> r.score > 0).map(r -> r.file.path).collect(Collectors.toList()), true)) {
				a.add(scoredByPath.get(p));
			}
			for (String p : interleaveByMount(ranked.stream().filter(r -> r.score <= 0).map(r -> r.file.path).collect(Collectors.toList()), true)) {
				a.add(scoredByPath.get(p));
			}
			mapRanked = a;
		}

		List<String> pinnedOrder = new ArrayList<>();
		List<String> rest = new ArrayList<>();
		boolean prioritize = options.prioritizeRequest;
		List<String> flowPinned = new ArrayList<>();
		for (String path : options.pinnedPaths) {
			if (!prioritize) {
				if (byPath.containsKey(path) && !pinnedOrder.contains(path)) {
					pinnedOrder.add(path);
				}
			} else if (byPath.containsKey(path) && !pinnedOrder.contains(path) && !flowPinned.contains(path)) {
				flowPinned.add(path);
			}
		}
		for (ProjectFile file : forced) {
			if (byPath.containsKey(file.path) && !pinnedOrder.contains(file.path) && !rest.contains(file.path)) {
				rest.add(file.path);
			}
		}
		int aiPicked = 0;
		List<String> valid = new ArrayList<>();
		for (int i = 0; i < Math.min(KEYWORD_TOP, ranked.size()); i++) {
			Scored s = ranked.get(i);
			if (s.score > 0 && !pinnedOrder.contains(s.file.path) && !rest.contains(s.file.path)) {
				rest.add(s.file.path);
			}
		}
		if (multi) {
			Map<String, Double> best = new HashMap<>();
			for (Scored s : ranked) {
				best.putIfAbsent(mountOf(s.file.path, true), s.score);
			}
			double globalBest = ranked.isEmpty() ? 0 : ranked.get(0).score;
			boolean crossLayer = !prioritize;
			double relevance = crossLayer ? FLOW_MOUNT_RELEVANCE : 0.5;
			int topPerMount = crossLayer ? KEYWORD_TOP_PER_MOUNT_FLOW : KEYWORD_TOP_PER_MOUNT;
			for (Mount mount : mInfos) {
				boolean relevant = !mr.mentioned.isEmpty() ? mr.mentioned.contains(mount.id)
					: globalBest > 0 && best.getOrDefault(mount.id, 0.0) >= globalBest * relevance;
				if (!relevant) {
					continue;
				}
				int taken = 0;
				for (Scored s : ranked) {
					if (taken >= topPerMount || s.score <= 0) {
						break;
					}
					if (!mountOf(s.file.path, true).equals(mount.id)) {
						continue;
					}
					if (!pinnedOrder.contains(s.file.path) && !rest.contains(s.file.path)) {
						rest.add(s.file.path);
					}
					taken++;
				}
			}
		}
		List<String> base;
		List<String> siblings = new ArrayList<>();
		Set<String> siblingSet = new LinkedHashSet<>();
		if (!prioritize) {
			List<String> inter = interleaveByMount(rest, multi);
			base = new ArrayList<>(inter.subList(0, Math.min(inter.size(), Math.max(0, MAX_BASE_FILES - pinnedOrder.size()))));
		} else {
			Set<String> aiSet = new HashSet<>(valid);
			Map<String, Integer> originalOrder = new HashMap<>();
			for (int i = 0; i < flowPinned.size(); i++) {
				originalOrder.put(flowPinned.get(i), i);
			}
			flowPinned.sort((a, b) -> {
				int c = (aiSet.contains(b) ? 1 : 0) - (aiSet.contains(a) ? 1 : 0);
				if (c != 0) {
					return c;
				}
				c = Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0));
				return c != 0 ? c : originalOrder.getOrDefault(a, 0) - originalOrder.getOrDefault(b, 0);
			});
			List<String> refPinned = new ArrayList<>(pinnedOrder);
			int reserve = Math.min(RESERVED_REQUEST_FILES, Math.max(0, MAX_FILES - refPinned.size()));
			pinnedOrder.addAll(flowPinned.subList(0, Math.min(flowPinned.size(), Math.max(0, MAX_FILES - refPinned.size() - reserve))));
			boolean visual = false;
			for (String term : terms) {
				if (VISUAL_STEMS.contains(term)) {
					visual = true;
					break;
				}
			}
			if (visual) {
				java.util.function.Function<String, List<String>> siblingsOf = path -> {
					String own = Guards.codeExtension(baseName(path));
					String stem = path.substring(0, Math.max(0, path.length() - own.length() - 1));
					List<String> out = new ArrayList<>();
					for (String ext : SIBLING_EXTENSIONS) {
						if (ext.equals(own)) {
							continue;
						}
						String sibling = stem + "." + ext;
						if (byPath.containsKey(sibling) && !isTestPath(sibling)) {
							out.add(sibling);
						}
					}
					return out;
				};
				Set<String> all = new LinkedHashSet<>(refPinned);
				all.addAll(flowPinned);
				all.addAll(rest);
				List<String> sources = all.stream()
					.filter(p -> scores.getOrDefault(p, 0.0) > 0 && !isTestPath(p) && SIBLING_SOURCE_EXTENSIONS.contains(Guards.codeExtension(baseName(p)))
						&& !siblingsOf.apply(p).isEmpty())
					.sorted((a, b) -> Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0)))
					.limit(SIBLING_SOURCES).collect(Collectors.toList());
				for (String source : sources) {
					siblingSet.addAll(siblingsOf.apply(source));
				}
				siblings = new ArrayList<>(siblingSet).subList(0, Math.min(MAX_SIBLINGS, siblingSet.size()));
			}
			Set<String> inPinned = new HashSet<>(pinnedOrder);
			Set<String> reqSet = new LinkedHashSet<>(siblings);
			reqSet.addAll(interleaveByMount(rest, multi));
			List<String> requestPaths = reqSet.stream().filter(p -> !inPinned.contains(p)).collect(Collectors.toList());
			int take = Math.max(0, Math.min(MAX_FILES - pinnedOrder.size(), Math.max(reserve, MAX_BASE_FILES - pinnedOrder.size())));
			base = new ArrayList<>(requestPaths.subList(0, Math.min(take, requestPaths.size())));
		}
		List<String> finalPaths = new ArrayList<>(pinnedOrder.subList(0, Math.min(MAX_FILES, pinnedOrder.size())));
		finalPaths.addAll(base);

		Map<String, String> contents = new HashMap<>();
		Set<String> contentMiss = new HashSet<>();
		java.util.function.Function<ProjectFile, String> contentOf = file -> {
			if (contents.containsKey(file.path)) {
				return contents.get(file.path);
			}
			if (contentMiss.contains(file.path)) {
				return null;
			}
			String content = readText(file, READ_BYTES);
			if (content == null) {
				contentMiss.add(file.path);
			} else {
				contents.put(file.path, content);
			}
			return content;
		};

		// Puente HTTP.
		int bridged = 0;
		List<String[]> bridgeAnchors = new ArrayList<>();
		List<BridgeHint> bridgeHints = new ArrayList<>();
		if (!terms.isEmpty() && !routeDecls.isEmpty()) {
			List<BridgeRoute> routes = new ArrayList<>();
			for (RouteDecl decl : new ArrayList<>(routeDecls)) {
				StringBuilder joined = new StringBuilder();
				boolean resolved = true;
				for (String expr : decl.exprs) {
					String part = resolveRouteExpr(expr, constants, 0);
					if (part == null) {
						resolved = false;
						break;
					}
					joined.append('/').append(part);
				}
				List<String> segments = resolved ? routeSegments(joined.toString()) : new ArrayList<>();
				if (!segments.isEmpty()) {
					BridgeRoute r = new BridgeRoute();
					r.path = decl.path;
					r.httpMethod = decl.httpMethod;
					r.segments = segments;
					r.handler = decl.handler;
					routes.add(r);
				}
			}
			Pattern frontExt = Pattern.compile("\\.(?:ts|tsx|js|jsx)$", Pattern.CASE_INSENSITIVE);
			List<String> sources = finalPaths.stream().filter(p -> frontExt.matcher(p).find() && !isTestPath(p)).limit(MAX_BRIDGE_SOURCES).collect(Collectors.toList());
			List<Object[]> found = new ArrayList<>();
			if (!routes.isEmpty()) {
				for (String source : sources) {
					token.check();
					ProjectFile file = byPath.get(source);
					if (file == null) {
						continue;
					}
					String content = contentOf.apply(file);
					if (content == null) {
						continue;
					}
					List<HttpCall> calls = extractHttpCalls(content);
					if (calls.isEmpty()) {
						continue;
					}
					String[] lines = content.split("\r?\n", -1);
					for (HttpCall call : calls) {
						StringBuilder sb = new StringBuilder();
						for (int i = Math.max(0, call.line - 3); i < Math.min(lines.length, call.line + 4); i++) {
							sb.append(lines[i]).append('\n');
						}
						String around = foldAccents(sb.toString()).toLowerCase(Locale.ROOT);
						int relevance = 0;
						for (String term : terms) {
							if (around.contains(term)) {
								relevance++;
							}
						}
						found.add(new Object[] { source, call, relevance });
					}
				}
			}
			List<Object[]> relevantCalls = found.stream().filter(f -> (Integer) f[2] > 0).collect(Collectors.toList());
			List<Object[]> picked = new ArrayList<>(relevantCalls.isEmpty() ? found : relevantCalls);
			picked.sort((a, b) -> (Integer) b[2] - (Integer) a[2]);
			if (picked.size() > MAX_HTTP_CALLS) {
				picked = new ArrayList<>(picked.subList(0, MAX_HTTP_CALLS));
			}
			Map<String, Object[]> targets = new LinkedHashMap<>();
			for (Object[] item : picked) {
				String itemSource = (String) item[0];
				HttpCall itemCall = (HttpCall) item[1];
				BridgeRoute bestRoute = null;
				int bestScore = 0;
				for (BridgeRoute route : routes) {
					if (route.path.equals(itemSource)) {
						continue;
					}
					int score = matchScore(itemCall, route);
					if (score > bestScore) {
						bestScore = score;
						bestRoute = route;
					}
				}
				if (bestRoute == null) {
					continue;
				}
				if (bridgeHints.size() < MAX_BRIDGE_HINTS) {
					BridgeHint h = new BridgeHint();
					h.front = itemSource;
					h.line = itemCall.line;
					h.method = itemCall.httpMethod;
					h.back = bestRoute.path;
					h.handler = bestRoute.handler;
					bridgeHints.add(h);
				}
				Object[] target = targets.get(bestRoute.path);
				if (target == null) {
					if (targets.size() >= MAX_BRIDGE_FILES) {
						continue;
					}
					target = new Object[] { itemSource, new LinkedHashSet<String>() };
					targets.put(bestRoute.path, target);
				}
				@SuppressWarnings("unchecked")
				Set<String> handlers = (Set<String>) target[1];
				if (bestRoute.handler != null && handlers.size() < 4) {
					handlers.add(bestRoute.handler);
				}
			}
			Set<String> present = new HashSet<>(finalPaths);
			Map<String, String> lastAfter = new HashMap<>();
			for (Map.Entry<String, Object[]> e : targets.entrySet()) {
				String backPath = e.getKey();
				String tSource = (String) e.getValue()[0];
				if (!present.contains(backPath)) {
					if (finalPaths.size() >= MAX_FILES) {
						continue;
					}
					String anchor = lastAfter.getOrDefault(tSource, tSource);
					finalPaths.add(finalPaths.indexOf(anchor) + 1, backPath);
					present.add(backPath);
					lastAfter.put(tSource, backPath);
				}
				@SuppressWarnings("unchecked")
				Set<String> handlers = (Set<String>) e.getValue()[1];
				for (String handler : handlers) {
					bridgeAnchors.add(new String[] { backPath, handler });
				}
				bridged++;
			}
		}

		// Expansion por referencias.
		Map<String, Map<String, List<ProjectFile>>> index = new HashMap<>();
		for (ProjectFile file : cands) {
			if (isTestPath(file.path) || !REF_EXTENSIONS.contains(Guards.codeExtension(file.name))) {
				continue;
			}
			index.computeIfAbsent(mountOf(file.path, prefixed), k -> new HashMap<>()).computeIfAbsent(fileKey(file.name), k -> new ArrayList<>()).add(file);
		}
		Set<String> included = new HashSet<>(finalPaths);
		List<Object[]> queue = new ArrayList<>();
		for (String p : interleaveByMount(finalPaths, multi)) {
			queue.add(new Object[] { p, 0 });
		}
		Map<String, Integer> refsByMount = new HashMap<>();
		int refCap = multi ? MAX_REF_FILES_PER_MOUNT : MAX_REF_FILES;
		int refsAdded = 0;
		for (int q = 0; q < queue.size(); q++) {
			token.check();
			if (refsAdded >= MAX_REF_FILES || finalPaths.size() >= MAX_FILES) {
				break;
			}
			String path = (String) queue.get(q)[0];
			int depth = (Integer) queue.get(q)[1];
			ProjectFile file = byPath.get(path);
			if (file == null) {
				continue;
			}
			String ext = Guards.codeExtension(file.name);
			if (!REF_EXTENSIONS.contains(ext)) {
				continue;
			}
			boolean withTypes = depth < REF_DEPTH;
			if (!withTypes && !ext.equals("java") && !ext.equals("kt")) {
				continue;
			}
			String content = contentOf.apply(file);
			if (content == null) {
				continue;
			}
			RefParts parts = referencedParts(content, ext);
			List<String> keys = new ArrayList<>();
			if (withTypes) {
				keys.addAll(parts.types);
			}
			if (parts.entity != null) {
				keys.add(parts.entity);
			}
			String mountKey = mountOf(path, prefixed);
			Map<String, List<ProjectFile>> byKey = index.get(mountKey);
			int offset = 0;
			for (String key : keys) {
				if (refsAdded >= MAX_REF_FILES || finalPaths.size() >= MAX_FILES || refsByMount.getOrDefault(mountKey, 0) >= refCap) {
					break;
				}
				List<ProjectFile> list = byKey == null ? null : byKey.get(key);
				if (list == null || list.stream().anyMatch(f -> included.contains(f.path))) {
					continue;
				}
				ProjectFile target = list.get(0);
				finalPaths.add(finalPaths.indexOf(path) + 1 + offset, target.path);
				included.add(target.path);
				queue.add(new Object[] { target.path, depth + 1 });
				offset++;
				refsAdded++;
				inc(refsByMount, mountKey);
			}
		}

		// Anclas.
		QuestionAnchors questionAnchors = methodAnchorsOf(questionText);
		List<CodeScan> scans = new ArrayList<>();
		if (!questionAnchors.global.isEmpty() || !questionAnchors.scoped.isEmpty() || !terms.isEmpty()) {
			for (String path : finalPaths) {
				token.check();
				ProjectFile file = byPath.get(path);
				if (file == null) {
					continue;
				}
				String ext = Guards.codeExtension(file.name);
				if (!BRACE_EXTENSIONS.contains(ext)) {
					continue;
				}
				String content = contentOf.apply(file);
				if (content == null) {
					continue;
				}
				CodeScan scan = new CodeScan();
				scan.path = path;
				scan.mount = mountOf(path, prefixed);
				scan.key = fileKey(file.name);
				scan.ext = ext;
				scan.lines = content.split("\r?\n", -1);
				scan.calls = callsOf(scan.lines, fieldTypesOf(content, ext));
				scans.add(scan);
			}
		}
		Map<String, CodeScan> scanByPath = new HashMap<>();
		Set<String> finalKeys = new HashSet<>();
		for (CodeScan s : scans) {
			scanByPath.put(s.path, s);
			finalKeys.add(s.mount + "|" + s.key);
		}
		Map<String, Map<String, Integer>> called = new HashMap<>();
		java.util.function.BiFunction<String, Object[], Boolean> setCalled = (destKey, mr2) -> {
			String method = (String) mr2[0];
			int rank = (Integer) mr2[1];
			Map<String, Integer> methods = called.computeIfAbsent(destKey, k -> new LinkedHashMap<>());
			Integer current = methods.get(method);
			if (current != null) {
				if (current <= rank) {
					return false;
				}
				methods.put(method, rank);
				return true;
			}
			if (methods.size() >= MAX_ANCHOR_METHODS) {
				String worst = null;
				int worstRank = rank;
				for (Map.Entry<String, Integer> e : methods.entrySet()) {
					if (e.getValue() > worstRank) {
						worst = e.getKey();
						worstRank = e.getValue();
					}
				}
				if (worst == null) {
					return false;
				}
				methods.remove(worst);
			}
			methods.put(method, rank);
			return true;
		};
		java.util.function.BiFunction<CodeScan, Object[], Boolean> addTargets = (scan, cr) -> {
			CallSite call = (CallSite) cr[0];
			int rank = (Integer) cr[1];
			boolean changed = false;
			for (String target : call.targets) {
				String destKey = scan.mount + "|" + target;
				if (finalKeys.contains(destKey) && setCalled.apply(destKey, new Object[] { call.method, rank })) {
					changed = true;
				}
			}
			return changed;
		};
		java.util.function.Function<CodeScan, Map<String, Integer>> ownAnchors = scan -> {
			Map<String, Integer> out = new LinkedHashMap<>();
			for (String method : questionAnchors.global) {
				out.put(method, 1);
			}
			for (String method : questionAnchors.scoped.getOrDefault(scan.key, Collections.emptyList())) {
				out.put(method, 1);
			}
			for (Map.Entry<String, Integer> e : called.getOrDefault(scan.mount + "|" + scan.key, Collections.emptyMap()).entrySet()) {
				Integer current = out.get(e.getKey());
				if (current == null || current > e.getValue()) {
					out.put(e.getKey(), e.getValue());
				}
			}
			return out;
		};
		java.util.function.BiFunction<CodeScan, String, MethodLocation> locate = (scan, method) -> {
			if (scan.located.containsKey(method)) {
				return scan.located.get(method);
			}
			if (scan.locatedMiss.contains(method)) {
				return null;
			}
			MethodLocation loc = locateMethod(scan.lines, method, scan.ext);
			if (loc == null) {
				scan.locatedMiss.add(method);
			} else {
				scan.located.put(method, loc);
			}
			return loc;
		};
		if (!scans.isEmpty()) {
			for (String[] anchor : bridgeAnchors) {
				CodeScan scan = scanByPath.get(anchor[0]);
				if (scan != null && anchor[1] != null) {
					setCalled.apply(scan.mount + "|" + scan.key, new Object[] { anchor[1], 2 });
				}
			}
			Set<String> termSet = new HashSet<>(terms);
			for (CodeScan scan : scans) {
				token.check();
				for (CallSite call : scan.calls) {
					boolean hit = false;
					for (String t : tokenize(call.method)) {
						if (termSet.contains(t)) {
							hit = true;
						}
					}
					if (hit) {
						addTargets.apply(scan, new Object[] { call, 3 });
					}
				}
			}
			for (int round = 0; round < ANCHOR_ROUNDS; round++) {
				boolean changed = false;
				for (CodeScan scan : scans) {
					token.check();
					if (scan.calls.isEmpty()) {
						continue;
					}
					for (Map.Entry<String, Integer> own : new ArrayList<>(ownAnchors.apply(scan).entrySet())) {
						if (own.getValue() > 2) {
							continue;
						}
						MethodLocation found = locate.apply(scan, own.getKey());
						if (found == null) {
							continue;
						}
						for (CallSite call : scan.calls) {
							if (call.line >= found.decl && call.line <= found.end && addTargets.apply(scan, new Object[] { call, 2 })) {
								changed = true;
							}
						}
					}
				}
				if (!changed) {
					break;
				}
			}
		}
		java.util.function.Function<String, List<String>> anchorsFor = path -> {
			CodeScan scan = scanByPath.get(path);
			if (scan == null) {
				return new ArrayList<>();
			}
			List<Map.Entry<String, Integer>> entries = new ArrayList<>(ownAnchors.apply(scan).entrySet());
			entries.sort((a, b) -> a.getValue() - b.getValue());
			return entries.stream().limit(MAX_ANCHOR_METHODS).map(Map.Entry::getKey).collect(Collectors.toList());
		};

		// Fragmentos.
		report.accept("Leyendo los fragmentos\u2026");
		List<String> entryPaths = new ArrayList<>();
		List<List<Frag>> entryFragments = new ArrayList<>();
		for (String path : finalPaths) {
			token.check();
			if (activeInfo != null && path.equals(activeInfo.file.path)) {
				continue;
			}
			ProjectFile file = byPath.get(path);
			if (file == null) {
				continue;
			}
			String content = contentOf.apply(file);
			if (content == null) {
				continue;
			}
			List<Frag> list = fragmentsOf(path, content, terms, new ArrayList<>(), anchorsFor.apply(path),
				prioritize ? (siblingSet.contains(path) ? Integer.valueOf(1) : Integer.valueOf(FOCUS_MIN_TERMS)) : null);
			if (list.isEmpty()) {
				continue;
			}
			entryPaths.add(path);
			entryFragments.add(list);
		}
		int n = entryPaths.size();
		Integer[] orderIdx = new Integer[n];
		for (int i = 0; i < n; i++) {
			orderIdx[i] = i;
		}
		if (multi) {
			List<String> inter = interleaveByMount(entryPaths, true);
			Map<String, Integer> rank = new HashMap<>();
			for (int i = 0; i < inter.size(); i++) {
				rank.put(inter.get(i), i);
			}
			Arrays.sort(orderIdx, Comparator.comparingInt(i -> rank.getOrDefault(entryPaths.get(i), 0)));
		}
		List<String> sortedPaths = new ArrayList<>();
		List<List<Frag>> sortedFragments = new ArrayList<>();
		for (Integer i : orderIdx) {
			sortedPaths.add(entryPaths.get(i));
			sortedFragments.add(entryFragments.get(i));
		}
		int[] demand = new int[n];
		int[] anchorDemand = new int[n];
		int[] counts = new int[n];
		for (int i = 0; i < n; i++) {
			for (Frag f : sortedFragments.get(i)) {
				demand[i] += f.content.length();
				if (f.priority >= 3) {
					anchorDemand[i] += f.content.length();
				}
			}
			counts[i] = sortedFragments.get(i).size();
		}
		int[] countShares = fairShares(counts, MAX_FRAGMENTS, Integer.MAX_VALUE);
		int[] anchorCaps = new int[n];
		for (int i = 0; i < n; i++) {
			anchorCaps[i] = Math.min(anchorDemand[i], MAX_FILE_CHARS);
		}
		int[] anchorShares = fairShares(anchorCaps, ANCHOR_BUDGET, MAX_FILE_CHARS);
		int anchorTotal = Arrays.stream(anchorShares).sum();
		int[] restDemand = new int[n];
		for (int i = 0; i < n; i++) {
			restDemand[i] = Math.max(0, Math.min(demand[i], MAX_FILE_CHARS) - anchorShares[i]);
		}
		int[] restShares = fairShares(restDemand, Math.max(0, MAX_FRAGMENTS_TOTAL - anchorTotal), MAX_FILE_CHARS);
		List<ContextFragment> fragments = new ArrayList<>();
		Set<String> fragmentFiles = new LinkedHashSet<>();
		int total = 0;
		int anchorCount = 0;
		int focusCount = 0;
		for (int i = 0; i < n; i++) {
			int left = anchorShares[i] + restShares[i];
			List<Frag> kept = new ArrayList<>();
			List<Frag> ordered = new ArrayList<>(sortedFragments.get(i));
			ordered.sort((a, b) -> b.priority != a.priority ? b.priority - a.priority : a.startLine - b.startLine);
			for (Frag fragment : ordered) {
				if (kept.size() >= countShares[i]) {
					break;
				}
				if (fragment.content.length() <= left) {
					kept.add(fragment);
					left -= fragment.content.length();
					total += fragment.content.length();
					continue;
				}
				String[] lines = fragment.content.split("\n", -1);
				int size = 0;
				int count = 0;
				for (String line : lines) {
					int next = size + line.length() + (count > 0 ? 1 : 0);
					if (next > left) {
						break;
					}
					size = next;
					count++;
				}
				if (count > 0 && size >= MIN_TRIM_CHARS) {
					Frag cut = new Frag();
					cut.path = fragment.path;
					cut.startLine = fragment.startLine;
					cut.endLine = fragment.startLine + count - 1;
					cut.content = String.join("\n", Arrays.asList(lines).subList(0, count));
					cut.priority = fragment.priority;
					cut.anchor = fragment.anchor;
					cut.focus = fragment.focus;
					kept.add(cut);
					total += size;
				}
				break;
			}
			kept.sort((a, b) -> a.startLine - b.startLine);
			for (Frag fragment : kept) {
				ContextFragment cf = new ContextFragment();
				cf.path = fragment.path;
				cf.startLine = fragment.startLine;
				cf.endLine = fragment.endLine;
				cf.content = fragment.content;
				cf.priority = fragment.priority;
				fragments.add(cf);
				fragmentFiles.add(fragment.path);
				if (fragment.anchor) {
					anchorCount++;
				}
				if (fragment.focus) {
					focusCount++;
				}
			}
		}
		TrazaPlugin.info("Contexto \u00b7 carpetas=" + mInfos.size() + " \u00b7 listados=" + listed + " \u00b7 excluidos=" + excluded + " \u00b7 candidatos=" + cands.size()
			+ " \u00b7 escaneados=" + toScan.size() + " \u00b7 forzados=" + forced.size() + " \u00b7 IA=" + aiPicked + " \u00b7 claves=" + ids.size()
			+ " \u00b7 referencias=" + refsAdded + " \u00b7 puente=" + bridged + " \u00b7 anclas=" + anchorCount + " \u00b7 archivos=" + fragmentFiles.size()
			+ " \u00b7 fragmentos=" + fragments.size() + " (" + total + " caracteres)" + (prioritize ? " \u00b7 foco=" + focusCount + " \u00b7 hermanos=" + siblings.size() : ""));

		FlowContext ctx = new FlowContext();
		ctx.mounts = mounts;
		ctx.mapText = mapText(mapRanked, MAP_FLOW_CHARS);
		ctx.fragments = fragments;
		if (activeInfo != null) {
			ctx.active = activeInfo.active;
			ctx.selection = activeInfo.selection;
		}
		ctx.roles = roles;
		ctx.bridge = bridgeHints;
		return ctx;
	}

	private static int compareLocale(String a, String b) {
		int c = a.compareToIgnoreCase(b);
		return c != 0 ? c : a.compareTo(b);
	}
}
