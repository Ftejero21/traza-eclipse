package com.ftejero21.traza.ui.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;

import com.ftejero21.traza.core.FlowStore;
import com.ftejero21.traza.core.FlowStore.GeneratedFlow;
import com.ftejero21.traza.ui.FlowView;
import com.ftejero21.traza.ui.Ui;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** «Traza: Visualizar flujo» (oculto, parametros flowId y mode): reabre un flujo de la sesion. Sin IA. */
public class ShowFlowHandler extends AbstractHandler {
	public static final String PARAM_FLOW_ID = "com.ftejero21.traza.commands.showFlow.flowId";
	public static final String PARAM_MODE = "com.ftejero21.traza.commands.showFlow.mode";
	private static final Pattern FLOW_ID = Pattern.compile("^[0-9a-f-]{36}$", Pattern.CASE_INSENSITIVE);

	@Override
	public Object execute(ExecutionEvent event) {
		showFlow(event.getParameter(PARAM_FLOW_ID), event.getParameter(PARAM_MODE));
		return null;
	}

	/** Port de showFlow: sin flowId, el ultimo flujo generado. Debe llamarse desde el hilo UI. */
	public static void showFlow(String flowId, String mode) {
		GeneratedFlow generated;
		if (flowId == null) {
			generated = FlowStore.last();
		} else {
			generated = FLOW_ID.matcher(flowId).matches() ? FlowStore.flowById(flowId) : null;
		}
		if (generated != null) {
			String id = flowId == null ? "" : flowId;
			List<JsonObject> saved = "changes".equals(mode) && !id.isEmpty() ? FlowStore.proposalsFor(id) : new ArrayList<>();
			if (!saved.isEmpty()) {
				FlowView.show(new FlowView.FlowPayload(generated.flow, false, generated.title, new ArrayList<JsonElement>(saved), generated.model, "changes"));
				return;
			}
			FlowView.showGenerated(generated, null);
			return;
		}
		MessageDialog.openInformation(Ui.shell(), "Traza", flowId == null
			? Ui.t("Todavía no se ha generado ningún flujo. Usa «Traza: Generar flujo».", "No flow has been generated yet. Use \"Traza: Generar flujo\".")
			: Ui.t("No se encontró ese flujo en esta sesión; vuelve a generarlo.", "That flow was not found in this session; generate it again."));
	}
}
