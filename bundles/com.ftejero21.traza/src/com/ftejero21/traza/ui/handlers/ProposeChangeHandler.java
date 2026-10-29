package com.ftejero21.traza.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import com.ftejero21.traza.core.EditorHint;

/** «Traza: Proponer cambio»: pide el cambio y abre el chat de Copilot; el resultado se abre en «Simular cambios». */
public class ProposeChangeHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		if (!AiCommands.ensureCopilot()) {
			return null;
		}
		if (!AiCommands.ensureProjects("Abre una carpeta del proyecto para proponer un cambio.", "Open a project folder to propose a change.")) {
			return null;
		}
		String request = AiCommands.askQuestion("Traza: Proponer cambio", "¿Qué cambio quieres? (se genera el flujo si no hay uno relacionado abierto)");
		if (request == null) {
			return null;
		}
		AiCommands.sendChangeRequest(request, null, EditorHint.captureActive());
		return null;
	}
}
