package com.ftejero21.traza.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.Guards;
import com.ftejero21.traza.db.DbConfig;
import com.ftejero21.traza.db.DbConfigStore;
import com.ftejero21.traza.db.DbMetadataService;
import com.ftejero21.traza.db.DbMetadataService.DbException;
import com.ftejero21.traza.db.DbPasswords;
import com.ftejero21.traza.db.SpringCandidates;
import com.ftejero21.traza.db.SpringCandidates.Candidate;

/** Flujos de añadir, editar, quitar, contraseña y probar conexiones de base de datos (port de los comandos de host-db.ts). */
public final class DbCommands {
	private static final String ADD_TITLE = "Traza: Añadir conexión de base de datos";
	private static final Pattern PORT = Pattern.compile("^\\d{1,5}$");

	private DbCommands() {
	}

	private static void changed() {
		DbMetadataService.INSTANCE.clearCache();
	}

	/** Elige una conexion existente; null si no hay o se cancela. */
	public static DbConfig pickConnection(String message) {
		List<DbConfig> list = DbConfigStore.readAll();
		if (list.isEmpty()) {
			MessageDialog.openInformation(Ui.shell(), "Traza", "No hay conexiones de base de datos. Usa \"Traza: Añadir conexión de base de datos\".");
			return null;
		}
		List<String> labels = new ArrayList<>();
		for (DbConfig c : list) {
			labels.add(c.name + "  (" + c.provider + " · " + c.database + ")");
		}
		int i = Dialogs.pick("Traza", message, labels);
		return i < 0 ? null : list.get(i);
	}

	/** Pide la contrasena y la guarda en el almacen seguro. Devuelve true si se cambio algo. */
	public static boolean askPassword(DbConfig config) {
		String value = Dialogs.password("Contraseña de «" + config.name + "»",
			"Se guarda en el almacén seguro de Eclipse (nunca en las preferencias). Vacío = borrar.");
		if (value == null) {
			return false;
		}
		if (value.isEmpty()) {
			DbPasswords.remove(config.id);
			Ui.notice("Traza", "Contraseña de la base de datos borrada.");
			changed();
			return true;
		}
		if (!DbPasswords.set(config.id, value)) {
			Ui.error("Traza", "No se pudo guardar la contraseña en el almacén seguro.");
			return false;
		}
		Ui.notice("Traza", "Contraseña de la base de datos guardada.");
		changed();
		return true;
	}

	public static void setPassword() {
		DbConfig config = pickConnection("Conexión cuya contraseña quieres cambiar");
		if (config != null) {
			askPassword(config);
		}
	}

	private static String required(String v, String message) {
		return !v.isEmpty() && v.length() <= DbConfig.MAX_NAME ? null : message;
	}

	/** Añade una conexion (port de addDatabase). Devuelve true si se añadio. */
	public static boolean addDatabase() {
		return collect(null);
	}

	/** Edita una conexion existente con los mismos dialogos y validaciones. */
	public static boolean editDatabase(DbConfig existing) {
		return collect(existing);
	}

	private static boolean collect(DbConfig edit) {
		List<DbConfig> existing = DbConfigStore.readAll();
		Candidate base = null;
		if (edit == null) {
			List<Candidate> candidates = SpringCandidates.find();
			if (!candidates.isEmpty()) {
				List<String> labels = new ArrayList<>();
				for (Candidate c : candidates) {
					labels.add(c.database + "  (" + c.provider + " · " + c.host + ":" + c.port + ")  " + c.file);
				}
				labels.add("Introducir los datos a mano");
				int i = Dialogs.pick(ADD_TITLE, "Datasource detectado en el proyecto (solo URL y usuario)", labels);
				if (i < 0) {
					return false;
				}
				base = i < candidates.size() ? candidates.get(i) : null;
			}
		}
		String title = edit == null ? ADD_TITLE : "Traza: Editar conexión de base de datos";
		String id;
		if (edit != null) {
			id = edit.id;
		} else {
			String initial = base != null ? base.database.replaceAll("[^A-Za-z0-9_-]", "-") : "";
			if (initial.length() > 40) {
				initial = initial.substring(0, 40);
			}
			id = Dialogs.input(title, "Identificador de la conexión", initial, v -> !Guards.DB_KEY_PATTERN.matcher(v).matches()
				? "Solo letras, números, \"-\" y \"_\" (1–40)." : existing.stream().anyMatch(c -> c.id.equals(v)) ? "Ya existe una conexión con ese id." : null);
			if (id == null) {
				return false;
			}
		}
		String name = Dialogs.input(title, "Nombre visible", edit != null ? edit.name : base != null ? base.database : id,
			v -> required(v, "Nombre obligatorio."));
		if (name == null) {
			return false;
		}
		List<String> providers = DbConfig.PROVIDERS;
		int pi = Dialogs.pick(title, "Proveedor", providers);
		if (pi < 0) {
			return false;
		}
		String provider = providers.get(pi);
		String host = Dialogs.input(title, "Host", edit != null ? edit.host : base != null ? base.host : "localhost",
			v -> DbConfig.HOST_PATTERN.matcher(v).matches() ? null : "Host no válido.");
		if (host == null) {
			return false;
		}
		String defaultPort = String.valueOf(edit != null ? edit.port : base != null ? base.port : provider.equals("postgresql") ? 5432 : 3306);
		String portText = Dialogs.input(title, "Puerto", defaultPort,
			v -> PORT.matcher(v).matches() && Integer.parseInt(v) >= 1 && Integer.parseInt(v) <= 65535 ? null : "Puerto no válido.");
		if (portText == null) {
			return false;
		}
		String database = Dialogs.input(title, "Base de datos", edit != null ? edit.database : base != null ? base.database : "",
			v -> required(v, "Base de datos obligatoria."));
		if (database == null) {
			return false;
		}
		String user = Dialogs.input(title, "Usuario (solo lectura recomendado)", edit != null ? edit.user : base != null ? base.user : "",
			v -> required(v, "Usuario obligatorio."));
		if (user == null) {
			return false;
		}
		String schema = null;
		if (provider.equals("postgresql")) {
			schema = Dialogs.input(title, "Esquema", edit != null && edit.schema != null ? edit.schema : "public", v -> required(v, "Esquema obligatorio."));
			if (schema == null) {
				return false;
			}
		}
		boolean ssl = false;
		if (!DbConfig.isLocalHost(host)) {
			boolean useTls = MessageDialog.openQuestion(Ui.shell(), title,
				"Conexión remota: se usará TLS con verificación del certificado (obligatorio para servidores remotos).\n\n¿Continuar?");
			if (!useTls) {
				return false;
			}
			ssl = true;
		}
		String root = Dialogs.input(title, "Carpeta del proyecto que usa esta base de datos (vacío = todas)", edit != null && edit.root != null ? edit.root : base != null ? base.root : "",
			v -> v.isEmpty() || Guards.normalizeCodePath(v) != null ? null : "Ruta no válida.");
		if (root == null) {
			return false;
		}
		DbConfig config = new DbConfig();
		config.id = id;
		config.name = name;
		config.provider = provider;
		config.host = host;
		config.port = Integer.parseInt(portText);
		config.database = database;
		config.user = user;
		config.root = Guards.normalizeCodePath(root) == null ? "" : Guards.normalizeCodePath(root);
		config.ssl = ssl;
		if (schema != null) {
			config.schema = schema;
		}
		try {
			if (edit == null) {
				DbConfigStore.append(config);
			} else {
				DbConfigStore.replace(config);
			}
		} catch (RuntimeException e) {
			TrazaPlugin.warn("No se pudo guardar la conexion (" + e.getClass().getSimpleName() + ").");
			Ui.error("Traza", "No se pudo guardar la conexión de base de datos.");
			return false;
		}
		if (edit == null) {
			askPassword(config);
		}
		changed();
		Ui.notice("Traza", "Conexión «" + name + "» " + (edit == null ? "añadida." : "actualizada."));
		return true;
	}

	/** Quita la conexion y su contrasena. */
	public static boolean removeDatabase(DbConfig config) {
		if (!MessageDialog.openConfirm(Ui.shell(), "Traza", "¿Quitar la conexión «" + config.name + "» y su contraseña?")) {
			return false;
		}
		try {
			DbConfigStore.remove(config.id);
			DbPasswords.remove(config.id);
		} catch (RuntimeException e) {
			TrazaPlugin.warn("No se pudo quitar la conexion (" + e.getClass().getSimpleName() + ").");
			Ui.error("Traza", "No se pudo quitar la conexión de base de datos.");
			return false;
		}
		changed();
		Ui.notice("Traza", "Conexión «" + config.name + "» quitada.");
		return true;
	}

	public static void removeDatabase() {
		DbConfig config = pickConnection("Conexión que quieres quitar");
		if (config != null) {
			removeDatabase(config);
		}
	}

	/** Prueba la conexion en segundo plano y avisa con el numero de tablas. */
	public static void testDatabase(DbConfig config) {
		Job job = new Job("Probando «" + config.name + "»…") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					int tables = DbMetadataService.INSTANCE.test(config.id);
					Ui.info("Traza", "Conexión «" + config.name + "» correcta: " + tables + " tablas.");
				} catch (DbException e) {
					Ui.error("Traza", e.getMessage());
				} catch (RuntimeException e) {
					TrazaPlugin.error("Fallo al probar una conexion de base de datos.", e);
					Ui.error("Traza", DbMetadataService.msg("generic"));
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
	}

	public static void testDatabase() {
		DbConfig config = pickConnection("Conexión que quieres probar");
		if (config != null) {
			testDatabase(config);
		}
	}
}
