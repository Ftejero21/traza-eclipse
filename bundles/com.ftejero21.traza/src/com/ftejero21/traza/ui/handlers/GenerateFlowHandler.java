package com.ftejero21.traza.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import com.ftejero21.traza.core.EditorHint;

/** «Traza: Generar flujo»: pregunta, editor activo y seleccion como pista, y chat de Copilot con autoSend. */
public class GenerateFlowHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		if (!AiCommands.ensureCopilot()) {
			return null;
		}
		if (!AiCommands.ensureProjects("Abre una carpeta del proyecto para generar el flujo.", "Open a project folder to generate the flow.")) {
			return null;
		}
		String question = AiCommands.askQuestion("Traza: Generar flujo", "¿Qué proceso quieres ver? (p. ej. «¿Cómo funciona el login?»)");
		if (question == null) {
			return null;
		}
		AiCommands.sendFlowRequest(question, null, EditorHint.captureActive());
		return null;
	}
}
