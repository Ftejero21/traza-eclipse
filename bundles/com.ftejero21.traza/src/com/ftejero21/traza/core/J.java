package com.ftejero21.traza.core;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Ayudas para tratar JSON dinamico (equivalente a los `unknown` del codigo TypeScript original). */
public final class J {
	/** Serializa con nulls (la webview distingue `null` de ausente) y con escape HTML (seguro para incrustar en JS). */
	public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
	/** Con escape HTML (para incrustar cadenas en JavaScript de forma segura). */
	public static final Gson GSON_JS = new GsonBuilder().serializeNulls().create();
	public static final Gson GSON_PLAIN = new GsonBuilder().create();

	private J() {
	}

	public static String toJson(JsonElement e) {
		return GSON.toJson(e);
	}

	public static JsonElement parse(String text) {
		return JsonParser.parseString(text);
	}

	public static JsonObject obj(JsonElement e) {
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	public static JsonObject obj(JsonObject o, String key) {
		return o == null ? null : obj(o.get(key));
	}

	public static JsonArray arr(JsonElement e) {
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	public static JsonArray arr(JsonObject o, String key) {
		return o == null ? new JsonArray() : arr(o.get(key));
	}

	public static boolean isArr(JsonElement e) {
		return e != null && e.isJsonArray();
	}

	public static boolean isStr(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
	}

	public static boolean isNum(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
	}

	public static boolean isBool(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
	}

	public static boolean isNull(JsonElement e) {
		return e == null || e.isJsonNull();
	}

	/** Valor string o "" (equivale a `typeof x === 'string' ? x : ''`). */
	public static String str(JsonElement e) {
		return isStr(e) ? e.getAsString() : "";
	}

	public static String str(JsonObject o, String key) {
		return o == null ? "" : str(o.get(key));
	}

	public static boolean has(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull();
	}

	/** Numero finito o null. */
	public static Double num(JsonElement e) {
		if (!isNum(e)) {
			return null;
		}
		double d = e.getAsDouble();
		return Double.isFinite(d) ? d : null;
	}

	public static Double num(JsonObject o, String key) {
		return o == null ? null : num(o.get(key));
	}

	/** Entero valido (numero sin parte decimal) o null. */
	public static Long integer(JsonElement e) {
		Double d = num(e);
		if (d == null || d != Math.floor(d) || Math.abs(d) > 9.007199254740991E15) {
			return null;
		}
		return d.longValue();
	}

	public static JsonPrimitive p(String s) {
		return new JsonPrimitive(s);
	}

	public static JsonElement nul() {
		return JsonNull.INSTANCE;
	}

	public static JsonArray strArray(Iterable<String> items) {
		JsonArray a = new JsonArray();
		for (String s : items) {
			a.add(s);
		}
		return a;
	}

	public static JsonElement deepCopy(JsonElement e) {
		return e == null ? null : e.deepCopy();
	}

	/** Reemplazo con funcion (la salida se toma literal, sin interpretar `$` ni `\`). */
	public static String replace(Pattern p, String input, Function<Matcher, String> fn) {
		Matcher m = p.matcher(input);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	public static String replaceAll(Pattern p, String input, String literal) {
		return p.matcher(input).replaceAll(Matcher.quoteReplacement(literal));
	}

	public static Pattern ci(String regex) {
		return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
	}

	public static String slice(String s, int from, int to) {
		int len = s.length();
		int a = Math.max(0, Math.min(len, from));
		int b = Math.max(a, Math.min(len, to));
		return s.substring(a, b);
	}

	public static String slice(String s, int from) {
		return slice(s, from, s.length());
	}

	public static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, Math.max(0, max));
	}

	public static String repeat(String s, int n) {
		return n <= 0 ? "" : s.repeat(n);
	}

	public static boolean isBase64(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
			if (!ok) {
				return false;
			}
		}
		return true;
	}
}
