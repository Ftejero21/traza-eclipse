package com.ftejero21.traza.prefs;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.ftejero21.traza.TrazaPlugin;

/** Preferencias de Traza (nodo InstanceScope com.ftejero21.traza). */
public final class TrazaPreferences {
	public static final String NODE = "com.ftejero21.traza";
	public static final String LANGUAGE = "language";
	public static final String THEME = "theme";
	public static final String DATABASES = "databases";
	public static final String MCP_PORT = "mcpPort";
	public static final String AI_NOTICE_SHOWN = "aiNoticeShown";
	public static final String COPILOT_AGENT_MODE = "copilotAgentMode";

	private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
	private static boolean hooked;

	private TrazaPreferences() {
	}

	private static IEclipsePreferences node() {
		return InstanceScope.INSTANCE.getNode(NODE);
	}

	private static IEclipsePreferences defaults() {
		return DefaultScope.INSTANCE.getNode(NODE);
	}

	/** Idioma de la vista: "es" (por defecto) o "en". */
	public static String language() {
		return "en".equals(node().get(LANGUAGE, "es")) ? "en" : "es";
	}

	public static boolean isSpanish() {
		return !"en".equals(language());
	}

	/** Valor de la preferencia: "traza" o "ide". */
	public static String themePref() {
		return "ide".equals(node().get(THEME, "traza")) ? "ide" : "traza";
	}

	/** Valor para la webview: "traza" o "vscode" (ide equivale a vscode). */
	public static String themeForWebview() {
		return "ide".equals(themePref()) ? "vscode" : "traza";
	}

	public static String databasesJson() {
		return node().get(DATABASES, "[]");
	}

	public static void setDatabasesJson(String json) {
		node().put(DATABASES, json);
		flush();
	}

	public static int mcpPort() {
		return node().getInt(MCP_PORT, 0);
	}

	public static void setMcpPort(int port) {
		node().putInt(MCP_PORT, port);
		flush();
	}

	public static boolean aiNoticeShown() {
		return node().getBoolean(AI_NOTICE_SHOWN, false);
	}

	public static void setAiNoticeShown(boolean v) {
		node().putBoolean(AI_NOTICE_SHOWN, v);
		flush();
	}

	/** Crear el modo «Traza» del chat de Copilot (archivo de agente gestionado). Activado por defecto. */
	public static boolean copilotAgentMode() {
		return node().getBoolean(COPILOT_AGENT_MODE, true);
	}

	public static void setCopilotAgentMode(boolean v) {
		node().putBoolean(COPILOT_AGENT_MODE, v);
		flush();
	}

	public static void setLanguage(String v) {
		node().put(LANGUAGE, "en".equals(v) ? "en" : "es");
		flush();
	}

	public static void setTheme(String v) {
		node().put(THEME, "ide".equals(v) ? "ide" : "traza");
		flush();
	}

	public static void flush() {
		try {
			node().flush();
		} catch (BackingStoreException e) {
			TrazaPlugin.error("No se pudieron guardar las preferencias de Traza.", e);
		}
	}

	/** Valores por defecto (los usa el PreferenceInitializer). */
	public static void initDefaults() {
		IEclipsePreferences d = defaults();
		d.put(LANGUAGE, "es");
		d.put(THEME, "traza");
		d.put(DATABASES, "[]");
		d.putInt(MCP_PORT, 0);
		d.putBoolean(AI_NOTICE_SHOWN, false);
		d.putBoolean(COPILOT_AGENT_MODE, true);
	}

	/** Registra un listener de cambios (recibe la clave modificada). Devuelve una accion para quitarlo. */
	public static Runnable addChangeListener(Consumer<String> l) {
		synchronized (TrazaPreferences.class) {
			if (!hooked) {
				hooked = true;
				node().addPreferenceChangeListener(event -> {
					for (Consumer<String> c : LISTENERS) {
						try {
							c.accept(event.getKey());
						} catch (RuntimeException e) {
							TrazaPlugin.error("Error en un listener de preferencias.", e);
						}
					}
				});
			}
		}
		LISTENERS.add(l);
		return () -> LISTENERS.remove(l);
	}
}
