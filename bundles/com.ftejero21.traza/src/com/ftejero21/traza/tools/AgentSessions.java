package com.ftejero21.traza.tools;

import java.util.function.Consumer;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ftejero21.traza.ai.CancelToken;
import com.ftejero21.traza.ai.Msg;

/** Sesion compartida de las herramientas de solo lectura (TTL de 10 min, invalidada por los cambios de recursos). */
public final class AgentSessions {
	private static final long TTL_MS = 10 * 60_000L;

	private static AgentToolSession session;
	private static long createdAt;
	private static IResourceChangeListener listener;
	private static volatile Consumer<String> progressSink;

	private AgentSessions() {
	}

	/** Destino de los avisos de progreso de las herramientas (por ejemplo, el job activo). */
	public static void setProgressSink(Consumer<String> sink) {
		progressSink = sink;
	}

	/** Sesion vigente; la crea si hace falta. Lanza IllegalStateException si no hay carpetas abiertas. */
	public static synchronized AgentToolSession get() {
		long now = System.currentTimeMillis();
		if (session == null || now - createdAt > TTL_MS) {
			AgentToolSession fresh = new AgentToolSession(CancelToken.NONE, Msg.language(), msg -> {
				Consumer<String> sink = progressSink;
				if (sink != null) {
					sink.accept(msg);
				}
			});
			fresh.init();
			session = fresh;
			createdAt = now;
			install();
		}
		return session;
	}

	public static synchronized void invalidate() {
		session = null;
	}

	private static void install() {
		if (listener != null) {
			return;
		}
		listener = (IResourceChangeEvent event) -> invalidate();
		ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
	}

	public static synchronized void dispose() {
		session = null;
		if (listener != null) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
			listener = null;
		}
	}
}
