package com.ftejero21.traza.mcp;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.Msg;
import com.ftejero21.traza.prefs.TrazaPreferences;
import com.ftejero21.traza.ui.Ui;

/**
 * Integracion con GitHub Copilot for Eclipse solo por reflexion/ids de comando: sin dependencia de compilacion ni de
 * ejecucion con Copilot. Si Copilot no esta instalado, todo lo que no usa IA sigue funcionando.
 */
public final class CopilotBridge {
	public static final String UPDATE_SITE = "https://azuredownloads-g3ahgwb5b8bkbxhd.b01.azurefd.net/github-copilot/";
	public static final String UI_BUNDLE = "com.microsoft.copilot.eclipse.ui";
	private static final String OPEN_CHAT = "com.microsoft.copilot.eclipse.commands.openChatView";

	private CopilotBridge() {
	}

	/** El bundle de Copilot existe (instalado), aunque el comando no este aun registrado. */
	public static boolean isInstalled() {
		return Platform.getBundle(UI_BUNDLE) != null;
	}

	/** El bundle de Copilot existe y el comando de abrir el chat esta definido. */
	public static boolean isAvailable() {
		if (!isInstalled() || !PlatformUI.isWorkbenchRunning()) {
			return false;
		}
		try {
			ICommandService cs = PlatformUI.getWorkbench().getService(ICommandService.class);
			return cs != null && cs.getCommand(OPEN_CHAT).isDefined();
		} catch (RuntimeException e) {
			TrazaPlugin.warn("No se pudo comprobar la disponibilidad de Copilot (" + e.getClass().getSimpleName() + ").");
			return false;
		}
	}

	/** Abre el chat de Copilot en modo Agent con el texto (y lo envia si autoSend). Devuelve false si no se pudo. */
	public static boolean openChat(String input, boolean autoSend) {
		Boolean ok = Ui.sync(() -> openChatOnUi(input, autoSend, true));
		return ok != null && ok;
	}

	/** Abre el chat de Copilot sin parametros. */
	public static boolean openChatPlain() {
		Boolean ok = Ui.sync(() -> openChatOnUi(null, false, false));
		return ok != null && ok;
	}

	private static boolean openChatOnUi(String input, boolean autoSend, boolean withParams) {
		if (!isAvailable()) {
			return false;
		}
		try {
			ICommandService cs = PlatformUI.getWorkbench().getService(ICommandService.class);
			IHandlerService hs = PlatformUI.getWorkbench().getService(IHandlerService.class);
			Command cmd = cs.getCommand(OPEN_CHAT);
			ParameterizedCommand pc = null;
			if (withParams) {
				Map<String, String> full = new HashMap<>();
				full.put(OPEN_CHAT + ".inputValue", input);
				full.put(OPEN_CHAT + ".autoSend", String.valueOf(autoSend));
				full.put(OPEN_CHAT + ".mode", "Agent");
				pc = ParameterizedCommand.generateCommand(cmd, full);
				if (pc == null) {
					Map<String, String> basic = new HashMap<>();
					basic.put(OPEN_CHAT + ".inputValue", input);
					basic.put(OPEN_CHAT + ".autoSend", String.valueOf(autoSend));
					pc = ParameterizedCommand.generateCommand(cmd, basic);
				}
				if (pc == null) {
					Map<String, String> only = new HashMap<>();
					only.put(OPEN_CHAT + ".inputValue", input);
					pc = ParameterizedCommand.generateCommand(cmd, only);
				}
			}
			if (pc == null) {
				pc = new ParameterizedCommand(cmd, null);
			}
			hs.executeCommand(pc, null);
			return true;
		} catch (Exception e) {
			TrazaPlugin.warn("No se pudo abrir el chat de Copilot (" + e.getClass().getSimpleName() + ").");
			return false;
		}
	}

	/** Texto de ayuda cuando Copilot no esta presente. */
	public static String missingMessage() {
		return Ui.t(
			"Esta acción necesita GitHub Copilot for Eclipse, que no está instalado.\n\nInstálalo desde Help > Install New Software con el sitio:\n" + UPDATE_SITE
				+ "\n\nDespués inicia sesión, usa el modo Agent y aprueba el servidor \"Traza: traza\" en el icono de herramientas del chat.",
			"This action needs GitHub Copilot for Eclipse, which is not installed.\n\nInstall it from Help > Install New Software with the site:\n" + UPDATE_SITE
				+ "\n\nThen sign in, use Agent mode and approve the \"Traza: traza\" server in the tools icon of the chat.");
	}

	/** Aviso de privacidad la primera vez que se usa la IA. */
	public static void showNoticeOnce() {
		if (TrazaPreferences.aiNoticeShown()) {
			return;
		}
		TrazaPreferences.setAiNoticeShown(true);
		Ui.notice("Traza", Msg.t("notice"));
	}
}
