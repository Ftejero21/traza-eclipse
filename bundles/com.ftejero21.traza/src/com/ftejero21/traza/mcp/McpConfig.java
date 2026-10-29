package com.ftejero21.traza.mcp;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.http.LocalHttpServer;
import com.google.gson.JsonObject;

/** Token del servidor MCP (secure storage) y configuracion que se entrega a Copilot. El token nunca se escribe en logs. */
public final class McpConfig {
	private static final String NODE = "/com.ftejero21.traza/mcp";
	private static final String KEY = "token";
	private static String token;

	private McpConfig() {
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	/** Token estable: se lee del secure storage o se genera y guarda. Si falla el almacen, se usa uno efimero. */
	public static synchronized String token() {
		if (token != null) {
			return token;
		}
		try {
			ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE);
			String stored = node.get(KEY, null);
			if (stored == null || stored.length() < 32) {
				stored = randomToken();
				node.put(KEY, stored, true);
				node.flush();
			}
			token = stored;
		} catch (StorageException | IOException e) {
			TrazaPlugin.error("No se pudo acceder al almacen seguro; se usa un token efimero para el servidor MCP.", e);
			token = randomToken();
		}
		return token;
	}

	/** Genera un token nuevo (hay que aprobar de nuevo el servidor en Copilot). */
	public static synchronized String regenerateToken() {
		String fresh = randomToken();
		try {
			ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE);
			node.put(KEY, fresh, true);
			node.flush();
		} catch (StorageException | IOException e) {
			TrazaPlugin.error("No se pudo guardar el nuevo token del servidor MCP.", e);
		}
		token = fresh;
		return fresh;
	}

	/** Configuracion de servidores para Copilot. Salida estable: mismo orden de claves, sin marcas de tiempo. */
	public static String serversJson() {
		LocalHttpServer.ensureStarted();
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + token());
		JsonObject requestInit = new JsonObject();
		requestInit.add("headers", headers);
		JsonObject traza = new JsonObject();
		traza.addProperty("type", "http");
		traza.addProperty("url", LocalHttpServer.mcpUrl());
		traza.add("requestInit", requestInit);
		JsonObject servers = new JsonObject();
		servers.add("traza", traza);
		JsonObject root = new JsonObject();
		root.add("servers", servers);
		return J.GSON.toJson(root);
	}
}
