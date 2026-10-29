package com.ftejero21.traza.db;

import java.io.IOException;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import com.ftejero21.traza.TrazaPlugin;

/** Contrasenas de las bases de datos: solo en el secure storage de Eclipse (nunca en preferencias, logs ni webview). */
public final class DbPasswords {
	private static final String NODE = "/com.ftejero21.traza/db";

	private DbPasswords() {
	}

	private static String key(String id) {
		return "password." + id;
	}

	/** Contrasena guardada, o null si no hay (o el almacen no es accesible). */
	public static String get(String id) {
		try {
			return SecurePreferencesFactory.getDefault().node(NODE).get(key(id), null);
		} catch (StorageException e) {
			TrazaPlugin.error("No se pudo leer una contrasena del almacen seguro.", e);
			return null;
		}
	}

	public static boolean has(String id) {
		String p = get(id);
		return p != null && !p.isEmpty();
	}

	/** Guarda la contrasena cifrada. Devuelve false (y registra el error sin el valor) si falla. */
	public static boolean set(String id, String password) {
		try {
			ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE);
			node.put(key(id), password, true);
			node.flush();
			return true;
		} catch (StorageException | IOException e) {
			TrazaPlugin.error("No se pudo guardar la contrasena en el almacen seguro.", e);
			return false;
		}
	}

	public static void remove(String id) {
		try {
			ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE);
			node.remove(key(id));
			node.flush();
		} catch (IOException e) {
			TrazaPlugin.error("No se pudo borrar la contrasena del almacen seguro.", e);
		}
	}
}
