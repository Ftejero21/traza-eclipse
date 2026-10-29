package com.ftejero21.traza.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.OpenWindowListener;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.WindowEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.part.ViewPart;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.AiException;
import com.ftejero21.traza.core.FlowStore;
import com.ftejero21.traza.core.FlowStore.GeneratedFlow;
import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.db.DbMetadataService;
import com.ftejero21.traza.http.LocalHttpServer;
import com.ftejero21.traza.mcp.CopilotBridge;
import com.ftejero21.traza.mcp.JobRegistry;
import com.ftejero21.traza.prefs.TrazaPreferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Vista unica «Flujo · &lt;titulo&gt;» con la webview Angular en un SWT Browser (port de FlowPanel). Toda la API estatica
 * debe llamarse desde el hilo UI.
 */
public class FlowView extends ViewPart {
	public static final String ID = "com.ftejero21.traza.views.flow";
	private static final int FILES_DEBOUNCE_MS = 500;
	private static final int MAX_PROPOSALS = 10;
	private static final String MODEL_NAME = "GitHub Copilot";
	private static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", "dist", "build", "target", "out", ".git", ".angular", "coverage", ".next");

	private static FlowView current;

	/** Contenido de la vista. */
	public static final class FlowPayload {
		public final JsonElement flow;
		public final boolean sample;
		public final String title;
		public final List<JsonElement> proposals;
		public final String sourceModel;
		public final String mode;

		public FlowPayload(JsonElement flow, boolean sample, String title, List<JsonElement> proposals, String sourceModel, String mode) {
			this.flow = flow == null ? JsonNull.INSTANCE : flow;
			this.sample = sample;
			this.title = title;
			this.proposals = proposals;
			this.sourceModel = sourceModel;
			this.mode = mode;
		}

		FlowPayload with(List<JsonElement> newProposals, String newMode) {
			return new FlowPayload(flow, sample, title, newProposals, sourceModel, newMode);
		}
	}

	/** Flujo abierto en la vista (sin el de ejemplo) y sus propuestas. */
	public static final class CurrentFlow {
		public final JsonElement flow;
		public final String title;
		public final List<JsonElement> proposals;

		CurrentFlow(JsonElement flow, String title, List<JsonElement> proposals) {
			this.flow = flow;
			this.title = title;
			this.proposals = proposals;
		}
	}

	private Browser browser;
	private BrowserFunction hostPost;
	private FlowPayload payload;
	private boolean ready;
	private boolean disposed;
	private final List<JsonObject> queue = new ArrayList<>();
	private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private ExecutorService executor;
	private IResourceChangeListener resourceListener;
	private Runnable removePrefListener;
	private final Runnable filesRunnable = () -> post(simple("filesChanged"));

	/* ------------------------------ API estatica (hilo UI) ------------------------------ */

	/** Abre o actualiza la vista con el contenido. */
	public static void show(FlowPayload payload) {
		IWorkbenchPage page = Ui.page();
		if (page == null) {
			return;
		}
		try {
			org.eclipse.ui.IViewPart part = page.showView(ID);
			FlowView view = part instanceof FlowView ? (FlowView) part : current;
			if (view == null) {
				return;
			}
			view.payload = payload;
			view.setPartName("Flujo · " + payload.title);
			view.post(openFlowMessage(payload));
		} catch (org.eclipse.ui.PartInitException e) {
			TrazaPlugin.error("No se pudo abrir la vista de Traza.", e);
			Ui.error("Traza", Ui.t("No se pudo abrir la vista de Traza.", "The Traza view could not be opened."));
		}
	}

	/** Flujo de ejemplo. */
	public static void showSample() {
		show(new FlowPayload(JsonNull.INSTANCE, true, "Login con email y contraseña", new ArrayList<>(), null, null));
	}

	/**
	 * Abre un flujo generado: con escenario se abre el escenario (sin mode); si se pidio un escenario y no llego, en
	 * «Simular»; en otro caso, «Recorrido». Un mode explicito (enlaces) manda.
	 */
	public static void showGenerated(GeneratedFlow generated, String mode) {
		JsonElement scenario = generated.flow.get("scenario");
		boolean hasScenario = scenario != null && !scenario.isJsonNull() && !(J.isBool(scenario) && !scenario.getAsBoolean());
		String effective = mode != null ? mode : (generated.scenarioAsked && !hasScenario ? "sim" : null);
		show(new FlowPayload(generated.flow, false, generated.title, new ArrayList<>(), generated.model, effective));
	}

	public static CurrentFlow currentFlow() {
		FlowView v = current;
		if (v == null || v.payload == null || v.payload.sample || v.payload.flow.isJsonNull()) {
			return null;
		}
		return new CurrentFlow(v.payload.flow, v.payload.title, new ArrayList<>(v.payload.proposals));
	}

	/** Anade una propuesta al flujo abierto y abre «Simular cambios» (reenvia openFlow con las acumuladas, max. 10). */
	public static boolean addProposal(JsonObject proposal) {
		FlowView v = current;
		if (v == null || v.payload == null || v.payload.sample || v.payload.flow.isJsonNull()) {
			return false;
		}
		String proposalFlowId = J.str(proposal, "flowId");
		JsonObject flowObj = J.obj(v.payload.flow);
		String panelFlowId = flowObj == null ? "" : J.str(flowObj, "id");
		if (!proposalFlowId.isEmpty() && !panelFlowId.isEmpty() && !proposalFlowId.equals(panelFlowId)) {
			return false;
		}
		List<JsonElement> proposals = new ArrayList<>(v.payload.proposals);
		proposals.add(proposal);
		while (proposals.size() > MAX_PROPOSALS) {
			proposals.remove(0);
		}
		v.payload = v.payload.with(proposals, "changes");
		IWorkbenchPage page = Ui.page();
		if (page != null) {
			page.bringToTop(v);
		}
		v.post(openFlowMessage(v.payload));
		return true;
	}

	/** Limpia la cache de metadata de base de datos (contrasena o conexiones cambiadas). */
	public static void clearDbCache() {
		DbMetadataService.INSTANCE.clearCache();
	}

	/* ------------------------------ mensajes ------------------------------ */

	private static JsonObject simple(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		return o;
	}

	private static JsonObject openFlowMessage(FlowPayload p) {
		JsonObject m = new JsonObject();
		m.addProperty("type", "openFlow");
		m.add("flow", p.flow);
		m.addProperty("sample", p.sample);
		JsonArray proposals = new JsonArray();
		p.proposals.forEach(proposals::add);
		m.add("proposals", proposals);
		if (p.sourceModel != null) {
			JsonObject source = new JsonObject();
			source.addProperty("model", p.sourceModel);
			m.add("source", source);
		}
		if (p.mode != null) {
			m.addProperty("mode", p.mode);
		}
		return m;
	}

	private static JsonObject configMessage(String type) {
		JsonObject m = new JsonObject();
		m.addProperty("type", type);
		m.addProperty("language", TrazaPreferences.language());
		m.addProperty("modelName", MODEL_NAME);
		m.addProperty("theme", TrazaPreferences.themeForWebview());
		return m;
	}

	/** Envia un mensaje a la webview; hasta que avisa con ready se encola. */
	private void post(JsonObject message) {
		if (disposed || browser == null || browser.isDisposed()) {
			return;
		}
		String type = J.str(message, "type");
		if (!ready) {
			if (type.equals("openFlow")) {
				queue.removeIf(m -> J.str(m, "type").equals("openFlow"));
			}
			if ((type.equals("filesChanged") || type.equals("fileSaved")) && queue.stream().anyMatch(m -> J.str(m, "type").equals(type))) {
				return;
			}
			queue.add(message);
			return;
		}
		try {
			browser.execute("window.__trazaReceive(" + J.GSON_JS.toJson(J.GSON.toJson(message)) + ");");
		} catch (SWTException e) {
			TrazaPlugin.warn("No se pudo enviar el mensaje a la webview (" + e.getClass().getSimpleName() + ").");
		}
	}

	private void applyTheme() {
		if (browser == null || browser.isDisposed()) {
			return;
		}
		IdeTheme theme = IdeTheme.current();
		String css = "ide".equals(TrazaPreferences.themePref()) ? theme.css : "";
		try {
			browser.execute("if(window.__trazaSetTheme){window.__trazaSetTheme(" + theme.dark + "," + J.GSON_JS.toJson(css) + ");}");
		} catch (SWTException e) {
			TrazaPlugin.warn("No se pudo aplicar el tema (" + e.getClass().getSimpleName() + ").");
		}
	}

	/* ------------------------------ ciclo de vida ------------------------------ */

	@Override
	public void createPartControl(Composite parent) {
		current = this;
		Composite area = new Composite(parent, SWT.NONE);
		area.setLayout(new FillLayout());
		LocalHttpServer.ensureStarted();
		if (!LocalHttpServer.isRunning()) {
			message(area, Ui.t("No se pudo iniciar el servidor local de Traza. Consulta el registro de errores.", "The Traza local server could not be started. Check the error log."));
			return;
		}
		try {
			boolean windows = Platform.OS_WIN32.equals(Platform.getOS());
			browser = new Browser(area, windows ? SWT.EDGE : SWT.NONE);
		} catch (SWTError | SWTException e) {
			TrazaPlugin.error("No se pudo crear el navegador integrado.", e);
			message(area, windowsHint());
			return;
		}
		executor = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
			private final AtomicInteger n = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "traza-view-" + n.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		});
		final String base = LocalHttpServer.viewBase();
		hostPost = new BrowserFunction(browser, "trazaHostPost") {
			@Override
			public Object function(Object[] arguments) {
				onHostPost(base, arguments);
				return null;
			}
		};
		browser.addLocationListener(new LocationAdapter() {
			@Override
			public void changing(LocationEvent event) {
				String loc = event.location == null ? "" : event.location;
				if (event.top && !loc.startsWith(base) && !loc.equals("about:blank")) {
					event.doit = false;
					TrazaPlugin.warn("Navegacion bloqueada fuera de la vista de Traza.");
				}
			}
		});
		browser.addOpenWindowListener(new OpenWindowListener() {
			@Override
			public void open(WindowEvent event) {
				event.browser = null;
			}
		});
		browser.addProgressListener(new ProgressAdapter() {
			@Override
			public void completed(ProgressEvent event) {
				applyTheme();
			}
		});
		resourceListener = this::onResourceChanged;
		ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
		removePrefListener = TrazaPreferences.addChangeListener(key -> Ui.async(() -> {
			if (disposed) {
				return;
			}
			if (TrazaPreferences.DATABASES.equals(key)) {
				DbMetadataService.INSTANCE.clearCache();
			}
			post(configMessage("config"));
			applyTheme();
		}));
		browser.setUrl(base + "index.html");
	}

	private static String windowsHint() {
		return Platform.OS_WIN32.equals(Platform.getOS())
			? Ui.t("No se pudo crear el navegador integrado. Instala el runtime de Microsoft Edge WebView2 y reinicia Eclipse.",
				"The embedded browser could not be created. Install the Microsoft Edge WebView2 runtime and restart Eclipse.")
			: Ui.t("No se pudo crear el navegador integrado. En Linux instala WebKitGTK y reinicia Eclipse.",
				"The embedded browser could not be created. On Linux install WebKitGTK and restart Eclipse.");
	}

	private static void message(Composite area, String text) {
		Label label = new Label(area, SWT.WRAP);
		label.setText(text);
		area.layout();
	}

	@Override
	public void setFocus() {
		if (browser != null && !browser.isDisposed()) {
			browser.setFocus();
		}
	}

	@Override
	public void dispose() {
		disposed = true;
		if (current == this) {
			current = null;
		}
		Display display = Ui.display();
		if (display != null && !display.isDisposed()) {
			display.timerExec(-1, filesRunnable);
		}
		if (resourceListener != null) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
			resourceListener = null;
		}
		if (removePrefListener != null) {
			removePrefListener.run();
			removePrefListener = null;
		}
		JobRegistry.cancelAll(new ArrayList<>(inFlight));
		inFlight.clear();
		queue.clear();
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		if (hostPost != null && !hostPost.isDisposed()) {
			hostPost.dispose();
		}
		hostPost = null;
		super.dispose();
	}

	/* ------------------------------ recursos del workspace ------------------------------ */

	private void onResourceChanged(IResourceChangeEvent event) {
		IResourceDelta delta = event.getDelta();
		if (delta == null || disposed) {
			return;
		}
		boolean[] flags = new boolean[2];
		try {
			delta.accept(d -> {
				IResource r = d.getResource();
				int type = r.getType();
				if (type == IResource.ROOT) {
					return true;
				}
				int kind = d.getKind();
				if (type == IResource.PROJECT) {
					if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED || (d.getFlags() & IResourceDelta.OPEN) != 0) {
						flags[0] = true;
					}
					return true;
				}
				if (type == IResource.FOLDER && EXCLUDED_DIRS.contains(r.getName())) {
					return false;
				}
				if (r.isDerived()) {
					return false;
				}
				if (type == IResource.FILE) {
					if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED) {
						flags[0] = true;
					} else if (kind == IResourceDelta.CHANGED && (d.getFlags() & IResourceDelta.CONTENT) != 0) {
						flags[1] = true;
					}
				} else if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED) {
					flags[0] = true;
				}
				return true;
			});
		} catch (CoreException e) {
			TrazaPlugin.warn("No se pudo examinar un cambio del workspace (" + e.getClass().getSimpleName() + ").");
			return;
		}
		if (flags[0] || flags[1]) {
			final boolean files = flags[0];
			final boolean saved = flags[1];
			Ui.async(() -> {
				if (disposed) {
					return;
				}
				if (files) {
					Display display = Ui.display();
					display.timerExec(-1, filesRunnable);
					display.timerExec(FILES_DEBOUNCE_MS, filesRunnable);
				}
				if (saved) {
					post(simple("fileSaved"));
				}
			});
		}
	}

	/* ------------------------------ mensajes de la webview ------------------------------ */

	private void onHostPost(String base, Object[] arguments) {
		if (disposed || browser == null || browser.isDisposed()) {
			return;
		}
		String url = browser.getUrl();
		if (url == null || !url.startsWith(base)) {
			TrazaPlugin.warn("Mensaje ignorado: procede de fuera de la vista de Traza.");
			return;
		}
		if (arguments == null || arguments.length < 1 || !(arguments[0] instanceof String)) {
			return;
		}
		JsonElement raw;
		try {
			raw = com.ftejero21.traza.ai.Semantics.parseStrict((String) arguments[0]);
		} catch (RuntimeException e) {
			TrazaPlugin.warn("Mensaje no valido de la webview.");
			return;
		}
		JsonObject message = Guards.guardMessage(raw);
		if (message == null) {
			return;
		}
		switch (J.str(message, "type")) {
		case "ready":
			ready = true;
			post(configMessage("init"));
			applyTheme();
			List<JsonObject> pending = new ArrayList<>(queue);
			queue.clear();
			pending.forEach(this::post);
			return;
		case "request":
			handleRequest(J.str(message, "id"), message.getAsJsonObject("req"));
			return;
		case "cancel":
			JobRegistry.cancel(J.str(message, "id"));
			inFlight.remove(J.str(message, "id"));
			return;
		case "openRef":
			WorkspaceFiles.openRef(J.str(message, "fileId"), message.get("line").getAsInt(), J.obj(message.get("sample")));
			return;
		case "notify":
			notifyUser(J.str(message, "level"), J.str(message, "text"));
			return;
		case "ask":
			ask(J.str(message, "text"));
			return;
		case "saveDbMap": {
			String flowId = J.str(message, "flowId");
			JsonObject dbMap = message.getAsJsonObject("dbMap");
			FlowStore.rememberDbMap(flowId, dbMap);
			JsonObject flow = payload == null ? null : J.obj(payload.flow);
			if (payload != null && !payload.sample && flow != null && flowId.equals(J.str(flow, "id"))) {
				JsonObject copy = flow.deepCopy();
				copy.add("dbMap", dbMap);
				payload = new FlowPayload(copy, false, payload.title, payload.proposals, payload.sourceModel, payload.mode);
			}
			return;
		}
		default:
			return;
		}
	}

	private void handleRequest(String id, JsonObject req) {
		if (executor == null) {
			return;
		}
		inFlight.add(id);
		java.util.function.Consumer<String> progress = text -> Ui.async(() -> {
			JsonObject m = new JsonObject();
			m.addProperty("type", "progress");
			m.addProperty("id", id);
			m.addProperty("message", text);
			post(m);
		});
		// La preparacion de la peticion (decodificar, listar archivos...) nunca se hace en el hilo UI.
		java.util.concurrent.CompletableFuture.supplyAsync(() -> WebviewBridge.handle(id, req, executor, progress), executor)
			.thenCompose(f -> f).whenComplete((result, error) -> Ui.async(() -> {
			inFlight.remove(id);
			if (disposed) {
				return;
			}
			Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			if (cause instanceof CancellationException) {
				return;
			}
			JsonObject m = new JsonObject();
			m.addProperty("type", "response");
			m.addProperty("id", id);
			if (cause == null) {
				m.addProperty("ok", true);
				m.add("result", result == null ? JsonNull.INSTANCE : result);
			} else {
				m.addProperty("ok", false);
				m.addProperty("error", errorText(cause));
			}
			post(m);
		}));
	}

	private static String errorText(Throwable error) {
		if (error instanceof AiException || error instanceof DbMetadataService.DbException || error instanceof IllegalArgumentException) {
			return J.truncate(String.valueOf(error.getMessage()), 500);
		}
		TrazaPlugin.error("Error al atender una peticion de la webview.", error);
		return "Error inesperado.";
	}

	private void notifyUser(String level, String text) {
		if (level.equals("danger")) {
			MessageDialog.openError(Ui.shell(), "Traza", text);
		} else {
			Ui.notice("Traza", text);
		}
	}

	/** «Preguntar a la IA»: abre el chat de Copilot con la pregunta; si no se puede, la copia al portapapeles. */
	private void ask(String text) {
		CopilotBridge.showNoticeOnce();
		if (CopilotBridge.openChat(text, false)) {
			return;
		}
		try {
			org.eclipse.swt.dnd.Clipboard cb = new org.eclipse.swt.dnd.Clipboard(Ui.display());
			try {
				cb.setContents(new Object[] { text }, new org.eclipse.swt.dnd.Transfer[] { org.eclipse.swt.dnd.TextTransfer.getInstance() });
			} finally {
				cb.dispose();
			}
			Ui.notice("Traza", Ui.t("No se pudo abrir el chat; la pregunta se ha copiado al portapapeles.", "The chat could not be opened; the question was copied to the clipboard."));
		} catch (RuntimeException e) {
			TrazaPlugin.warn("No se pudo copiar la pregunta (" + e.getClass().getSimpleName() + ").");
			Ui.warning("Traza", Ui.t("No se pudo abrir el chat ni copiar la pregunta.", "The chat could not be opened and the question could not be copied."));
		}
	}
}
