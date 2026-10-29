package com.ftejero21.traza.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.db.DbMetadataService;
import com.ftejero21.traza.mcp.JobFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Port de handleRequest (flow-panel.ts): atiende las peticiones ya validadas de la webview. */
final class WebviewBridge {
	private WebviewBridge() {
	}

	private static CompletableFuture<JsonElement> async(ExecutorService executor, Supplier<JsonElement> work) {
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		try {
			executor.execute(() -> {
				try {
					f.complete(work.get());
				} catch (RuntimeException e) {
					f.completeExceptionally(e);
				}
			});
		} catch (RuntimeException e) {
			f.completeExceptionally(e);
		}
		return f;
	}

	static CompletableFuture<JsonElement> handle(String id, JsonObject req, ExecutorService executor, Consumer<String> progress) {
		String op = J.str(req, "op");
		switch (op) {
		case "listFiles":
			return async(executor, () -> WorkspaceFiles.listFiles().toJson());
		case "readFile":
			return async(executor, () -> {
				Long max = J.integer(req.get("maxBytes"));
				return WorkspaceFiles.readFile(J.str(req, "fileId"), J.str(req, "as"), max).toJson();
			});
		case "readMountFile":
			return async(executor, () -> {
				String content = WorkspaceFiles.readMountFile(J.str(req, "mountId"), J.str(req, "path"), J.str(req, "as"), J.integer(req.get("maxBytes")));
				return content == null ? JsonNull.INSTANCE : new JsonPrimitive(content);
			});
		case "flow":
			return JobFactory.flowRequest(id, J.str(req, "kind"), req.getAsJsonObject("body"), progress);
		case "copy":
			return async(executor, () -> {
				Ui.sync(() -> {
					Clipboard cb = new Clipboard(Ui.display());
					try {
						cb.setContents(new Object[] { J.str(req, "text") }, new Transfer[] { TextTransfer.getInstance() });
					} finally {
						cb.dispose();
					}
					return Boolean.TRUE;
				});
				return new JsonPrimitive(true);
			});
		case "compareChange":
			return JobFactory.compareChange(id, J.str(req, "proposalId"), req.getAsJsonObject("body"), progress);
		case "dbExplain":
			return JobFactory.dbExplain(id, req.getAsJsonObject("body"), progress);
		case "applyFiles":
			return async(executor, () -> WorkspaceFiles.applyFiles(req.getAsJsonArray("writes")));
		case "confirm":
			return async(executor, () -> {
				String title = J.str(req, "title");
				String message = J.str(req, "message");
				String confirm = J.str(req, "confirm");
				Integer choice = Ui.sync(() -> {
					MessageDialog dialog = new MessageDialog(Ui.shell(), title.isEmpty() ? "Traza" : title, null, message, MessageDialog.WARNING,
						new String[] { confirm, Ui.t("Cancelar", "Cancel") }, 1);
					return dialog.open();
				});
				return new JsonPrimitive(choice != null && choice == 0);
			});
		case "dbConnections":
			return async(executor, () -> DbMetadataService.INSTANCE.list());
		case "dbMetadata":
			return async(executor, () -> DbMetadataService.INSTANCE.metadata(J.str(req, "key"), false));
		default: {
			CompletableFuture<JsonElement> f = new CompletableFuture<>();
			TrazaPlugin.warn("Operacion no permitida desde la webview.");
			f.completeExceptionally(new IllegalArgumentException("Operación no permitida."));
			return f;
		}
		}
	}
}
