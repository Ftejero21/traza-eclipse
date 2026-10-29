package com.ftejero21.traza.core;

import java.net.URI;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ui.Ui;

/** Pista del editor activo (archivo y seleccion) capturada en el momento de la peticion. Equivale a useActiveEditor. */
public final class EditorHint {
	private static final int MAX_TEXT = 3 * 1024 * 1024;

	public String fileId;
	/** Texto completo del documento (con cambios sin guardar), o null si es demasiado grande. */
	public String text;
	/** Lineas de la seleccion (base 0) y su texto; selectedText vacio si no hay seleccion. */
	public int selStartLine;
	public int selEndLine;
	public String selectedText = "";

	/** Captura el editor de texto activo; null si no hay ninguno con un archivo del workspace. Seguro desde cualquier hilo. */
	public static EditorHint captureActive() {
		try {
			return Ui.sync(EditorHint::captureOnUi);
		} catch (RuntimeException e) {
			TrazaPlugin.warn("No se pudo capturar el editor activo (" + e.getClass().getSimpleName() + ").");
			return null;
		}
	}

	private static EditorHint captureOnUi() {
		IWorkbenchPage page = Ui.page();
		if (page == null) {
			return null;
		}
		IEditorPart editor = page.getActiveEditor();
		if (!(editor instanceof ITextEditor)) {
			return null;
		}
		ITextEditor te = (ITextEditor) editor;
		IEditorInput input = te.getEditorInput();
		IFile file = Adapters.adapt(input, IFile.class);
		if (file == null) {
			return null;
		}
		URI uri = file.getLocationURI();
		if (uri == null) {
			return null;
		}
		EditorHint hint = new EditorHint();
		hint.fileId = uri.toString();
		IDocumentProvider dp = te.getDocumentProvider();
		IDocument doc = dp == null ? null : dp.getDocument(input);
		if (doc != null && doc.getLength() <= MAX_TEXT) {
			hint.text = doc.get();
		}
		ISelection sel = te.getSelectionProvider() == null ? null : te.getSelectionProvider().getSelection();
		if (sel instanceof ITextSelection) {
			ITextSelection ts = (ITextSelection) sel;
			hint.selStartLine = Math.max(0, ts.getStartLine());
			hint.selEndLine = Math.max(hint.selStartLine, ts.getEndLine());
			if (ts.getLength() > 0 && ts.getText() != null) {
				hint.selectedText = ts.getText();
			}
		}
		return hint;
	}

	public boolean hasSelection() {
		return !selectedText.isEmpty();
	}
}
