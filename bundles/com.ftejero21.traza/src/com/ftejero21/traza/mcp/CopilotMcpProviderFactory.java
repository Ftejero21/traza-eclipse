package com.ftejero21.traza.mcp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IExecutableExtensionFactory;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.http.LocalHttpServer;

/**
 * Proveedor del punto de extension com.microsoft.copilot.eclipse.ui.mcpRegistration, creado por reflexion: devuelve un Proxy
 * que implementa IMcpRegistrationProvider (cargada desde el bundle de Copilot) sin ninguna dependencia de compilacion con
 * Copilot. Si Copilot no esta instalado, esta clase nunca se instancia.
 */
public class CopilotMcpProviderFactory implements IExecutableExtensionFactory {
	private static final String IFACE = "com.microsoft.copilot.eclipse.ui.extensions.IMcpRegistrationProvider";

	@Override
	public Object create() throws CoreException {
		try {
			Bundle copilot = Platform.getBundle(CopilotBridge.UI_BUNDLE);
			if (copilot == null) {
				throw new IllegalStateException("GitHub Copilot for Eclipse no esta instalado.");
			}
			Class<?> iface = copilot.loadClass(IFACE);
			InvocationHandler handler = (proxy, method, args) -> invoke(proxy, method, args);
			return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface }, handler);
		} catch (ClassNotFoundException | RuntimeException e) {
			TrazaPlugin.error("No se pudo registrar el servidor MCP de Traza en Copilot.", e);
			throw new CoreException(new Status(Status.ERROR, TrazaPlugin.ID, "No se pudo registrar el servidor MCP de Traza en Copilot.", e));
		}
	}

	private static Object invoke(Object proxy, Method method, Object[] args) {
		switch (method.getName()) {
		case "getMcpServerConfigurations":
			return CompletableFuture.supplyAsync(() -> {
				LocalHttpServer.ensureStarted();
				return McpConfig.serversJson();
			});
		case "equals":
			return args != null && args.length == 1 && proxy == args[0];
		case "hashCode":
			return System.identityHashCode(proxy);
		case "toString":
			return "TrazaMcpRegistrationProvider";
		default:
			throw new UnsupportedOperationException(method.getName());
		}
	}
}
