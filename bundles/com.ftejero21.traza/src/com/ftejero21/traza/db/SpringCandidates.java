package com.ftejero21.traza.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.core.WorkspaceFiles;

/** Datasources de Spring (application*.properties): solo URL y usuario; nunca la contrasena. */
public final class SpringCandidates {
	private static final int MAX_FILES = 20;
	private static final int MAX_BYTES = 256 * 1024;
	private static final Set<String> SKIP = Set.of("node_modules", "target", "build");
	private static final Pattern JDBC = Pattern.compile("^jdbc:(postgresql|mysql|mariadb)://([^/:?#\\s]+)(?::(\\d{1,5}))?/([^?;#\\s/]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern PROP = Pattern.compile("^spring\\.datasource\\.(url|username)\\s*[=:]\\s*(.*)$");
	private static final Pattern FILE_NAME = Pattern.compile("^application.*\\.properties$");

	public static final class Candidate {
		public String provider;
		public String host;
		public int port;
		public String database;
		public String user;
		public String root;
		public String file;
	}

	private SpringCandidates() {
	}

	public static List<Candidate> find() {
		List<Candidate> out = new ArrayList<>();
		List<IProject> projects = WorkspaceFiles.openProjects();
		boolean multi = projects.size() > 1;
		List<IFile> files = new ArrayList<>();
		for (IProject project : projects) {
			if (files.size() >= MAX_FILES) {
				break;
			}
			try {
				project.accept(proxy -> {
					if (files.size() >= MAX_FILES) {
						return false;
					}
					if (proxy.getType() == IResource.FOLDER) {
						return !SKIP.contains(proxy.getName());
					}
					if (proxy.getType() == IResource.FILE && FILE_NAME.matcher(proxy.getName()).matches()) {
						String p = proxy.requestFullPath().toString();
						if (p.contains("/src/main/resources/")) {
							files.add((IFile) proxy.requestResource());
						}
						return false;
					}
					return true;
				}, IResource.NONE);
			} catch (CoreException e) {
				TrazaPlugin.warn("No se pudieron buscar los application.properties (" + e.getClass().getSimpleName() + ").");
			}
		}
		for (IFile file : files) {
			String content;
			try (InputStream in = file.getContents(true)) {
				byte[] bytes = in.readNBytes(MAX_BYTES + 1);
				if (bytes.length > MAX_BYTES) {
					continue;
				}
				content = new String(bytes, StandardCharsets.UTF_8);
			} catch (CoreException | IOException e) {
				TrazaPlugin.warn("No se pudo leer un application.properties (" + e.getClass().getSimpleName() + ").");
				continue;
			}
			String url = "";
			String user = "";
			for (String rawLine : content.split("\r?\n")) {
				String line = rawLine.trim();
				if (line.startsWith("#") || line.startsWith("!")) {
					continue;
				}
				Matcher m = PROP.matcher(line);
				if (!m.find() || m.group(2).contains("${")) {
					continue;
				}
				if (m.group(1).equals("url")) {
					url = m.group(2).trim();
				} else {
					user = m.group(2).trim();
				}
			}
			Matcher jdbc = JDBC.matcher(url);
			if (!jdbc.find()) {
				continue;
			}
			String provider = jdbc.group(1).toLowerCase(Locale.ROOT);
			String projectRel = file.getProjectRelativePath().toString();
			String rel = multi ? file.getProject().getName() + "/" + projectRel : projectRel;
			int at = rel.indexOf("src/main/resources/");
			String root = Guards.normalizeCodePath(at > 0 ? rel.substring(0, at) : "");
			Candidate c = new Candidate();
			c.provider = provider;
			c.host = jdbc.group(2);
			c.port = jdbc.group(3) != null ? Integer.parseInt(jdbc.group(3)) : provider.equals("postgresql") ? 5432 : 3306;
			c.database = jdbc.group(4);
			c.user = user.length() > DbConfig.MAX_NAME ? user.substring(0, DbConfig.MAX_NAME) : user;
			c.root = root == null ? "" : root;
			c.file = rel;
			out.add(c);
		}
		return out;
	}
}
