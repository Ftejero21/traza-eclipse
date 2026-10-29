package com.ftejero21.traza.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import com.ftejero21.traza.ui.FlowView;

/** «Traza: Abrir flujo de ejemplo». */
public class OpenSampleFlowHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		FlowView.showSample();
		return null;
	}
}
