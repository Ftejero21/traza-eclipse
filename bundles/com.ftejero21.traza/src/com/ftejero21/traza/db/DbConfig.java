package com.ftejero21.traza.db;

import java.util.List;
import java.util.regex.Pattern;

import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.core.J;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Conexion de base de datos configurada (sin contrasena). Port de DbConfig y parseConfig de host-db.ts. */
public final class DbConfig {
	public static final List<String> PROVIDERS = List.of("postgresql", "mysql", "mariadb");
	public static final int MAX_NAME = 128;
	public static final Pattern NAME_PATTERN = Pattern.compile("^[^\\u0000-\\u001f]{1,128}$");
	public static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-_:\\[\\]]{1,253}$");

	public String id;
	public String name;
	public String provider;
	public String host;
	public int port;
	public String database;
	public String user;
	public String schema;
	public String root;
	public boolean ssl;

	private static String str(JsonElement value, int max) {
		return J.isStr(value) && !value.getAsString().trim().isEmpty() && value.getAsString().length() <= max ? value.getAsString().trim() : null;
	}

	public static boolean isLocalHost(String host) {
		return Guards.LOCAL_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT));
	}

	/** Entrada de la preferencia validada; null si no es valida. */
	public static DbConfig parse(JsonElement raw) {
		JsonObject o = J.obj(raw);
		if (o == null) {
			return null;
		}
		String id = str(o.get("id"), 40);
		String name = str(o.get("name"), MAX_NAME);
		String provider = J.isStr(o.get("provider")) ? o.get("provider").getAsString() : null;
		String host = str(o.get("host"), 253);
		JsonElement port = o.get("port");
		String database = str(o.get("database"), MAX_NAME);
		String user = str(o.get("user"), MAX_NAME);
		if (id == null || !Guards.DB_KEY_PATTERN.matcher(id).matches() || name == null || !NAME_PATTERN.matcher(name).matches()
			|| provider == null || !PROVIDERS.contains(provider)) {
			return null;
		}
		Long p = J.integer(port);
		if (host == null || !HOST_PATTERN.matcher(host).matches() || p == null || p < 1 || p > 65535) {
			return null;
		}
		if (database == null || user == null) {
			return null;
		}
		DbConfig c = new DbConfig();
		c.id = id;
		c.name = name;
		c.provider = provider;
		c.host = host;
		c.port = p.intValue();
		c.database = database;
		c.user = user;
		String schema = o.has("schema") ? str(o.get("schema"), MAX_NAME) : null;
		if (o.has("schema") && !(J.isStr(o.get("schema")) && o.get("schema").getAsString().isEmpty()) && schema == null) {
			return null;
		}
		if (schema != null) {
			c.schema = schema;
		}
		if (J.isStr(o.get("root")) && !o.get("root").getAsString().trim().isEmpty()) {
			String root = Guards.normalizeCodePath(o.get("root").getAsString());
			if (root == null) {
				return null;
			}
			c.root = root;
		}
		if (o.has("ssl") && !J.isBool(o.get("ssl"))) {
			return null;
		}
		if (J.isBool(o.get("ssl")) && o.get("ssl").getAsBoolean()) {
			c.ssl = true;
		}
		return c;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("name", name);
		o.addProperty("provider", provider);
		o.addProperty("host", host);
		o.addProperty("port", port);
		o.addProperty("database", database);
		o.addProperty("user", user);
		if (schema != null && !schema.isEmpty()) {
			o.addProperty("schema", schema);
		}
		if (root != null) {
			o.addProperty("root", root);
		}
		if (ssl) {
			o.addProperty("ssl", true);
		}
		return o;
	}
}
