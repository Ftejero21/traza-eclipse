package com.ftejero21.traza;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.ftejero21.traza.http.LocalHttpServer;
import com.ftejero21.traza.mcp.CopilotAgentFile;
import com.ftejero21.traza.mcp.JobRegistry;
import com.ftejero21.traza.tools.AgentSessions;

/** Activador del plugin Traza. Nunca registra codigo, secretos ni contenido de prompts. */
public class TrazaPlugin extends AbstractUIPlugin {
	public static final String ID = "com.ftejero21.traza";
	private static TrazaPlugin instance;

	public TrazaPlugin() {
		instance = this;
	}

	public static TrazaPlugin getDefault() {
		return instance;
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		instance = this;
	}

	/** Para el servidor, cancela los jobs en curso y libera la sesion de herramientas. */
	@Override
	public void stop(BundleContext context) throws Exception {
		try {
			JobRegistry.cancelEverything();
			AgentSessions.dispose();
			CopilotAgentFile.uninstall();
			LocalHttpServer.stop();
		} finally {
			instance = null;
			super.stop(context);
		}
	}

	public static ILog log() {
		return Platform.getLog(Platform.getBundle(ID));
	}

	public static void info(String msg) {
		log().log(new Status(IStatus.INFO, ID, msg));
	}

	public static void warn(String msg) {
		log().log(new Status(IStatus.WARNING, ID, msg));
	}

	public static void error(String msg, Throwable t) {
		log().log(new Status(IStatus.ERROR, ID, msg, t));
	}
}
