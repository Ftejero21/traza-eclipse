package com.ftejero21.traza.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.urischeme.IUriSchemeHandler;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.FlowSummary;
import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.ui.handlers.AiCommands;
import com.ftejero21.traza.ui.handlers.ShowFlowHandler;

/**
 * Deep links traza:// (port de host-uri.ts). La URI es entrada no confiable: siempre pide confirmacion modal antes de
 * enviar nada a Copilot.
 * <ul>
 * <li>traza://flow?q=...&amp;mode=sim|logs|db: genera un flujo.</li>
 * <li>traza://flow?q=...&amp;mode=change&amp;change=...: propone un cambio (q es opcional).</li>
 * <li>traza://show?id=&lt;uuid&gt;[&amp;mode=changes]: reabre un flujo ya generado en esta sesion (sin IA ni modal).</li>
 * </ul>
 */
public class TrazaUriSchemeHandler implements IUriSchemeHandler {
	private static final Set<String> MODES = Set.of("sim", "logs", "db", "change");
	private static final int MAX_PREVIEW = 300;
	private static final int MAX_FOLDERS = 3;
	private static final Pattern FLOW_ID = Pattern.compile("^[0-9a-f-]{36}$", Pattern.CASE_INSENSITIVE);
	private static boolean busy;

	@Override
	public void handle(String uri) {
		Ui.async(() -> {
			try {
				handleOnUi(uri);
			} catch (RuntimeException e) {
				TrazaPlugin.error("No se pudo procesar un enlace de Traza.", e);
				Ui.error("Traza", Ui.t("Error inesperado.", "Unexpected error."));
			}
		});
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> out = new HashMap<>();
		if (raw == null) {
			return out;
		}
		for (String pair : raw.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int eq = pair.indexOf('=');
			String k = eq < 0 ? pair : pair.substring(0, eq);
			String v = eq < 0 ? "" : pair.substring(eq + 1);
			try {
				out.putIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
			} catch (IllegalArgumentException e) {
				TrazaPlugin.warn("Parametro de enlace mal codificado.");
			}
		}
		return out;
	}

	private static String previewQuestion(String q) {
		String clean = q.replaceAll("[\\u0000-\\u001f\\u007f\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]", " ");
		return FlowSummary.oneLine(clean, MAX_PREVIEW);
	}

	private static String folderLabel() {
		List<IProject> folders = WorkspaceFiles.openProjects();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(MAX_FOLDERS, folders.size()); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			IProject p = folders.get(i);
			sb.append(p.getName()).append(" (").append(p.getLocation() == null ? "" : p.getLocation().toOSString()).append(')');
		}
		if (folders.size() > MAX_FOLDERS) {
			sb.append(" … (+").append(folders.size() - MAX_FOLDERS).append(')');
		}
		return sb.toString();
	}

	private static void error(boolean es, String esText, String enText) {
		MessageDialog.openError(Ui.shell(), "Traza", es ? esText : enText);
	}

	private void handleOnUi(String uriText) {
		boolean es = Ui.spanish();
		URI uri;
		try {
			uri = new URI(uriText);
		} catch (URISyntaxException e) {
			error(es, "Enlace de Traza no reconocido.", "Unrecognized Traza link.");
			return;
		}
		if (!"traza".equalsIgnoreCase(uri.getScheme())) {
			error(es, "Enlace de Traza no reconocido.", "Unrecognized Traza link.");
			return;
		}
		String host = uri.getHost() != null ? uri.getHost() : uri.getAuthority();
		String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+|/+$", "");
		String target = (host != null && !host.isEmpty() ? host : path).toLowerCase(Locale.ROOT);
		Map<String, String> params = query(uri.getRawQuery());
		if (target.equals("show")) {
			String id = params.getOrDefault("id", "").trim();
			if (!FLOW_ID.matcher(id).matches()) {
				error(es, "El enlace de Traza no trae un identificador de flujo válido (parámetro id).",
					"The Traza link has no valid flow identifier (id parameter).");
				return;
			}
			boolean changes = params.getOrDefault("mode", "").trim().equalsIgnoreCase("changes");
			ShowFlowHandler.showFlow(id, changes ? "changes" : null);
			return;
		}
		if (busy) {
			MessageDialog.openInformation(Ui.shell(), "Traza", es ? "Traza ya está procesando un enlace. Espera a que termine."
				: "Traza is already processing a link. Wait for it to finish.");
			return;
		}
		if (!target.equals("flow")) {
			error(es, "Enlace de Traza no reconocido. Usa traza://flow?q=<pregunta>.", "Unrecognized Traza link. Use traza://flow?q=<question>.");
			return;
		}
		String rawMode = params.getOrDefault("mode", "").trim().toLowerCase(Locale.ROOT);
		String mode = MODES.contains(rawMode) ? rawMode : null;
		String q = params.getOrDefault("q", "").trim();
		String change = params.getOrDefault("change", "").trim();
		String question;
		if ("change".equals(mode)) {
			if (change.isEmpty() || change.length() > Guards.MAX_ASK_CHARS) {
				error(es, "El enlace de Traza no trae un cambio válido (parámetro change, de 1 a 4000 caracteres).",
					"The Traza link has no valid change (change parameter, 1 to 4000 characters).");
				return;
			}
			if (q.length() > Guards.MAX_ASK_CHARS) {
				error(es, "El enlace de Traza no trae una pregunta válida (parámetro q, de 1 a 4000 caracteres).",
					"The Traza link has no valid question (q parameter, 1 to 4000 characters).");
				return;
			}
			question = q.isEmpty() ? change : q;
		} else {
			if (q.isEmpty() || q.length() > Guards.MAX_ASK_CHARS) {
				error(es, "El enlace de Traza no trae una pregunta válida (parámetro q, de 1 a 4000 caracteres).",
					"The Traza link has no valid question (q parameter, 1 to 4000 characters).");
				return;
			}
			question = q;
		}
		if (!WorkspaceFiles.hasProjects()) {
			error(es, "Abre una carpeta del proyecto para generar el flujo.", "Open a project folder to generate the flow.");
			return;
		}
		if (!AiCommands.ensureCopilot()) {
			return;
		}
		busy = true;
		try {
			String preview = previewQuestion(question);
			String folders = folderLabel();
			if ("change".equals(mode)) {
				boolean hasQuestion = !question.equals(change);
				String sendNote = es
					? "Traza enviará a GitHub Copilot las rutas y los fragmentos de código relevantes del workspace (nunca archivos sensibles como .env o claves, y con los secretos enmascarados). La propuesta solo se simulará en «Simular cambios»; no se modificará ningún archivo. Si no esperabas este enlace, cancela."
					: "Traza will send the relevant workspace paths and code fragments to GitHub Copilot (never sensitive files such as .env or keys, and with secrets masked). The proposal will only be simulated in \"Simular cambios\"; no file will be modified. If you did not expect this link, cancel.";
				StringBuilder detail = new StringBuilder();
				if (hasQuestion) {
					detail.append(es ? "Pregunta" : "Question").append(": ").append(preview).append("\n\n");
				}
				detail.append(es ? "Cambio" : "Change").append(": ").append(previewQuestion(change)).append("\n\n");
				detail.append(es ? "Carpeta" : "Folder").append(": ").append(folders).append("\n\n").append(sendNote);
				String button = es ? "Proponer cambio" : "Propose change";
				if (!confirm(es ? "Un enlace quiere proponer un cambio con Traza" : "A link wants to propose a change with Traza", detail.toString(), button)) {
					return;
				}
				AiCommands.sendChangeRequest(change, hasQuestion ? question : null, null);
				return;
			}
			String message = es ? "Un enlace quiere generar un flujo con Traza" : "A link wants to generate a flow with Traza";
			String button = es ? "Generar flujo" : "Generate flow";
			String detail = es
				? "Pregunta: " + preview + "\n\nCarpeta: " + folders
					+ "\n\nTraza enviará a GitHub Copilot las rutas y los fragmentos de código relevantes del workspace (nunca archivos sensibles como .env o claves, y con los secretos enmascarados). No se modificará ningún archivo. Si no esperabas este enlace, cancela."
				: "Question: " + preview + "\n\nFolder: " + folders
					+ "\n\nTraza will send the relevant workspace paths and code fragments to GitHub Copilot (never sensitive files such as .env or keys, and with secrets masked). No file will be modified. If you did not expect this link, cancel.";
			if (!confirm(message, detail, button)) {
				return;
			}
			AiCommands.sendFlowRequest(question, mode, null);
		} finally {
			busy = false;
		}
	}

	private static boolean confirm(String title, String detail, String button) {
		MessageDialog dialog = new MessageDialog(Ui.shell(), "Traza", null, title + "\n\n" + detail, MessageDialog.QUESTION,
			new String[] { button, Ui.t("Cancelar", "Cancel") }, 1);
		return dialog.open() == 0;
	}
}
