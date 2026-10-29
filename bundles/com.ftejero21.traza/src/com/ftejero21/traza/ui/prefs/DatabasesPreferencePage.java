package com.ftejero21.traza.ui.prefs;

import java.util.List;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.ftejero21.traza.db.DbConfig;
import com.ftejero21.traza.db.DbConfigStore;
import com.ftejero21.traza.db.DbPasswords;
import com.ftejero21.traza.ui.DbCommands;

/** Subpagina «Bases de datos»: conexiones (la contrasena va en el secure storage, nunca en las preferencias). */
public class DatabasesPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
	private Table table;
	private List<DbConfig> configs;

	@Override
	public void init(IWorkbench workbench) {
		noDefaultAndApplyButton();
	}

	@Override
	protected Control createContents(Composite parent) {
		Composite root = new Composite(parent, SWT.NONE);
		root.setLayout(new GridLayout(2, false));
		root.setLayoutData(new GridData(GridData.FILL_BOTH));

		Label info = new Label(root, SWT.WRAP);
		GridData gi = new GridData(GridData.FILL_HORIZONTAL);
		gi.horizontalSpan = 2;
		gi.widthHint = 460;
		info.setLayoutData(gi);
		info.setText("Conexiones de solo lectura (PostgreSQL, MySQL, MariaDB) para el modo Base de datos. "
			+ "La contraseña se guarda en el almacén seguro de Eclipse; los servidores remotos exigen TLS.");

		table = new Table(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		GridData gt = new GridData(GridData.FILL_BOTH);
		gt.heightHint = 220;
		table.setLayoutData(gt);
		String[] titles = { "Nombre", "Proveedor", "Servidor", "Base de datos", "Contraseña" };
		int[] widths = { 130, 90, 140, 120, 80 };
		for (int i = 0; i < titles.length; i++) {
			TableColumn c = new TableColumn(table, SWT.NONE);
			c.setText(titles[i]);
			c.setWidth(widths[i]);
		}

		Composite buttons = new Composite(root, SWT.NONE);
		buttons.setLayout(new GridLayout(1, false));
		buttons.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));
		button(buttons, "Añadir…", () -> {
			if (DbCommands.addDatabase()) {
				refresh();
			}
		});
		button(buttons, "Editar…", () -> {
			DbConfig c = selected();
			if (c != null && DbCommands.editDatabase(c)) {
				refresh();
			}
		});
		button(buttons, "Quitar", () -> {
			DbConfig c = selected();
			if (c != null && DbCommands.removeDatabase(c)) {
				refresh();
			}
		});
		button(buttons, "Contraseña…", () -> {
			DbConfig c = selected();
			if (c != null && DbCommands.askPassword(c)) {
				refresh();
			}
		});
		button(buttons, "Probar", () -> {
			DbConfig c = selected();
			if (c != null) {
				DbCommands.testDatabase(c);
			}
		});
		refresh();
		return root;
	}

	private void button(Composite parent, String text, Runnable action) {
		Button b = new Button(parent, SWT.PUSH);
		b.setText(text);
		b.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		b.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				action.run();
			}
		});
	}

	private DbConfig selected() {
		int i = table.getSelectionIndex();
		return i < 0 || i >= configs.size() ? null : configs.get(i);
	}

	private void refresh() {
		configs = DbConfigStore.readAll();
		table.removeAll();
		for (DbConfig c : configs) {
			TableItem item = new TableItem(table, SWT.NONE);
			item.setText(new String[] { c.name, c.provider, c.host + ":" + c.port, c.database, DbPasswords.has(c.id) ? "guardada" : "falta" });
		}
	}
}
