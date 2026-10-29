package com.ftejero21.traza.ui;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.prefs.TrazaPreferences;

/** Ayudas de hilo UI y avisos. */
public final class Ui {
	private Ui() {
	}

	public static Display display() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		return PlatformUI.getWorkbench().getDisplay();
	}

	public static void async(Runnable r) {
		Display d = display();
		if (d == null || d.isDisposed()) {
			return;
		}
		d.asyncExec(() -> {
			try {
				r.run();
			} catch (RuntimeException e) {
				TrazaPlugin.error("Error en una tarea de la interfaz.", e);
			}
		});
	}

	/** Ejecuta en el hilo UI y devuelve el resultado (si ya estamos en el hilo UI, directamente). */
	public static <T> T sync(Supplier<T> s) {
		Display d = display();
		if (d == null || d.isDisposed()) {
			return null;
		}
		if (Display.getCurrent() == d) {
			return s.get();
		}
		AtomicReference<T> out = new AtomicReference<>();
		AtomicReference<RuntimeException> err = new AtomicReference<>();
		d.syncExec(() -> {
			try {
				out.set(s.get());
			} catch (RuntimeException e) {
				err.set(e);
			}
		});
		if (err.get() != null) {
			throw err.get();
		}
		return out.get();
	}

	public static Shell shell() {
		Display d = display();
		if (d == null) {
			return null;
		}
		IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (w != null) {
			return w.getShell();
		}
		return d.getActiveShell();
	}

	public static IWorkbenchPage page() {
		IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (w == null && PlatformUI.getWorkbench().getWorkbenchWindowCount() > 0) {
			w = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
		}
		return w == null ? null : w.getActivePage();
	}

	public static boolean spanish() {
		return TrazaPreferences.isSpanish();
	}

	/** Texto segun el idioma de la preferencia. */
	public static String t(String es, String en) {
		return spanish() ? es : en;
	}

	public static void info(String title, String message) {
		async(() -> MessageDialog.openInformation(shell(), title, message));
	}

	public static void warning(String title, String message) {
		async(() -> MessageDialog.openWarning(shell(), title, message));
	}

	public static void error(String title, String message) {
		async(() -> MessageDialog.openError(shell(), title, message));
	}

	/** Aviso no modal (popup de notificacion); si no se puede mostrar, dialogo normal. */
	public static void notice(String title, String message) {
		async(() -> {
			try {
				org.eclipse.jface.notifications.NotificationPopup.forDisplay(display()).title(title, false).text(message).delay(8000).open();
			} catch (RuntimeException e) {
				TrazaPlugin.warn("No se pudo mostrar la notificacion: " + e.getClass().getSimpleName());
				MessageDialog.openInformation(shell(), title, message);
			}
		});
	}
}
