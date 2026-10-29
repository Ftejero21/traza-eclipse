package com.ftejero21.traza.ui.prefs;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.ftejero21.traza.http.LocalHttpServer;
import com.ftejero21.traza.mcp.CopilotBridge;
import com.ftejero21.traza.mcp.McpConfig;
import com.ftejero21.traza.mcp.McpServer;
import com.ftejero21.traza.prefs.TrazaPreferences;
import com.ftejero21.traza.ui.Ui;

/** Preferencias de Traza: idioma, tema y apartado MCP (estado, configuracion para Copilot y token). */
public class TrazaPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
	private Combo language;
	private Combo theme;
	private Label serverLabel;
	private Label copilotLabel;
	private Button agentMode;

	@Override
	public void init(IWorkbench workbench) {
		noDefaultAndApplyButton();
	}

	@Override
	protected Control createContents(Composite parent) {
		Composite root = new Composite(parent, SWT.NONE);
		root.setLayout(new GridLayout(2, false));
		root.setLayoutData(new GridData(GridData.FILL_BOTH));

		new Label(root, SWT.NONE).setText("Idioma / Language:");
		language = new Combo(root, SWT.READ_ONLY);
		language.setItems("Español", "English");
		language.select(TrazaPreferences.isSpanish() ? 0 : 1);

		new Label(root, SWT.NONE).setText("Apariencia / Theme:");
		theme = new Combo(root, SWT.READ_ONLY);
		theme.setItems("Traza (paleta original)", "IDE (colores y fuentes de Eclipse)");
		theme.select("ide".equals(TrazaPreferences.themePref()) ? 1 : 0);

		Group mcp = new Group(root, SWT.NONE);
		mcp.setText("Servidor MCP (GitHub Copilot)");
		mcp.setLayout(new GridLayout(2, false));
		GridData gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.horizontalSpan = 2;
		mcp.setLayoutData(gd);

		serverLabel = new Label(mcp, SWT.WRAP);
		serverLabel.setLayoutData(span2());
		copilotLabel = new Label(mcp, SWT.WRAP);
		copilotLabel.setLayoutData(span2());

		Button copy = new Button(mcp, SWT.PUSH);
		copy.setText("Copiar configuración MCP para Copilot");
		copy.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				copyConfig();
			}
		});
		Button regenerate = new Button(mcp, SWT.PUSH);
		regenerate.setText("Regenerar token");
		regenerate.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				regenerateToken();
			}
		});
		Button refresh = new Button(mcp, SWT.PUSH);
		refresh.setText("Actualizar estado");
		refresh.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				updateStatus();
			}
		});

		agentMode = new Button(mcp, SWT.CHECK);
		agentMode.setLayoutData(span2());
		agentMode.setText(Ui.t(
			"Añadir el modo «Traza» al chat de Copilot (crea .github/agents/Traza.agent.md en un proyecto abierto y lo excluye de Git)",
			"Add the \"Traza\" mode to the Copilot chat (creates .github/agents/Traza.agent.md in an open project and excludes it from Git)"));
		agentMode.setSelection(TrazaPreferences.copilotAgentMode());

		Label help = new Label(mcp, SWT.WRAP);
		help.setLayoutData(span2());
		help.setText("Primer uso: abre el chat de GitHub Copilot, elige el modo Traza en el selector (o Agent) y aprueba el servidor \"Traza: traza\" (aviso rojo del icono de herramientas MCP del chat, "
			+ "o Preferencias > GitHub Copilot > MCP). Opcional: activa la autoaprobación de herramientas de solo lectura en las preferencias de Copilot.\n"
			+ "Si la política de Copilot desactiva las contribuciones MCP de plugins, usa \"Copiar configuración MCP para Copilot\" y pégala en Preferencias > GitHub Copilot > MCP.");
		updateStatus();
		return root;
	}

	private static GridData span2() {
		GridData d = new GridData(GridData.FILL_HORIZONTAL);
		d.horizontalSpan = 2;
		d.widthHint = 460;
		return d;
	}

	private void updateStatus() {
		LocalHttpServer.ensureStarted();
		serverLabel.setText(LocalHttpServer.isRunning()
			? "Servidor MCP: activo en 127.0.0.1:" + LocalHttpServer.port() + " (solo loopback, con token)"
			: "Servidor MCP: detenido (consulta el registro de errores).");
		String copilot = CopilotBridge.isInstalled() ? "GitHub Copilot for Eclipse: instalado" : "GitHub Copilot for Eclipse: no instalado";
		copilotLabel.setText(copilot + " · Conectado: " + (McpServer.copilotConnected() ? "sí (se ha recibido initialize)" : "no"));
		serverLabel.getParent().layout(true, true);
	}

	private void copyConfig() {
		String json = McpConfig.serversJson();
		Clipboard cb = new Clipboard(getShell().getDisplay());
		try {
			cb.setContents(new Object[] { json }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			cb.dispose();
		}
		MessageDialog.openInformation(getShell(), "Traza",
			"Configuración MCP copiada al portapapeles. Contiene el token del servidor: no la compartas. Pégala en Preferencias > GitHub Copilot > MCP.");
	}

	private void regenerateToken() {
		if (!MessageDialog.openConfirm(getShell(), "Traza",
			"Se generará un token nuevo. Tendrás que volver a aprobar el servidor \"Traza: traza\" en Copilot (y, si lo configuraste a mano, pegar la nueva configuración). ¿Continuar?")) {
			return;
		}
		McpConfig.regenerateToken();
		updateStatus();
		MessageDialog.openInformation(getShell(), "Traza",
			"Token regenerado. Reinicia el chat de Copilot (o Eclipse) y aprueba de nuevo el servidor \"Traza: traza\".");
	}

	@Override
	public boolean performOk() {
		TrazaPreferences.setLanguage(language.getSelectionIndex() == 1 ? "en" : "es");
		TrazaPreferences.setTheme(theme.getSelectionIndex() == 1 ? "ide" : "traza");
		TrazaPreferences.setCopilotAgentMode(agentMode.getSelection());
		return true;
	}

	/** Devuelve el idioma de interfaz para textos auxiliares. */
	static String t(String es, String en) {
		return Ui.t(es, en);
	}
}
