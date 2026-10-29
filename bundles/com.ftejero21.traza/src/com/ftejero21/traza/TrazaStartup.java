package com.ftejero21.traza;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IStartup;

import com.ftejero21.traza.http.LocalHttpServer;
import com.ftejero21.traza.mcp.CopilotBridge;

/** Si GitHub Copilot esta instalado, arranca el servidor local en segundo plano (funciona tambien la configuracion manual y se fija el puerto). */
public class TrazaStartup implements IStartup {
	@Override
	public void earlyStartup() {
		if (!CopilotBridge.isInstalled()) {
			return;
		}
		Job job = new Job("Traza: servidor local") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				LocalHttpServer.ensureStarted();
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule();
	}
}
