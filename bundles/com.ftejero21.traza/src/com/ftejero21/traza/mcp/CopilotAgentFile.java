package com.ftejero21.traza.mcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.BundleFiles;
import com.ftejero21.traza.prefs.TrazaPreferences;

/**
 * Mantiene el archivo de agente gestionado {@code .github/agents/Traza.agent.md} en UN solo proyecto del workspace, para que
 * el selector de modos del chat de Copilot muestre «Traza». Nunca toca un archivo modificado por el usuario ni uno sin marcador.
 * Sin dependencia con Copilot.
 */
public final class CopilotAgentFile {
	public static final String REL_PATH = ".github/agents/Traza.agent.md";
	static final String TEMPLATE = "resources/copilot/Traza.agent.md";
	static final int FORMAT_VERSION = 1;

	private static final String AGENTS_DIR = ".github/agents";
	private static final String GITHUB_DIR = ".github";
	private static final Pattern MARKER = Pattern.compile("<!-- traza-managed v(\\d+) sha256:([0-9a-f]{64}) -->");
	private static final int DEBOUNCE_MS = 500;

	private enum State {
		ABSENT, MANAGED_CLEAN, USER, UNREADABLE
	}

	private static final Object LOCK = new Object();
	private static volatile Path managedFile;
	private static boolean installed;
	private static IResourceChangeListener resourceListener;
	private static Runnable prefListenerRemover;
	private static Job job;

	private CopilotAgentFile() {
	}

	/** Registra los listeners y programa una reconciliacion. Idempotente; no hace nada sin Copilot. */
	public static void install() {
		if (!CopilotBridge.isInstalled()) {
			return;
		}
		synchronized (LOCK) {
			if (installed) {
				return;
			}
			installed = true;
			job = new Job("Traza: modo Copilot") {
				@Override
				protected IStatus run(IProgressMonitor monitor) {
					reconcile(monitor);
					return Status.OK_STATUS;
				}
			};
			job.setSystem(true);
			resourceListener = CopilotAgentFile::onResourceChanged;
			ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
			prefListenerRemover = TrazaPreferences.addChangeListener(key -> {
				if (TrazaPreferences.COPILOT_AGENT_MODE.equals(key)) {
					scheduleReconcile();
				}
			});
		}
		scheduleReconcile();
	}

	/** Quita los listeners y cancela el Job. */
	public static void uninstall() {
		Job j;
		synchronized (LOCK) {
			if (!installed) {
				return;
			}
			installed = false;
			if (resourceListener != null) {
				ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
				resourceListener = null;
			}
			if (prefListenerRemover != null) {
				prefListenerRemover.run();
				prefListenerRemover = null;
			}
			j = job;
			job = null;
		}
		if (j != null) {
			j.cancel();
		}
	}

	/** Ruta absoluta normalizada del archivo gestionado del anfitrion actual, o null. */
	static Path managedFilePath() {
		return managedFile;
	}

	private static void scheduleReconcile() {
		Job j;
		synchronized (LOCK) {
			j = job;
		}
		if (j != null) {
			j.schedule(DEBOUNCE_MS);
		}
	}

	private static void onResourceChanged(IResourceChangeEvent event) {
		IResourceDelta root = event.getDelta();
		if (root == null) {
			return;
		}
		for (IResourceDelta child : root.getAffectedChildren()) {
			int kind = child.getKind();
			if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED
				|| (kind == IResourceDelta.CHANGED && (child.getFlags() & IResourceDelta.OPEN) != 0)) {
				scheduleReconcile();
				return;
			}
		}
	}

	// ---------------------------------------------------------------- reconciliacion

	private static void reconcile(IProgressMonitor monitor) {
		synchronized (LOCK) {
			if (!installed) {
				return;
			}
		}
		try {
			doReconcile(monitor);
		} catch (IOException | CoreException e) {
			TrazaPlugin.warn("No se pudo actualizar el modo Traza de Copilot (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
		}
	}

	private static void doReconcile(IProgressMonitor monitor) throws IOException, CoreException {
		List<IProject> candidates = candidates();
		boolean changed = false;
		boolean enabled = TrazaPreferences.copilotAgentMode();
		boolean blocked = false;
		List<IProject> clean = new ArrayList<>();
		for (IProject p : candidates) {
			State s = classify(file(p));
			if (s == State.MANAGED_CLEAN) {
				clean.add(p);
			} else if (s == State.USER || s == State.UNREADABLE) {
				blocked = true;
			}
		}
		if (monitor.isCanceled()) {
			return;
		}

		if (!enabled || blocked) {
			managedFile = null;
			for (IProject p : clean) {
				changed |= deleteManaged(p, monitor);
			}
			if (enabled && blocked) {
				TrazaPlugin.info("Hay un archivo " + REL_PATH + " propio del usuario o ilegible: Traza no crea el modo de Copilot.");
			}
			if (changed) {
				CopilotBridge.refreshModesAsync();
			}
			return;
		}
		if (candidates.isEmpty()) {
			managedFile = null;
			return;
		}

		IProject host = clean.isEmpty() ? candidates.get(0) : clean.get(0);
		for (IProject p : clean) {
			if (p != host) {
				changed |= deleteManaged(p, monitor);
			}
		}
		if (monitor.isCanceled()) {
			return;
		}
		changed |= writeHost(host, monitor);
		Path hostFile = absolute(host);
		managedFile = hostFile;
		if (hostFile != null) {
			excludeFromGit(hostFile);
		}
		if (changed) {
			CopilotBridge.refreshModesAsync();
		}
	}

	/** Proyectos accesibles de nivel superior con ubicacion en disco, ordenados por nombre. */
	private static List<IProject> candidates() {
		List<IProject> open = new ArrayList<>();
		for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (p.isAccessible() && p.getLocation() != null) {
				open.add(p);
			}
		}
		List<IProject> top = new ArrayList<>();
		for (IProject p : open) {
			IPath loc = p.getLocation();
			boolean nested = false;
			for (IProject other : open) {
				IPath ol = other.getLocation();
				if (other != p && ol.isPrefixOf(loc) && !ol.equals(loc)) {
					nested = true;
					break;
				}
			}
			if (!nested) {
				top.add(p);
			}
		}
		top.sort(Comparator.comparing((IProject p) -> p.getName().toLowerCase(Locale.ROOT)).thenComparing(IProject::getName));
		return top;
	}

	private static IFile file(IProject p) {
		return p.getFile(REL_PATH);
	}

	private static Path absolute(IProject p) {
		IPath loc = p.getLocation();
		return loc == null ? null : loc.append(REL_PATH).toFile().toPath().toAbsolutePath().normalize();
	}

	// ---------------------------------------------------------------- contenido y marcador

	private static String normalize(String s) {
		return s.replace("\r\n", "\n").replace('\r', '\n');
	}

	private static String templateBody() throws IOException {
		String t = normalize(BundleFiles.readText(TEMPLATE));
		return t.endsWith("\n") ? t : t + "\n";
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 no disponible", e);
		}
	}

	private static String withMarker(String body) {
		return body + "<!-- traza-managed v" + FORMAT_VERSION + " sha256:" + sha256(body) + " -->\n";
	}

	/** Cuerpo (todo lo anterior al marcador) si el contenido es gestionado y esta sin modificar; si no, null. */
	private static String cleanBody(String raw) {
		String t = normalize(raw).stripTrailing();
		int idx = t.lastIndexOf('\n') + 1;
		Matcher m = MARKER.matcher(t.substring(idx));
		if (!m.matches()) {
			return null;
		}
		String body = t.substring(0, idx);
		return sha256(body).equals(m.group(2)) ? body : null;
	}

	private static State classify(IFile f) {
		IPath loc = f.getLocation();
		if (loc == null) {
			return State.UNREADABLE;
		}
		Path p = loc.toFile().toPath();
		if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
			return State.ABSENT;
		}
		try {
			if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
				return State.USER;
			}
			return cleanBody(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)) != null ? State.MANAGED_CLEAN : State.USER;
		} catch (IOException e) {
			TrazaPlugin.warn("No se pudo leer " + p + " (" + e.getClass().getSimpleName() + ").");
			return State.UNREADABLE;
		}
	}

	// ---------------------------------------------------------------- escritura / borrado (siempre fuera del listener)

	/** Borra el archivo si sigue siendo gestionado y sin modificar. Devuelve true si borro algo. */
	private static boolean deleteManaged(IProject project, IProgressMonitor monitor) throws CoreException {
		boolean[] done = { false };
		ResourcesPlugin.getWorkspace().run(m -> {
			IFile f = file(project);
			if (classify(f) != State.MANAGED_CLEAN) {
				return;
			}
			f.delete(IResource.FORCE, m);
			done[0] = true;
			deleteIfEmpty(project.getFolder(AGENTS_DIR), m);
			deleteIfEmpty(project.getFolder(GITHUB_DIR), m);
		}, project, IWorkspace.AVOID_UPDATE, monitor);
		if (done[0]) {
			TrazaPlugin.info("Archivo de agente de Traza eliminado: " + absolute(project));
		}
		return done[0];
	}

	private static void deleteIfEmpty(IFolder folder, IProgressMonitor monitor) throws CoreException {
		IPath loc = folder.getLocation();
		if (loc == null) {
			return;
		}
		Path dir = loc.toFile().toPath();
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> s = Files.list(dir)) {
			if (s.findAny().isPresent()) {
				return;
			}
		} catch (IOException e) {
			TrazaPlugin.warn("No se pudo listar " + dir + " (" + e.getClass().getSimpleName() + ").");
			return;
		}
		folder.delete(IResource.FORCE, monitor);
	}

	/** Escribe plantilla + marcador en el anfitrion si falta o esta desactualizado. Devuelve true si escribio. */
	private static boolean writeHost(IProject project, IProgressMonitor monitor) throws IOException, CoreException {
		String body = templateBody();
		boolean[] done = { false };
		ResourcesPlugin.getWorkspace().run(m -> {
			IFile f = file(project);
			State s = classify(f);
			if (s == State.USER || s == State.UNREADABLE) {
				return;
			}
			if (s == State.MANAGED_CLEAN && body.equals(currentBody(f))) {
				return;
			}
			byte[] bytes = withMarker(body).getBytes(StandardCharsets.UTF_8);
			ensureFolder(project.getFolder(GITHUB_DIR), m);
			ensureFolder(project.getFolder(AGENTS_DIR), m);
			if (f.exists()) {
				f.setContents(new ByteArrayInputStream(bytes), IResource.FORCE, m);
			} else {
				f.create(new ByteArrayInputStream(bytes), IResource.FORCE, m);
			}
			done[0] = true;
		}, project, IWorkspace.AVOID_UPDATE, monitor);
		if (done[0]) {
			TrazaPlugin.info("Archivo de agente de Traza escrito: " + absolute(project));
		}
		return done[0];
	}

	private static String currentBody(IFile f) {
		IPath loc = f.getLocation();
		if (loc == null) {
			return null;
		}
		try {
			return cleanBody(new String(Files.readAllBytes(loc.toFile().toPath()), StandardCharsets.UTF_8));
		} catch (IOException e) {
			TrazaPlugin.warn("No se pudo leer " + loc + " (" + e.getClass().getSimpleName() + ").");
			return null;
		}
	}

	private static void ensureFolder(IFolder folder, IProgressMonitor monitor) throws CoreException {
		if (!folder.exists()) {
			folder.create(IResource.FORCE, true, monitor);
		}
	}

	// ---------------------------------------------------------------- exclude de Git

	/** Anade la ruta del archivo a {@code <gitdir>/info/exclude} (sin duplicados). Omite worktrees/submodulos y enlaces simbolicos. */
	private static void excludeFromGit(Path file) {
		try {
			Path dir = file.getParent();
			while (dir != null) {
				Path git = dir.resolve(".git");
				if (Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)) {
					addExclude(dir, git, file);
					return;
				}
				if (Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS)) {
					TrazaPlugin.info("El repositorio de " + dir + " usa un .git de tipo archivo (worktree/submodulo): no se anade el exclude.");
					return;
				}
				dir = dir.getParent();
			}
		} catch (IOException | RuntimeException e) {
			TrazaPlugin.warn("No se pudo actualizar .git/info/exclude (" + e.getClass().getSimpleName() + ").");
		}
	}

	private static void addExclude(Path repoRoot, Path gitDir, Path file) throws IOException {
		StringBuilder rel = new StringBuilder();
		for (Path seg : repoRoot.relativize(file)) {
			rel.append('/').append(seg.toString());
		}
		String line = rel.toString();
		Path info = gitDir.resolve("info");
		Path exclude = info.resolve("exclude");
		String existing = "";
		if (Files.exists(exclude, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(exclude, LinkOption.NOFOLLOW_LINKS)) {
				return;
			}
			existing = new String(Files.readAllBytes(exclude), StandardCharsets.UTF_8);
			for (String l : existing.split("\r?\n", -1)) {
				if (l.equals(line)) {
					return;
				}
			}
		} else if (!Files.isDirectory(info, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectories(info);
		}
		String add = (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + line + "\n";
		Files.write(exclude, add.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		TrazaPlugin.info("Anadido " + line + " a " + exclude);
	}
}
