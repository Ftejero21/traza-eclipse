package com.ftejero21.traza.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.IResourceProxyVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ui.Ui;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Port de host-files.ts sobre el workspace de Eclipse. Montajes = proyectos abiertos. Nunca lista ni lee archivos
 * sensibles; la escritura esta limitada a rutas normalizadas dentro de un proyecto abierto.
 */
public final class WorkspaceFiles {
	private static final int MAX_FILES = 20_000;
	private static final int MAX_TEXT_BYTES = 3 * 1024 * 1024;
	private static final int MAX_BASE64_BYTES = 3 * 1024 * 1024;
	private static final Set<String> EXCLUDE = Set.of("node_modules", "dist", "build", "target", "out", ".git", ".angular", "coverage", ".next",
		".m2", ".gradle", ".venv", "venv", ".pnpm-store", ".cache", ".vscode-test", "__pycache__");
	private static final Set<String> WRITE_FORBIDDEN_SEGMENTS = Set.of(".git", "node_modules");

	private WorkspaceFiles() {
	}

	/* ------------------------------ tipos ------------------------------ */

	public static final class Mount {
		public final String id;
		public final String name;
		public final String alias;
		public final String path;

		Mount(String id, String name, String alias, String path) {
			this.id = id;
			this.name = name;
			this.alias = alias;
			this.path = path;
		}

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("name", name);
			o.addProperty("alias", alias);
			o.addProperty("path", path);
			return o;
		}
	}

	public static final class ProjectFile {
		public final String id;
		public final String path;
		public final String name;
		public final String extension;
		public final boolean binary;

		ProjectFile(String id, String path, String name, String extension, boolean binary) {
			this.id = id;
			this.path = path;
			this.name = name;
			this.extension = extension;
			this.binary = binary;
		}

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("path", path);
			o.addProperty("name", name);
			o.addProperty("extension", extension);
			o.addProperty("binary", binary);
			return o;
		}
	}

	public static final class Snapshot {
		public final List<Mount> mounts = new ArrayList<>();
		public final List<ProjectFile> files = new ArrayList<>();

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			JsonArray m = new JsonArray();
			mounts.forEach(x -> m.add(x.toJson()));
			JsonArray f = new JsonArray();
			files.forEach(x -> f.add(x.toJson()));
			o.add("mounts", m);
			o.add("files", f);
			return o;
		}
	}

	public static final class ReadResult {
		public final boolean ok;
		public final boolean binary;
		public final String content;

		ReadResult(boolean ok, boolean binary, String content) {
			this.ok = ok;
			this.binary = binary;
			this.content = content;
		}

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("ok", ok);
			o.addProperty("binary", binary);
			o.addProperty("content", content);
			return o;
		}
	}

	/* ------------------------------ proyectos ------------------------------ */

	public static List<IProject> openProjects() {
		List<IProject> out = new ArrayList<>();
		for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (p.isOpen()) {
				out.add(p);
			}
		}
		return out;
	}

	public static boolean hasProjects() {
		return !openProjects().isEmpty();
	}

	private static IFile fileOf(String fileId) {
		URI uri;
		try {
			uri = new URI(fileId);
		} catch (java.net.URISyntaxException e) {
			TrazaPlugin.warn("Identificador de archivo no valido.");
			return null;
		}
		IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(uri);
		for (IFile f : files) {
			if (f.getProject().isOpen() && f.isAccessible() && !Guards.isSensitiveCodeFile(f.getName())) {
				return f;
			}
		}
		return null;
	}

	/** Archivo validado: proyecto abierto, existe y no sensible. */
	public static IFile resolve(String fileId) {
		return fileId == null ? null : fileOf(fileId);
	}

	/** Archivo de un montaje (proyecto) por nombre y ruta relativa; null si no existe o no esta permitido. */
	public static IFile mountFile(String mountId, String path) {
		List<IProject> projects = openProjects();
		IProject project = null;
		for (IProject p : projects) {
			if (p.getName().equals(mountId)) {
				project = p;
				break;
			}
		}
		if (project == null && (mountId == null || mountId.isEmpty()) && projects.size() == 1) {
			project = projects.get(0);
		}
		String rel = Guards.normalizeCodePath(path);
		if (project == null || rel == null || rel.isEmpty() || Guards.isSensitiveCodeFile(Guards.baseName(rel))) {
			return null;
		}
		IFile f = project.getFile(new Path(rel));
		return f.isAccessible() ? f : null;
	}

	/* ------------------------------ lectura ------------------------------ */

	private static String liveText(IFile file) {
		try {
			ITextFileBufferManager mgr = FileBuffers.getTextFileBufferManager();
			if (mgr == null) {
				return null;
			}
			ITextFileBuffer buf = mgr.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
			if (buf == null) {
				return null;
			}
			return buf.getDocument().get();
		} catch (RuntimeException e) {
			TrazaPlugin.warn("No se pudo leer el buffer de un archivo abierto (" + e.getClass().getSimpleName() + ").");
			return null;
		}
	}

	private static byte[] readBytes(IFile file, int maxBytes) throws CoreException, IOException {
		try (InputStream in = file.getContents(true)) {
			byte[] bytes = in.readNBytes(maxBytes + 1);
			return bytes.length > maxBytes ? null : bytes;
		}
	}

	private static Charset charsetOf(IFile file) {
		try {
			return Charset.forName(file.getCharset());
		} catch (Exception e) {
			return StandardCharsets.UTF_8;
		}
	}

	/** Lee texto (respetando cambios sin guardar de un archivo abierto) o base64. */
	private static ReadResult readFile(IFile file, String as, Long maxBytes) throws CoreException, IOException {
		if (as.equals("text")) {
			String live = liveText(file);
			if (live != null) {
				if (live.length() > MAX_TEXT_BYTES) {
					return new ReadResult(false, false, "");
				}
				return live.indexOf('\u0000') >= 0 ? new ReadResult(true, true, "") : new ReadResult(true, false, live);
			}
			byte[] bytes = readBytes(file, MAX_TEXT_BYTES);
			if (bytes == null) {
				return new ReadResult(false, false, "");
			}
			String content = new String(bytes, charsetOf(file));
			return content.indexOf('\u0000') >= 0 ? new ReadResult(true, true, "") : new ReadResult(true, false, content);
		}
		int limit = (int) Math.min(maxBytes == null ? MAX_BASE64_BYTES : maxBytes, MAX_BASE64_BYTES);
		byte[] bytes = readBytes(file, limit);
		return bytes == null ? new ReadResult(false, true, "") : new ReadResult(true, true, Base64.getEncoder().encodeToString(bytes));
	}

	public static ReadResult readFile(String fileId, String as, Long maxBytes) {
		IFile file = resolve(fileId);
		if (file == null) {
			return new ReadResult(false, false, "");
		}
		try {
			return readFile(file, as, maxBytes);
		} catch (CoreException | IOException e) {
			TrazaPlugin.warn("No se pudo leer el archivo (" + e.getClass().getSimpleName() + ").");
			return new ReadResult(false, false, "");
		}
	}

	/** Archivo por montaje y ruta relativa; null si no existe o no esta permitido. */
	public static String readMountFile(String mountId, String path, String as, long maxBytes) {
		IFile file = mountFile(mountId, path);
		if (file == null) {
			return null;
		}
		try {
			ReadResult read = readFile(file, as, maxBytes);
			if (!read.ok || (as.equals("text") && read.binary)) {
				return null;
			}
			if (as.equals("text") && read.content.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
				return null;
			}
			return read.content;
		} catch (CoreException | IOException e) {
			TrazaPlugin.warn("No se pudo leer el archivo del montaje (" + e.getClass().getSimpleName() + ").");
			return null;
		}
	}

	/** Texto de un archivo del contexto (null si no se puede leer, es binario o supera maxBytes). */
	public static String readText(ProjectFile file, int maxBytes) {
		IFile f = resolve(file.id);
		if (f == null) {
			return null;
		}
		try {
			String live = liveText(f);
			if (live == null) {
				byte[] bytes = readBytes(f, maxBytes);
				if (bytes == null) {
					return null;
				}
				live = new String(bytes, charsetOf(f));
			}
			if (live.indexOf('\u0000') >= 0) {
				return null;
			}
			return live.length() > maxBytes ? null : live;
		} catch (CoreException | IOException e) {
			TrazaPlugin.warn("No se pudo leer un archivo del contexto (" + e.getClass().getSimpleName() + ").");
			return null;
		}
	}

	/* ------------------------------ listado ------------------------------ */

	/** Archivos del workspace (solo rutas). Nunca lista archivos sensibles. */
	public static Snapshot listFiles() {
		Snapshot snapshot = new Snapshot();
		List<IProject> projects = openProjects();
		if (projects.isEmpty()) {
			return snapshot;
		}
		boolean multi = projects.size() > 1;
		for (IProject p : projects) {
			snapshot.mounts.add(new Mount(p.getName(), p.getName(), p.getName(), multi ? p.getName() : ""));
		}
		Set<String> seen = new HashSet<>();
		AtomicInteger count = new AtomicInteger();
		for (IProject project : projects) {
			if (count.get() >= MAX_FILES) {
				break;
			}
			try {
				project.accept((IResourceProxyVisitor) proxy -> visit(proxy, project, multi, snapshot, seen, count), IResource.NONE);
			} catch (CoreException e) {
				TrazaPlugin.warn("No se pudo recorrer un proyecto (" + e.getClass().getSimpleName() + ").");
			}
		}
		return snapshot;
	}

	private static boolean visit(IResourceProxy proxy, IProject project, boolean multi, Snapshot snapshot, Set<String> seen, AtomicInteger count) {
		if (count.get() >= MAX_FILES) {
			return false;
		}
		int type = proxy.getType();
		if (type == IResource.PROJECT) {
			return true;
		}
		if (proxy.isDerived()) {
			return false;
		}
		if (type == IResource.FOLDER) {
			return !EXCLUDE.contains(proxy.getName());
		}
		if (type != IResource.FILE) {
			return false;
		}
		String name = proxy.getName();
		if (Guards.isSensitiveCodeFile(name)) {
			return false;
		}
		String rel = proxy.requestFullPath().removeFirstSegments(1).toString();
		String path = multi ? Guards.normalizeCodePath(project.getName() + "/" + rel) : Guards.normalizeCodePath(rel);
		if (path == null || path.isEmpty() || seen.contains(path)) {
			return false;
		}
		IResource res = proxy.requestResource();
		URI uri = res == null ? null : res.getLocationURI();
		if (uri == null) {
			return false;
		}
		seen.add(path);
		String ext = Guards.codeExtension(name);
		snapshot.files.add(new ProjectFile(uri.toString(), path, name, ext, Guards.isBinaryCodeExtension(ext)));
		count.incrementAndGet();
		return false;
	}

	/* ------------------------------ abrir en el editor ------------------------------ */

	private static void reveal(IEditorPart editor, int line) {
		if (!(editor instanceof ITextEditor)) {
			return;
		}
		ITextEditor te = (ITextEditor) editor;
		IDocumentProvider dp = te.getDocumentProvider();
		if (dp == null) {
			return;
		}
		IDocument doc = dp.getDocument(te.getEditorInput());
		if (doc == null) {
			return;
		}
		try {
			int n = Math.min(Math.max(1, line), Math.max(1, doc.getNumberOfLines())) - 1;
			IRegion region = doc.getLineInformation(n);
			te.selectAndReveal(region.getOffset(), 0);
		} catch (BadLocationException e) {
			TrazaPlugin.warn("Linea fuera de rango al abrir el archivo.");
		}
	}

	/** «Abrir en el editor»: archivo del workspace (o archivo temporal con el contenido del ejemplo) en la linea. UI. */
	public static void openRef(String fileId, int line, JsonObject sample) {
		try {
			IWorkbenchPage page = Ui.page();
			if (page == null) {
				return;
			}
			if (sample != null) {
				String rawPath = J.str(sample, "path");
				String norm = Guards.normalizeCodePath(rawPath);
				String base = norm == null || norm.isEmpty() ? "sample.txt" : Guards.baseName(norm);
				java.nio.file.Path dir = java.nio.file.Paths.get(TrazaPlugin.getDefault().getStateLocation().append("samples").toOSString());
				Files.createDirectories(dir);
				java.nio.file.Path target = dir.resolve(base);
				Files.write(target, J.str(sample, "content").getBytes(StandardCharsets.UTF_8));
				IEditorPart editor = IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(target.toUri()));
				reveal(editor, line);
				return;
			}
			IFile file = resolve(fileId);
			if (file == null) {
				return;
			}
			reveal(IDE.openEditor(page, file), line);
		} catch (Exception e) {
			TrazaPlugin.warn("No se pudo abrir el archivo en el editor (" + e.getClass().getSimpleName() + ").");
			Ui.warning("Traza", Ui.t("No se pudo abrir el archivo en el editor.", "The file could not be opened in the editor."));
		}
	}

	/* ------------------------------ escritura ------------------------------ */

	/** Fichero de escritura validado de una ruta de la webview (no confiable); null si no cumple. */
	public static IFile writableFile(String raw) {
		List<IProject> projects = openProjects();
		String rel = Guards.normalizeCodePath(raw);
		if (projects.isEmpty() || rel == null || rel.isEmpty()) {
			return null;
		}
		IProject project = null;
		String inner = rel;
		if (projects.size() > 1) {
			int slash = rel.indexOf('/');
			if (slash < 0) {
				return null;
			}
			String name = rel.substring(0, slash);
			for (IProject p : projects) {
				if (p.getName().equals(name)) {
					project = p;
				}
			}
			inner = rel.substring(slash + 1);
		} else {
			project = projects.get(0);
		}
		if (project == null || inner.isEmpty()) {
			return null;
		}
		String[] segments = inner.split("/");
		String name = segments[segments.length - 1];
		if (Guards.isSensitiveCodeFile(name) || Guards.isBinaryCodeExtension(Guards.codeExtension(name))) {
			return null;
		}
		for (String s : segments) {
			if (WRITE_FORBIDDEN_SEGMENTS.contains(s.toLowerCase(Locale.ROOT))) {
				return null;
			}
		}
		return project.getFile(new Path(inner));
	}

	private static JsonObject result(boolean ok, String error) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", ok);
		if (error != null) {
			o.addProperty("error", error);
		}
		return o;
	}

	private static final class Target {
		IFile file;
		String content;
		Integer line;
	}

	private static void ensureParents(IContainer c, IProgressMonitor m) throws CoreException {
		if (c instanceof IFolder && !c.exists()) {
			ensureParents(c.getParent(), m);
			((IFolder) c).create(true, true, m);
		}
	}

	/**
	 * Aplica las escrituras de una propuesta (reemplazo completo, archivo nuevo o borrado) dentro de una operacion del
	 * workspace, y abre el primero en su linea. Nunca confia en las rutas de la webview. Llamar desde un hilo de fondo.
	 */
	public static JsonObject applyFiles(JsonArray writes) {
		List<Target> targets = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonElement el : writes) {
			JsonObject w = el.getAsJsonObject();
			IFile file = writableFile(J.str(w, "path"));
			if (file == null || !seen.add(file.getFullPath().toString())) {
				return result(false, "Ruta no permitida para escribir.");
			}
			Target t = new Target();
			t.file = file;
			t.content = J.isStr(w.get("content")) ? w.get("content").getAsString() : null;
			Long line = J.integer(w.get("line"));
			t.line = line == null ? null : line.intValue();
			targets.add(t);
		}
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		String[] failure = new String[1];
		try {
			workspace.run((IWorkspaceRunnable) monitor -> {
				IProgressMonitor m = monitor == null ? new NullProgressMonitor() : monitor;
				for (Target t : targets) {
					if (t.content == null) {
						if (t.file.exists()) {
							t.file.delete(IResource.KEEP_HISTORY, m);
						}
					} else if (t.file.exists()) {
						writeExisting(t.file, t.content, m);
					} else {
						ensureParents(t.file.getParent(), m);
						Charset cs = charsetFor(t.file);
						t.file.create(new ByteArrayInputStream(t.content.getBytes(cs)), true, m);
					}
				}
			}, null, IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
		} catch (CoreException e) {
			TrazaPlugin.error("No se pudieron aplicar los cambios.", e);
			failure[0] = "No se pudieron aplicar los cambios.";
		}
		if (failure[0] != null) {
			return result(false, failure[0]);
		}
		Target first = null;
		for (Target t : targets) {
			if (t.content != null) {
				first = t;
				break;
			}
		}
		if (first != null) {
			final Target open = first;
			Ui.async(() -> {
				try {
					IWorkbenchPage page = Ui.page();
					if (page != null) {
						reveal(IDE.openEditor(page, open.file), open.line == null ? 1 : open.line);
					}
				} catch (CoreException e) {
					TrazaPlugin.warn("No se pudo abrir el archivo modificado (" + e.getClass().getSimpleName() + ").");
				}
			});
		}
		return result(true, null);
	}

	private static Charset charsetFor(IFile file) {
		try {
			return Charset.forName(file.getParent().getDefaultCharset());
		} catch (Exception e) {
			return StandardCharsets.UTF_8;
		}
	}

	private static void writeExisting(IFile file, String content, IProgressMonitor m) throws CoreException {
		ITextFileBufferManager mgr = FileBuffers.getTextFileBufferManager();
		mgr.connect(file.getFullPath(), LocationKind.IFILE, m);
		try {
			ITextFileBuffer buf = mgr.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
			buf.getDocument().set(content);
			buf.commit(m, true);
		} finally {
			mgr.disconnect(file.getFullPath(), LocationKind.IFILE, m);
		}
	}

}
