package com.ftejero21.traza.db;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.core.J;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Port de HostDb (host-db.ts): lectura de la metadata (tablas, columnas, claves e indices) en SOLO LECTURA con cache de
 * 60 s y una sola peticion en curso por conexion. Nunca se ejecuta SQL de la IA ni se leen filas: solo consultas fijas y
 * parametrizadas a information_schema / pg_catalog.
 */
public final class DbMetadataService {
	public static final DbMetadataService INSTANCE = new DbMetadataService();

	private static final long CACHE_MS = 60_000;
	private static final int CONNECT_TIMEOUT_S = 8;
	private static final int QUERY_TIMEOUT_S = 10;
	private static final int MAX_TABLES = 500;
	private static final int MAX_COLUMNS = 200;
	private static final Map<String, String> PG_FK_ACTIONS = Map.of("a", "NO ACTION", "r", "RESTRICT", "c", "CASCADE", "n", "SET NULL", "d", "SET DEFAULT");

	/** Error con un mensaje fijo, apto para la webview (nunca URL, usuario ni contrasena). */
	public static final class DbException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public DbException(String message) {
			super(message);
		}
	}

	private static final class Cached {
		final long at;
		final JsonObject meta;

		Cached(long at, JsonObject meta) {
			this.at = at;
			this.meta = meta;
		}
	}

	private final Map<String, Cached> cache = new HashMap<>();
	private final Map<String, CompletableFuture<JsonObject>> pending = new HashMap<>();
	private int generation;

	private DbMetadataService() {
	}

	public static String msg(String key) {
		switch (key) {
		case "auth":
			return "Usuario o contraseña de la base de datos incorrectos.";
		case "database":
			return "La base de datos no existe.";
		case "host":
			return "No se pudo conectar con el servidor de la base de datos.";
		case "timeout":
			return "La base de datos ha tardado demasiado en responder.";
		case "provider":
			return "Proveedor de base de datos no soportado.";
		case "missing":
			return "No existe esa conexión de base de datos.";
		case "password":
			return "Falta la contraseña de la base de datos. Usa \"Traza: Contraseña de base de datos\".";
		case "tls":
			return "Para un servidor remoto activa \"ssl\": solo se permite conexión sin TLS en localhost.";
		default:
			return "No se pudo leer el esquema de la base de datos.";
		}
	}

	/* ------------------------------ API ------------------------------ */

	/** Conexiones para la webview: sin host, usuario ni contrasena. */
	public JsonArray list() {
		JsonArray out = new JsonArray();
		for (DbConfig config : DbConfigStore.readAll()) {
			JsonObject o = new JsonObject();
			o.addProperty("key", config.id);
			o.addProperty("name", config.name);
			o.addProperty("provider", config.provider);
			o.addProperty("database", config.database);
			o.addProperty("root", config.root == null ? "" : config.root);
			o.addProperty("hasPassword", DbPasswords.has(config.id));
			out.add(o);
		}
		return out;
	}

	public synchronized void clearCache() {
		generation++;
		cache.clear();
		pending.clear();
	}

	/** Metadata de la conexion (bloquea hasta tenerla). */
	public JsonObject metadata(String key, boolean force) {
		if (!Guards.DB_KEY_PATTERN.matcher(key).matches()) {
			throw new DbException(msg("missing"));
		}
		CompletableFuture<JsonObject> future;
		boolean owner = false;
		int gen;
		synchronized (this) {
			Cached cached = cache.get(key);
			if (!force && cached != null && System.currentTimeMillis() - cached.at < CACHE_MS) {
				return cached.meta;
			}
			future = pending.get(key);
			gen = generation;
			if (future == null) {
				future = new CompletableFuture<>();
				pending.put(key, future);
				owner = true;
			}
		}
		if (owner) {
			try {
				JsonObject meta = load(key);
				synchronized (this) {
					if (gen == generation) {
						cache.put(key, new Cached(System.currentTimeMillis(), meta));
					}
				}
				future.complete(meta);
			} catch (RuntimeException e) {
				future.completeExceptionally(e);
			} finally {
				synchronized (this) {
					if (pending.get(key) == future) {
						pending.remove(key);
					}
				}
			}
		}
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException) {
				throw (RuntimeException) e.getCause();
			}
			throw new DbException(msg("generic"));
		}
	}

	/** «Probar conexion»: numero de tablas. */
	public int test(String key) {
		return metadata(key, true).getAsJsonArray("tables").size();
	}

	/* ------------------------------ carga ------------------------------ */

	private JsonObject load(String key) {
		DbConfig config = DbConfigStore.find(key);
		if (config == null) {
			throw new DbException(msg("missing"));
		}
		if (!DbConfig.isLocalHost(config.host) && !config.ssl) {
			throw new DbException(msg("tls"));
		}
		String password = DbPasswords.get(config.id);
		if (password == null || password.isEmpty()) {
			throw new DbException(msg("password"));
		}
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(DbMetadataService.class.getClassLoader());
		try {
			if (config.provider.equals("postgresql")) {
				return pgMetadata(config, password);
			}
			if (config.provider.equals("mysql") || config.provider.equals("mariadb")) {
				return mariaMetadata(config, password);
			}
			throw new DbException(msg("provider"));
		} catch (DbException e) {
			throw e;
		} catch (SQLException | RuntimeException e) {
			TrazaPlugin.warn("No se pudo leer la metadata de la base de datos (" + e.getClass().getSimpleName() + ").");
			throw translate(e);
		} finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	/** Traduce un error del driver a un mensaje fijo. */
	static DbException translate(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof DbException) {
				return (DbException) t;
			}
			if (t instanceof SQLException) {
				SQLException s = (SQLException) t;
				String state = s.getSQLState() == null ? "" : s.getSQLState();
				int code = s.getErrorCode();
				if (state.equals("28P01") || state.equals("28000") || code == 1045 || code == 1044 || code == 1251) {
					return new DbException(msg("auth"));
				}
				if (state.equals("3D000") || state.equals("3F000") || code == 1049) {
					return new DbException(msg("database"));
				}
				if (state.equals("57014") || t instanceof SQLTimeoutException) {
					return new DbException(msg("timeout"));
				}
				if (state.startsWith("08")) {
					return new DbException(msg("host"));
				}
			}
			if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
				return new DbException(msg("host"));
			}
			if (t instanceof SocketTimeoutException) {
				return new DbException(msg("timeout"));
			}
		}
		String m = error.getMessage();
		if (m != null && m.toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
			return new DbException(msg("timeout"));
		}
		return new DbException(msg("generic"));
	}

	/* ------------------------------ ayudas ------------------------------ */

	private static JsonObject emptyColumn(String name, int position) {
		JsonObject c = new JsonObject();
		c.addProperty("name", name);
		c.addProperty("position", position);
		c.addProperty("dataType", "");
		c.addProperty("columnType", "");
		c.add("length", JsonNull.INSTANCE);
		c.add("precision", JsonNull.INSTANCE);
		c.add("scale", JsonNull.INSTANCE);
		c.addProperty("nullable", true);
		c.addProperty("primaryKey", false);
		c.addProperty("unique", false);
		c.addProperty("autoIncrement", false);
		c.add("defaultValue", JsonNull.INSTANCE);
		c.add("extra", JsonNull.INSTANCE);
		c.add("comment", JsonNull.INSTANCE);
		return c;
	}

	private static JsonObject newTable(String name, boolean view) {
		JsonObject t = new JsonObject();
		t.addProperty("name", name);
		t.addProperty("type", view ? "VIEW" : "TABLE");
		t.add("engine", JsonNull.INSTANCE);
		t.add("rowEstimate", JsonNull.INSTANCE);
		t.add("comment", JsonNull.INSTANCE);
		t.add("columns", new JsonArray());
		t.add("primaryKey", new JsonArray());
		t.add("foreignKeys", new JsonArray());
		t.add("indexes", new JsonArray());
		t.add("referencedBy", new JsonArray());
		return t;
	}

	private static Number num(Object value) {
		if (value instanceof Number) {
			return (Number) value;
		}
		if (value instanceof String && !((String) value).trim().isEmpty()) {
			try {
				return Double.valueOf(((String) value).trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static void setNum(JsonObject o, String key, Object value) {
		Number n = num(value);
		if (n == null) {
			o.add(key, JsonNull.INSTANCE);
		} else if (n.doubleValue() == Math.rint(n.doubleValue())) {
			o.addProperty(key, n.longValue());
		} else {
			o.addProperty(key, n.doubleValue());
		}
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static JsonArray textArray(Array array) throws SQLException {
		JsonArray out = new JsonArray();
		if (array == null) {
			return out;
		}
		Object[] items = (Object[]) array.getArray();
		for (Object item : items) {
			String s = text(item);
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	private static JsonObject index(String name, boolean unique, boolean primary, JsonArray columns, String type) {
		JsonObject i = new JsonObject();
		i.addProperty("name", name);
		i.addProperty("unique", unique);
		i.addProperty("primary", primary);
		i.add("columns", columns);
		if (type == null) {
			i.add("type", JsonNull.INSTANCE);
		} else {
			i.addProperty("type", type);
		}
		return i;
	}

	private static Set<String> asSet(JsonArray a) {
		Set<String> s = new HashSet<>();
		for (JsonElement e : a) {
			s.add(e.getAsString());
		}
		return s;
	}

	/** Completa PK, unicas y referencedBy (invirtiendo las FK) de las tablas. */
	private static JsonArray finish(Map<String, JsonObject> tables) {
		for (JsonObject table : tables.values()) {
			JsonArray pkArr = table.getAsJsonArray("primaryKey");
			Set<String> pk = asSet(pkArr);
			Set<String> unique = new HashSet<>();
			for (JsonElement ie : table.getAsJsonArray("indexes")) {
				JsonObject i = ie.getAsJsonObject();
				if (i.get("unique").getAsBoolean() && !i.get("primary").getAsBoolean() && i.getAsJsonArray("columns").size() == 1) {
					unique.add(i.getAsJsonArray("columns").get(0).getAsString());
				}
			}
			for (JsonElement ce : table.getAsJsonArray("columns")) {
				JsonObject c = ce.getAsJsonObject();
				String name = c.get("name").getAsString();
				if (pk.contains(name)) {
					c.addProperty("primaryKey", true);
				}
				if (unique.contains(name) || (pk.contains(name) && pkArr.size() == 1)) {
					c.addProperty("unique", true);
				}
			}
			for (JsonElement fe : table.getAsJsonArray("foreignKeys")) {
				JsonObject fk = fe.getAsJsonObject();
				JsonObject target = tables.get(fk.get("refTable").getAsString());
				if (target == null) {
					continue;
				}
				JsonObject ref = new JsonObject();
				ref.add("name", fk.get("name"));
				ref.addProperty("table", table.get("name").getAsString());
				ref.add("columns", fk.get("columns"));
				ref.add("refColumns", fk.get("refColumns"));
				ref.add("onDelete", fk.get("onDelete"));
				ref.add("onUpdate", fk.get("onUpdate"));
				target.getAsJsonArray("referencedBy").add(ref);
			}
		}
		JsonArray out = new JsonArray();
		tables.values().forEach(out::add);
		return out;
	}

	private static JsonObject metaOf(DbConfig config, String provider, Map<String, JsonObject> tables) {
		JsonObject m = new JsonObject();
		m.addProperty("database", config.database);
		m.addProperty("provider", provider);
		m.add("tables", finish(tables));
		return m;
	}

	/* ------------------------------ PostgreSQL ------------------------------ */

	private JsonObject pgMetadata(DbConfig config, String password) throws SQLException {
		Properties props = new Properties();
		props.setProperty("user", config.user);
		props.setProperty("password", password);
		props.setProperty("ApplicationName", "traza");
		props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_S));
		props.setProperty("socketTimeout", String.valueOf(QUERY_TIMEOUT_S + 5));
		props.setProperty("options", "-c default_transaction_read_only=on -c statement_timeout=" + (QUERY_TIMEOUT_S * 1000));
		props.setProperty("sslmode", config.ssl ? "verify-full" : "disable");
		String url = "jdbc:postgresql://" + config.host + ":" + config.port + "/" + URLEncoder.encode(config.database, StandardCharsets.UTF_8);
		String schema = config.schema == null || config.schema.isEmpty() ? "public" : config.schema;
		try (Connection conn = new org.postgresql.Driver().connect(url, props)) {
			if (conn == null) {
				throw new DbException(msg("generic"));
			}
			conn.setReadOnly(true);
			Map<String, JsonObject> tables = new LinkedHashMap<>();
			try (PreparedStatement ps = conn.prepareStatement(
				"SELECT table_name::text AS table_name, table_type::text AS table_type FROM information_schema.tables "
					+ "WHERE table_schema = ? AND table_type IN ('BASE TABLE', 'VIEW') ORDER BY table_name LIMIT " + MAX_TABLES)) {
				ps.setQueryTimeout(QUERY_TIMEOUT_S);
				ps.setString(1, schema);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						String name = text(rs.getString("table_name"));
						tables.put(name, newTable(name, "VIEW".equals(rs.getString("table_type"))));
					}
				}
			}
			try (PreparedStatement ps = conn.prepareStatement(
				"SELECT table_name::text AS table_name, column_name::text AS column_name, ordinal_position, data_type::text AS data_type, "
					+ "udt_name::text AS udt_name, character_maximum_length, numeric_precision, numeric_scale, is_nullable::text AS is_nullable, "
					+ "column_default::text AS column_default, is_identity::text AS is_identity "
					+ "FROM information_schema.columns WHERE table_schema = ? ORDER BY table_name, ordinal_position")) {
				ps.setQueryTimeout(QUERY_TIMEOUT_S);
				ps.setString(1, schema);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						JsonObject table = tables.get(text(rs.getString("table_name")));
						if (table == null || table.getAsJsonArray("columns").size() >= MAX_COLUMNS) {
							continue;
						}
						Object lengthObj = rs.getObject("character_maximum_length");
						Number length = num(lengthObj);
						String dt = text(rs.getString("data_type"));
						String dataType = dt.equals("USER-DEFINED") ? text(rs.getString("udt_name")) : dt;
						String def = rs.getString("column_default");
						Number pos = num(rs.getObject("ordinal_position"));
						JsonObject column = emptyColumn(text(rs.getString("column_name")), pos != null ? pos.intValue() : table.getAsJsonArray("columns").size() + 1);
						column.addProperty("dataType", dataType);
						column.addProperty("columnType", dataType + (length != null && length.longValue() != 0 ? "(" + length.longValue() + ")" : ""));
						setNum(column, "length", lengthObj);
						setNum(column, "precision", rs.getObject("numeric_precision"));
						setNum(column, "scale", rs.getObject("numeric_scale"));
						column.addProperty("nullable", "YES".equals(rs.getString("is_nullable")));
						if (def == null) {
							column.add("defaultValue", JsonNull.INSTANCE);
						} else {
							column.addProperty("defaultValue", def);
						}
						column.addProperty("autoIncrement", "YES".equals(rs.getString("is_identity")) || (def != null && def.startsWith("nextval(")));
						table.getAsJsonArray("columns").add(column);
					}
				}
			}
			try (PreparedStatement ps = conn.prepareStatement(
				"SELECT c.conname::text AS name, c.contype::text AS type, t.relname::text AS table_name, "
					+ "ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) "
					+ "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum ORDER BY k.ord) AS columns, "
					+ "rt.relname::text AS ref_table, "
					+ "ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY AS k(attnum, ord) "
					+ "JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum ORDER BY k.ord) AS ref_columns, "
					+ "c.confdeltype::text AS on_delete, c.confupdtype::text AS on_update "
					+ "FROM pg_catalog.pg_constraint c "
					+ "JOIN pg_catalog.pg_class t ON t.oid = c.conrelid "
					+ "JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace "
					+ "LEFT JOIN pg_catalog.pg_class rt ON rt.oid = c.confrelid "
					+ "WHERE n.nspname = ? AND c.contype IN ('p', 'u', 'f') ORDER BY t.relname, c.conname")) {
				ps.setQueryTimeout(QUERY_TIMEOUT_S);
				ps.setString(1, schema);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						JsonObject table = tables.get(text(rs.getString("table_name")));
						if (table == null) {
							continue;
						}
						JsonArray columns = textArray(rs.getArray("columns"));
						String type = text(rs.getString("type"));
						String name = text(rs.getString("name"));
						if (type.equals("p")) {
							table.add("primaryKey", columns);
							table.getAsJsonArray("indexes").add(index(name, true, true, columns.deepCopy(), null));
						} else if (type.equals("u")) {
							table.getAsJsonArray("indexes").add(index(name, true, false, columns, null));
						} else if (type.equals("f")) {
							JsonObject fk = new JsonObject();
							fk.addProperty("name", name);
							fk.add("columns", columns);
							fk.addProperty("refTable", text(rs.getString("ref_table")));
							fk.add("refColumns", textArray(rs.getArray("ref_columns")));
							String onDelete = PG_FK_ACTIONS.get(text(rs.getString("on_delete")));
							String onUpdate = PG_FK_ACTIONS.get(text(rs.getString("on_update")));
							fk.add("onDelete", onDelete == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(onDelete));
							fk.add("onUpdate", onUpdate == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(onUpdate));
							table.getAsJsonArray("foreignKeys").add(fk);
						}
					}
				}
			}
			return metaOf(config, "postgresql", tables);
		}
	}

	/* ------------------------------ MySQL / MariaDB ------------------------------ */

	private static List<Map<String, Object>> run(Connection conn, String sql, String database) throws SQLException {
		List<Map<String, Object>> rows = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setQueryTimeout(QUERY_TIMEOUT_S);
			ps.setString(1, database);
			try (ResultSet rs = ps.executeQuery()) {
				int n = rs.getMetaData().getColumnCount();
				while (rs.next()) {
					Map<String, Object> row = new HashMap<>();
					for (int i = 1; i <= n; i++) {
						row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
					}
					rows.add(row);
				}
			}
		}
		return rows;
	}

	private JsonObject mariaMetadata(DbConfig config, String password) throws SQLException {
		boolean local = DbConfig.isLocalHost(config.host);
		StringBuilder url = new StringBuilder("jdbc:mariadb://").append(config.host).append(':').append(config.port).append('/')
			.append(URLEncoder.encode(config.database, StandardCharsets.UTF_8))
			.append("?connectTimeout=").append(CONNECT_TIMEOUT_S * 1000).append("&socketTimeout=").append(QUERY_TIMEOUT_S * 1000 + 5000);
		if (config.ssl) {
			url.append("&sslMode=verify-full");
		} else if (local) {
			url.append("&allowPublicKeyRetrieval=true");
		}
		Properties props = new Properties();
		props.setProperty("user", config.user);
		props.setProperty("password", password);
		try (Connection conn = new org.mariadb.jdbc.Driver().connect(url.toString(), props)) {
			if (conn == null) {
				throw new DbException(msg("generic"));
			}
			try (Statement st = conn.createStatement()) {
				st.setQueryTimeout(QUERY_TIMEOUT_S);
				st.execute("SET SESSION TRANSACTION READ ONLY");
			}
			conn.setReadOnly(true);
			String db = config.database;
			Map<String, JsonObject> tables = new LinkedHashMap<>();
			for (Map<String, Object> row : run(conn,
				"SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type FROM information_schema.TABLES "
					+ "WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME LIMIT " + MAX_TABLES, db)) {
				String name = text(row.get("table_name"));
				tables.put(name, newTable(name, "VIEW".equals(text(row.get("table_type")))));
			}
			for (Map<String, Object> row : run(conn,
				"SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name, ORDINAL_POSITION AS ordinal_position, DATA_TYPE AS data_type, "
					+ "COLUMN_TYPE AS column_type, CHARACTER_MAXIMUM_LENGTH AS char_length, NUMERIC_PRECISION AS num_precision, "
					+ "NUMERIC_SCALE AS num_scale, IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default, EXTRA AS extra, COLUMN_KEY AS column_key "
					+ "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION", db)) {
				JsonObject table = tables.get(text(row.get("table_name")));
				if (table == null || table.getAsJsonArray("columns").size() >= MAX_COLUMNS) {
					continue;
				}
				Number pos = num(row.get("ordinal_position"));
				JsonObject column = emptyColumn(text(row.get("column_name")), pos != null ? pos.intValue() : table.getAsJsonArray("columns").size() + 1);
				String extra = text(row.get("extra"));
				String dataType = text(row.get("data_type"));
				column.addProperty("dataType", dataType);
				String columnType = text(row.get("column_type"));
				column.addProperty("columnType", columnType.isEmpty() ? dataType : columnType);
				setNum(column, "length", row.get("char_length"));
				setNum(column, "precision", row.get("num_precision"));
				setNum(column, "scale", row.get("num_scale"));
				column.addProperty("nullable", "YES".equals(text(row.get("is_nullable"))));
				if (row.get("column_default") == null) {
					column.add("defaultValue", JsonNull.INSTANCE);
				} else {
					column.addProperty("defaultValue", text(row.get("column_default")));
				}
				column.addProperty("autoIncrement", extra.toLowerCase(java.util.Locale.ROOT).contains("auto_increment"));
				if (extra.isEmpty()) {
					column.add("extra", JsonNull.INSTANCE);
				} else {
					column.addProperty("extra", extra);
				}
				column.addProperty("primaryKey", "PRI".equals(text(row.get("column_key"))));
				column.addProperty("unique", "UNI".equals(text(row.get("column_key"))));
				table.getAsJsonArray("columns").add(column);
			}
			Map<String, JsonObject> fks = new HashMap<>();
			for (Map<String, Object> row : run(conn,
				"SELECT k.CONSTRAINT_NAME AS name, k.TABLE_NAME AS table_name, k.COLUMN_NAME AS column_name, "
					+ "k.REFERENCED_TABLE_NAME AS ref_table, k.REFERENCED_COLUMN_NAME AS ref_column, r.DELETE_RULE AS on_delete, r.UPDATE_RULE AS on_update "
					+ "FROM information_schema.KEY_COLUMN_USAGE k "
					+ "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
					+ "ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND r.TABLE_NAME = k.TABLE_NAME "
					+ "WHERE k.TABLE_SCHEMA = ? AND k.REFERENCED_TABLE_NAME IS NOT NULL "
					+ "ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION", db)) {
				JsonObject table = tables.get(text(row.get("table_name")));
				if (table == null) {
					continue;
				}
				String key = table.get("name").getAsString() + "\u0000" + text(row.get("name"));
				JsonObject fk = fks.get(key);
				if (fk == null) {
					fk = new JsonObject();
					fk.addProperty("name", text(row.get("name")));
					fk.add("columns", new JsonArray());
					fk.addProperty("refTable", text(row.get("ref_table")));
					fk.add("refColumns", new JsonArray());
					String onDelete = text(row.get("on_delete"));
					String onUpdate = text(row.get("on_update"));
					fk.add("onDelete", onDelete.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(onDelete));
					fk.add("onUpdate", onUpdate.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(onUpdate));
					fks.put(key, fk);
					table.getAsJsonArray("foreignKeys").add(fk);
				}
				fk.getAsJsonArray("columns").add(text(row.get("column_name")));
				fk.getAsJsonArray("refColumns").add(text(row.get("ref_column")));
			}
			Map<String, JsonObject> indexes = new HashMap<>();
			for (Map<String, Object> row : run(conn,
				"SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name, COLUMN_NAME AS column_name, INDEX_TYPE AS index_type "
					+ "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND NON_UNIQUE = 0 "
					+ "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX", db)) {
				JsonObject table = tables.get(text(row.get("table_name")));
				if (table == null) {
					continue;
				}
				String name = text(row.get("index_name"));
				String key = table.get("name").getAsString() + "\u0000" + name;
				JsonObject idx = indexes.get(key);
				if (idx == null) {
					String type = text(row.get("index_type"));
					idx = index(name, true, name.equals("PRIMARY"), new JsonArray(), type.isEmpty() ? null : type);
					indexes.put(key, idx);
					table.getAsJsonArray("indexes").add(idx);
				}
				idx.getAsJsonArray("columns").add(text(row.get("column_name")));
				if (idx.get("primary").getAsBoolean()) {
					table.add("primaryKey", idx.getAsJsonArray("columns"));
				}
			}
			return metaOf(config, config.provider, tables);
		}
	}

	/** Util para pruebas manuales: numero de tablas de una metadata. */
	public static int tableCount(JsonObject meta) {
		return meta.getAsJsonArray("tables").size();
	}

	/** Serializa la metadata para la webview (conserva los nulls). */
	public static String toJson(JsonObject meta) {
		return J.GSON.toJson(meta);
	}
}
