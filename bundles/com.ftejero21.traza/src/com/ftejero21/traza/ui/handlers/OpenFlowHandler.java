package com.ftejero21.traza.ui.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.FlowJson;
import com.ftejero21.traza.core.FlowJson.Parsed;
import com.ftejero21.traza.ui.FlowView;
import com.ftejero21.traza.ui.Ui;

/** «Traza: Abrir flujo…»: archivo JSON, JSON del editor activo o ejemplo; un *.flow.json seleccionado o abierto se abre directamente. */
public class OpenFlowHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		IFile file = flowFileFromSelection(HandlerUtil.getCurrentSelection(event));
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		if (file == null && editor != null) {
			IFile f = Adapters.adapt(editor.getEditorInput(), IFile.class);
			file = isFlowFile(f) ? f : null;
		}
		if (file != null) {
			String text = readFile(file);
			if (text != null) {
				open(text);
			}
			return null;
		}
		boolean jsonEditor = editor instanceof ITextEditor && editor.getEditorInput() != null && editor.getEditorInput().getName().toLowerCase().endsWith(".json");
		List<String> labels = new ArrayList<>();
		labels.add(Ui.t("Abrir archivo JSON…", "Open JSON file…"));
		if (jsonEditor) {
			labels.add(Ui.t("Usar JSON del editor activo", "Use JSON of the active editor"));
		}
		labels.add(Ui.t("Flujo de ejemplo", "Sample flow"));
		labels.add(Ui.t("Cancelar", "Cancel"));
		MessageDialog dialog = new MessageDialog(Ui.shell(), "Traza", null, Ui.t("Traza: ¿qué flujo quieres abrir?", "Traza: which flow do you want to open?"),
			MessageDialog.QUESTION, labels.toArray(new String[0]), 0);
		int choice = dialog.open();
		if (choice < 0 || choice >= labels.size() - 1) {
			return null;
		}
		String label = labels.get(choice);
		if (label.equals(labels.get(labels.size() - 2))) {
			FlowView.showSample();
			return null;
		}
		String text = jsonEditor && choice == 1 ? editorText((ITextEditor) editor) : pickFile();
		if (text != null) {
			open(text);
		}
		return null;
	}

	private static boolean isFlowFile(IFile f) {
		return f != null && f.getName().toLowerCase().endsWith(".flow.json");
	}

	private static IFile flowFileFromSelection(ISelection selection) {
		if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
			IFile f = Adapters.adapt(((IStructuredSelection) selection).getFirstElement(), IFile.class);
			return isFlowFile(f) ? f : null;
		}
		return null;
	}

	private static String editorText(ITextEditor editor) {
		IEditorInput input = editor.getEditorInput();
		IDocument doc = editor.getDocumentProvider().getDocument(input);
		return doc == null ? null : doc.get();
	}

	private static String readFile(IFile file) {
		try (InputStream in = file.getContents(true)) {
			byte[] bytes = in.readNBytes(FlowJson.MAX_JSON_BYTES + 1);
			if (bytes.length > FlowJson.MAX_JSON_BYTES) {
				Ui.error("Traza", "El JSON supera el límite de 2 MB.");
				return null;
			}
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (CoreException | IOException e) {
			TrazaPlugin.warn("No se pudo leer el archivo JSON (" + e.getClass().getSimpleName() + ").");
			Ui.error("Traza", "No se pudo leer el archivo JSON.");
			return null;
		}
	}

	private static String pickFile() {
		FileDialog fd = new FileDialog(Ui.shell(), SWT.OPEN);
		fd.setText("Traza: Abrir flujo");
		fd.setFilterExtensions(new String[] { "*.json", "*.*" });
		String chosen = fd.open();
		if (chosen == null) {
			return null;
		}
		try {
			Path p = Paths.get(chosen);
			if (Files.size(p) > FlowJson.MAX_JSON_BYTES) {
				Ui.error("Traza", "El JSON supera el límite de 2 MB.");
				return null;
			}
			return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			TrazaPlugin.warn("No se pudo leer el archivo JSON (" + e.getClass().getSimpleName() + ").");
			Ui.error("Traza", "No se pudo leer el archivo JSON.");
			return null;
		}
	}

	private static void open(String text) {
		Parsed parsed = FlowJson.parse(text);
		if (parsed == null) {
			return;
		}
		FlowView.show(new FlowView.FlowPayload(parsed.flow, false, FlowJson.titleOf(parsed.flow), parsed.proposals, null, null));
	}
}
