package com.ftejero21.traza.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;

import com.ftejero21.traza.mcp.CopilotBridge;
import com.ftejero21.traza.ui.Ui;

/** «Traza: Elegir modelo»: abre el chat de Copilot; el modelo se elige en su selector. */
public class SelectModelHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		if (!AiCommands.ensureCopilot()) {
			return null;
		}
		CopilotBridge.openChatPlain();
		MessageDialog.openInformation(Ui.shell(), "Traza", Ui.t(
			"Traza usa GitHub Copilot: el modelo se elige en el selector de modelos del chat de GitHub Copilot.",
			"Traza uses GitHub Copilot: the model is chosen in the model picker of the GitHub Copilot chat."));
		return null;
	}
}
