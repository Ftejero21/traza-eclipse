package com.ftejero21.traza.core;

import java.util.Locale;
import java.util.Set;

/** Port de workspace-code-context-exclude.ts: rutas que nunca forman parte del contexto de la IA. */
public final class ContextExclude {
	private static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", "dist", "build", "target", "out", ".git", ".angular", ".next",
		"coverage", "vendor", "__pycache__", ".idea", ".vscode", ".m2", ".gradle", ".venv", "venv", ".pnpm-store", ".cache", ".vscode-test",
		"obj", ".nuxt", ".svelte-kit", ".turbo", ".parcel-cache", ".terraform");
	private static final Set<String> LOCKFILES = Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock", "poetry.lock",
		"cargo.lock", "gemfile.lock");
	private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
		"png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg", "tif", "tiff", "avif", "heic", "psd",
		"woff", "woff2", "ttf", "otf", "eot",
		"mp3", "wav", "ogg", "flac", "aac", "m4a", "mp4", "webm", "mov", "avi", "mkv",
		"zip", "gz", "tgz", "rar", "7z", "tar", "bz2", "xz", "jar", "war", "ear", "pdf",
		"exe", "dll", "so", "dylib", "bin", "class", "o", "obj", "a", "lib", "pyc", "pyo", "wasm", "db", "sqlite", "sqlite3", "dat",
		"pom", "sha1", "sha256", "sha512", "md5", "asc", "repositories", "lastupdated", "pdb", "nupkg", "ser", "log",
		"m4v", "wmv", "flv", "opus", "iso", "dmg", "msi", "apk", "aab", "ipa", "docx", "xlsx", "pptx", "glb", "parquet");

	private ContextExclude() {
	}

	/** Ruta con algun segmento de directorio excluido; no mira extension ni lockfiles. */
	public static boolean isContextExcludedDir(String path) {
		String[] segments = (path == null ? "" : path).split("/", -1);
		for (int i = 0; i < segments.length - 1; i++) {
			if (EXCLUDED_DIRS.contains(segments[i].toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/** Rutas que nunca forman parte del contexto de la IA (dependencias, salidas de build, lockfiles, binarios...). */
	public static boolean isContextExcludedPath(String path) {
		String[] segments = (path == null ? "" : path).split("/", -1);
		String name = segments[segments.length - 1].toLowerCase(Locale.ROOT);
		for (int i = 0; i < segments.length - 1; i++) {
			if (EXCLUDED_DIRS.contains(segments[i].toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		if (LOCKFILES.contains(name) || name.endsWith(".lock") || name.endsWith(".min.js") || name.endsWith(".map")) {
			return true;
		}
		int dot = name.lastIndexOf('.');
		return dot > 0 && EXCLUDED_EXTENSIONS.contains(name.substring(dot + 1));
	}
}
