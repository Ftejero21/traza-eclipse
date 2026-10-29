package com.ftejero21.traza.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.osgi.framework.Bundle;

import com.ftejero21.traza.TrazaPlugin;

/** Lectura de recursos empaquetados en el bundle (solo con rutas fijas internas). */
public final class BundleFiles {
	private BundleFiles() {
	}

	public static Bundle bundle() {
		TrazaPlugin p = TrazaPlugin.getDefault();
		if (p != null && p.getBundle() != null) {
			return p.getBundle();
		}
		return org.eclipse.core.runtime.Platform.getBundle(TrazaPlugin.ID);
	}

	public static URL entry(String path) {
		Bundle b = bundle();
		return b == null ? null : b.getEntry(path);
	}

	public static byte[] readBytes(String path) throws IOException {
		URL url = entry(path);
		if (url == null) {
			throw new IOException("Recurso no encontrado: " + path);
		}
		try (InputStream in = url.openStream()) {
			return in.readAllBytes();
		}
	}

	public static String readText(String path) throws IOException {
		return new String(readBytes(path), StandardCharsets.UTF_8);
	}
}
