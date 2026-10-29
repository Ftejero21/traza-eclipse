package com.ftejero21.traza.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ftejero21.traza.core.J;
import com.google.gson.JsonElement;

/** Cache LRU de resultados validados (maximo 50, TTL 15 min, clave sha256). Guarda copias. */
public final class ResultCache {
	private static final int CACHE_MAX = 50;
	private static final long CACHE_TTL_MS = 15 * 60_000L;

	private static final class Entry {
		final long expires;
		final String json;

		Entry(long expires, String json) {
			this.expires = expires;
			this.json = json;
		}
	}

	private static final Map<String, Entry> CACHE = new LinkedHashMap<>();

	private ResultCache() {
	}

	public static String key(String op, String model, String lang, String system, String user) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest((op + "|" + model + "|" + lang + "|" + system + "|" + user).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static synchronized JsonElement get(String key) {
		Entry e = CACHE.remove(key);
		if (e == null || e.expires <= System.currentTimeMillis()) {
			return null;
		}
		CACHE.put(key, e);
		return J.parse(e.json);
	}

	public static synchronized void put(String key, JsonElement value) {
		CACHE.remove(key);
		CACHE.put(key, new Entry(System.currentTimeMillis() + CACHE_TTL_MS, J.GSON.toJson(value)));
		while (CACHE.size() > CACHE_MAX) {
			Iterator<String> it = CACHE.keySet().iterator();
			it.next();
			it.remove();
		}
	}
}
