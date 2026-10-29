package com.ftejero21.traza.db;

import java.util.ArrayList;
import java.util.List;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.prefs.TrazaPreferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Lectura y escritura de las conexiones en la preferencia `databases` (JSON). Las invalidas o repetidas se ignoran con un aviso. */
public final class DbConfigStore {
	private DbConfigStore() {
	}

	private static JsonArray raw() {
		try {
			JsonElement e = J.parse(TrazaPreferences.databasesJson());
			return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
		} catch (RuntimeException ex) {
			TrazaPlugin.warn("La preferencia de bases de datos no es un JSON valido; se ignora.");
			return new JsonArray();
		}
	}

	/** Conexiones validas. */
	public static synchronized List<DbConfig> readAll() {
		List<DbConfig> out = new ArrayList<>();
		for (JsonElement item : raw()) {
			DbConfig c = DbConfig.parse(item);
			boolean dup = false;
			if (c != null) {
				for (DbConfig o : out) {
					if (o.id.equals(c.id)) {
						dup = true;
					}
				}
			}
			if (c == null || dup) {
				TrazaPlugin.warn("Conexion de base de datos no valida en la preferencia \"databases\": se ignora.");
				continue;
			}
			out.add(c);
		}
		return out;
	}

	public static synchronized DbConfig find(String id) {
		for (DbConfig c : readAll()) {
			if (c.id.equals(id)) {
				return c;
			}
		}
		return null;
	}

	/** Anade la conexion conservando el resto de entradas tal cual. */
	public static synchronized void append(DbConfig config) {
		JsonArray list = raw();
		list.add(config.toJson());
		TrazaPreferences.setDatabasesJson(J.GSON.toJson(list));
	}

	public static synchronized void replace(DbConfig config) {
		JsonArray out = new JsonArray();
		boolean replaced = false;
		for (JsonElement item : raw()) {
			JsonObject o = J.obj(item);
			if (o != null && config.id.equals(J.str(o, "id"))) {
				out.add(config.toJson());
				replaced = true;
			} else {
				out.add(item);
			}
		}
		if (!replaced) {
			out.add(config.toJson());
		}
		TrazaPreferences.setDatabasesJson(J.GSON.toJson(out));
	}

	public static synchronized void remove(String id) {
		JsonArray out = new JsonArray();
		for (JsonElement item : raw()) {
			JsonObject o = J.obj(item);
			if (o != null && id.equals(J.str(o, "id"))) {
				continue;
			}
			out.add(item);
		}
		TrazaPreferences.setDatabasesJson(J.GSON.toJson(out));
	}
}
