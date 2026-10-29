package com.ftejero21.traza.ui;

import java.util.List;
import java.util.function.Function;

import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ListDialog;

/** Dialogos sencillos (entrada de texto, contrasena y lista). Siempre en el hilo UI. */
public final class Dialogs {
	private Dialogs() {
	}

	private static final class PasswordDialog extends InputDialog {
		PasswordDialog(Shell shell, String title, String message, IInputValidator validator) {
			super(shell, title, message, "", validator);
		}

		@Override
		protected int getInputTextStyle() {
			return SWT.SINGLE | SWT.BORDER | SWT.PASSWORD;
		}
	}

	/** Texto introducido (recortado) o null si se cancela. El validador devuelve un mensaje de error o null. */
	public static String input(String title, String message, String initial, Function<String, String> validator) {
		InputDialog dialog = new InputDialog(Ui.shell(), title, message, initial == null ? "" : initial, text -> validator == null ? null : validator.apply(text.trim()));
		if (dialog.open() != Window.OK) {
			return null;
		}
		return dialog.getValue().trim();
	}

	/** Contrasena introducida (sin recortar) o null si se cancela. Vacia = borrar. */
	public static String password(String title, String message) {
		PasswordDialog dialog = new PasswordDialog(Ui.shell(), title, message, null);
		if (dialog.open() != Window.OK) {
			return null;
		}
		return dialog.getValue();
	}

	/** Indice de la opcion elegida o -1 si se cancela. */
	public static int pick(String title, String message, List<String> labels) {
		ListDialog dialog = new ListDialog(Ui.shell());
		dialog.setTitle(title);
		dialog.setMessage(message);
		dialog.setContentProvider(ArrayContentProvider.getInstance());
		dialog.setLabelProvider(new LabelProvider());
		dialog.setInput(labels);
		dialog.setInitialSelections(labels.isEmpty() ? new Object[0] : new Object[] { labels.get(0) });
		if (dialog.open() != Window.OK || dialog.getResult() == null || dialog.getResult().length == 0) {
			return -1;
		}
		return labels.indexOf(dialog.getResult()[0]);
	}
}
