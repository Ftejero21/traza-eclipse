package com.ftejero21.traza.ui.handlers;

import org.eclipse.jface.dialogs.MessageDialog;

import com.ftejero21.traza.core.EditorHint;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.mcp.CopilotBridge;
import com.ftejero21.traza.mcp.TaskRegistry;
import com.ftejero21.traza.mcp.TaskRegistry.PendingRequest;
import com.ftejero21.traza.ui.Dialogs;
import com.ftejero21.traza.ui.Ui;

/** Logica comun de «Generar flujo», «Proponer cambio» y los enlaces traza://: registra la peticion y abre el chat de Copilot. */
public final class AiCommands {
	public static final int MAX_QUESTION_CHARS = 4000;

	private AiCommands() {
	}

	/** Copilot disponible; si no, dialogo con la ayuda de instalacion. */
	public static boolean ensureCopilot() {
		if (CopilotBridge.isAvailable()) {
			return true;
		}
		MessageDialog.openInformation(Ui.shell(), "Traza", CopilotBridge.missingMessage());
		return false;
	}

	/** Pregunta con InputDialog (maximo 4000 caracteres); null si esta vacia o se cancela. */
	public static String askQuestion(String title, String prompt) {
		String value = Dialogs.input(title, prompt, "", v -> v.length() > MAX_QUESTION_CHARS ? "Máximo " + MAX_QUESTION_CHARS + " caracteres." : null);
		return value == null || value.isEmpty() ? null : value;
	}

	/** Registra la peticion de flujo (con el editor activo y la seleccion de ahora) y abre el chat con autoSend. */
	public static boolean sendFlowRequest(String question, String mode, EditorHint hint) {
		PendingRequest p = new PendingRequest();
		p.kind = "FLOW";
		p.hint = hint;
		p.mode = mode;
		TaskRegistry.addPending(p);
		String q = J.GSON.toJson(question);
		String prompt = Ui.t(
			"Traza: genera el flujo de «" + question + "». Llama a la herramienta traza_flow con question=" + q + " y requestId=\"" + p.requestId + "\".",
			"Traza: generate the flow of «" + question + "». Call the traza_flow tool with question=" + q + " and requestId=\"" + p.requestId + "\".");
		return send(prompt);
	}

	/** Registra la peticion de cambio y abre el chat con autoSend. */
	public static boolean sendChangeRequest(String request, String flowQuestion, EditorHint hint) {
		PendingRequest p = new PendingRequest();
		p.kind = "CHANGE";
		p.hint = hint;
		TaskRegistry.addPending(p);
		String q = J.GSON.toJson(request);
		String fq = flowQuestion != null && !flowQuestion.equals(request) ? " flowQuestion=" + J.GSON.toJson(flowQuestion) + "," : "";
		String prompt = Ui.t(
			"Traza: propone el cambio «" + request + "». Llama a la herramienta traza_change con request=" + q + "," + fq + " requestId=\"" + p.requestId + "\".",
			"Traza: propose the change «" + request + "». Call the traza_change tool with request=" + q + "," + fq + " requestId=\"" + p.requestId + "\".");
		return send(prompt);
	}

	private static boolean send(String prompt) {
		CopilotBridge.showNoticeOnce();
		if (CopilotBridge.openChatInTrazaMode(prompt, true)) {
			return true;
		}
		MessageDialog.openError(Ui.shell(), "Traza", Ui.t("No se pudo abrir el chat de GitHub Copilot.", "The GitHub Copilot chat could not be opened."));
		return false;
	}

	/** Hay proyectos abiertos; si no, avisa. */
	public static boolean ensureProjects(String es, String en) {
		if (WorkspaceFiles.hasProjects()) {
			return true;
		}
		MessageDialog.openError(Ui.shell(), "Traza", Ui.t(es, en));
		return false;
	}
}
