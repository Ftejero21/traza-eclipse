package com.ftejero21.traza.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;

import com.ftejero21.traza.core.FlowStore;
import com.ftejero21.traza.core.FlowStore.GeneratedFlow;
import com.ftejero21.traza.ui.FlowView;
import com.ftejero21.traza.ui.Ui;

/** «Traza: Mostrar ultimo flujo generado». */
public class ShowLastFlowHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		GeneratedFlow last = FlowStore.last();
		if (last != null) {
			FlowView.showGenerated(last, null);
		} else {
			MessageDialog.openInformation(Ui.shell(), "Traza", "Todavía no se ha generado ningún flujo. Usa «Traza: Generar flujo».");
		}
		return null;
	}
}
