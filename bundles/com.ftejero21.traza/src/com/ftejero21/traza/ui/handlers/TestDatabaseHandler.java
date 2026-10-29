package com.ftejero21.traza.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import com.ftejero21.traza.ui.DbCommands;

/** Comando de conexiones de base de datos. */
public class TestDatabaseHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		DbCommands.testDatabase();
		return null;
	}
}
