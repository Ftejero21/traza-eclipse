package com.ftejero21.traza.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Port de workspace-code-ai-mask.ts: enmascarado de secretos. Todo lo que coincide se sustituye por «oculto». */
public final class SecretMasker {
	public static final String MASK = "\u00aboculto\u00bb";

	private static final Pattern SENSITIVE_KEY = Pattern.compile("^(?:.*(?:password|passwd|pwd|secret|token|api[_-]?key|authorization|cookie).*)$",
		Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern BEARER = Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern JWT = Pattern.compile("\\beyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+");
	private static final Pattern HEADER = Pattern.compile("\\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie)(\\s*:\\s*)([^\\r\\n]+)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern HEADER_SCHEME = Pattern.compile("^(Bearer|Basic|Digest|Token)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern KEY_VALUE = Pattern.compile(
		"\\b([\\w.-]*(?:password|passwd|pwd|secret|token|api[_-]?key)[\\w.-]*)([\"']?\\s*[:=]\\s*)(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;&}\\])]+)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
	private static final Pattern CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
	private static final Pattern CONFIG_FILE = Pattern.compile(
		"(?:\\.(?:properties|ya?ml|conf|cfg|ini|toml|env|npmrc)|(?:^|/)(?:appsettings[\\w.-]*|secrets?[\\w.-]*|credentials[\\w.-]*)\\.json)$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern URL_CREDENTIALS = Pattern.compile("(\\b[a-z][\\w+.-]*://)([^\\s:/@]+):([^\\s/@]+)@", Pattern.CASE_INSENSITIVE);

	private SecretMasker() {
	}

	private static boolean luhn(String digits) {
		int sum = 0;
		boolean dbl = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int n = digits.charAt(i) - 48;
			if (dbl) {
				n *= 2;
				if (n > 9) {
					n -= 9;
				}
			}
			sum += n;
			dbl = !dbl;
		}
		return sum % 10 == 0;
	}

	public static String maskSensitiveText(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String s = J.replace(HEADER, text, m -> {
			Matcher scheme = HEADER_SCHEME.matcher(m.group(3).trim());
			String prefix = scheme.find() ? scheme.group(1) + " " : "";
			return m.group(1) + m.group(2) + prefix + MASK;
		});
		s = J.replaceAll(BEARER, s, "Bearer " + MASK);
		s = J.replaceAll(JWT, s, MASK);
		s = J.replace(KEY_VALUE, s, m -> {
			String value = m.group(3);
			return value.equals(MASK) || value.startsWith("Bearer") ? m.group() : m.group(1) + m.group(2) + MASK;
		});
		s = J.replaceAll(EMAIL, s, MASK);
		s = J.replace(CARD, s, m -> {
			String digits = m.group().replaceAll("[ -]", "");
			return digits.length() >= 13 && digits.length() <= 19 && luhn(digits) ? MASK : m.group();
		});
		return s;
	}

	/**
	 * Archivos de configuracion (application.properties, *.yml...) enviados como contexto: se ocultan valores de claves
	 * sensibles, credenciales en URLs, tokens y emails. El codigo fuente no se toca.
	 */
	public static String maskConfigSecrets(String path, String text) {
		if (text == null || text.isEmpty() || !CONFIG_FILE.matcher(path == null ? "" : path).find()) {
			return text;
		}
		return maskSensitiveText(J.replace(URL_CREDENTIALS, text, m -> m.group(1) + m.group(2) + ":" + MASK + "@"));
	}

	/** Enmascara recursivamente cada string del JSON y los valores de claves con nombre sensible. */
	public static JsonElement maskSensitiveJson(JsonElement value) {
		return maskSensitiveJson(value, 0);
	}

	private static JsonElement maskSensitiveJson(JsonElement value, int depth) {
		if (depth > 64) {
			return JsonNull.INSTANCE;
		}
		if (value == null || value.isJsonNull()) {
			return JsonNull.INSTANCE;
		}
		if (J.isStr(value)) {
			return new JsonPrimitive(maskSensitiveText(value.getAsString()));
		}
		if (value.isJsonArray()) {
			JsonArray out = new JsonArray();
			for (JsonElement item : value.getAsJsonArray()) {
				out.add(maskSensitiveJson(item, depth + 1));
			}
			return out;
		}
		if (value.isJsonObject()) {
			JsonObject out = new JsonObject();
			for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
				String safeKey = maskSensitiveText(e.getKey());
				JsonElement item = e.getValue();
				if (SENSITIVE_KEY.matcher(e.getKey()).matches() && !item.isJsonNull()) {
					out.add(safeKey, new JsonPrimitive(MASK));
				} else {
					out.add(safeKey, maskSensitiveJson(item, depth + 1));
				}
			}
			return out;
		}
		return value;
	}
}
