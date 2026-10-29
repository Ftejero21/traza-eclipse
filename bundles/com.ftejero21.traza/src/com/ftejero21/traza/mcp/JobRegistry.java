package com.ftejero21.traza.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.ai.AiException;
import com.ftejero21.traza.ai.Checks;
import com.ftejero21.traza.ai.Checks.Validation;
import com.ftejero21.traza.ai.Msg;
import com.ftejero21.traza.ai.PromptBuilders;
import com.ftejero21.traza.ai.ResultCache;
import com.ftejero21.traza.core.J;
import com.ftejero21.traza.tools.AgentSessions;
import com.ftejero21.traza.ui.Ui;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Acciones de la webview que en VS Code llamaban al modelo, convertidas en jobs para Copilot: se abre el chat en modo Agent
 * con un prompt prellenado, Copilot lee la tarea con traza_get_job y entrega el resultado con traza_submit_job; el host
 * valida el resultado y responde a la webview. Solo se despacha un job al chat a la vez (cola FIFO).
 */
public final class JobRegistry {
	public static final String MODEL = "GitHub Copilot";
	private static final int MAX_ATTEMPTS = 3;
	private static final long TTL_MS = 10 * 60_000L;
	private static final long HINT_AFTER_MS = 90_000L;
	public static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

	/** Una tarea del job: una llamada al modelo (operacion, prompt y comprobacion de formato). */
	public static final class JobTask {
		public String taskId = UUID.randomUUID().toString();
		public String op;
		public String system;
		public String user;
		public boolean agent;
		public Function<JsonObject, Validation> check;
		public int attempts;
		public JsonElement result;
		public boolean cached;
		public String cacheKey;
	}

	private static final class Job {
		String id;
		String webId;
		String kind;
		String lang;
		String failureKey = "schema";
		List<JobTask> tasks;
		long created;
		long dispatchedAt;
		boolean touched;
		boolean hintShown;
		boolean closed;
		CompletableFuture<JsonElement> future;
		Function<List<JobTask>, JsonElement> finisher;
		Consumer<String> progress;
	}

	/** Resultado de una herramienta MCP. */
	public static final class ToolText {
		public final String text;
		public final boolean error;

		public ToolText(String text, boolean error) {
			this.text = text;
			this.error = error;
		}
	}

	private static final Map<String, Job> JOBS = new LinkedHashMap<>();
	private static final List<Job> QUEUE = new ArrayList<>();
	private static final Map<String, String> CLOSED = new LinkedHashMap<>();
	private static Job active;
	private static ScheduledExecutorService watchdog;

	private JobRegistry() {
	}

	/* ------------------------------ alta ------------------------------ */

	/** Crea un job con sus tareas; completa el futuro con el resultado final (o con AiException si falla). */
	public static CompletableFuture<JsonElement> submit(String webId, String kind, String lang, String failureKey, List<JobTask> tasks,
		Function<List<JobTask>, JsonElement> finisher, Consumer<String> progress) {
		CompletableFuture<JsonElement> future = new CompletableFuture<>();
		cancel(webId);
		boolean allCached = true;
		for (JobTask t : tasks) {
			t.cacheKey = ResultCache.key(t.op, MODEL, lang, t.system, t.user);
			JsonElement hit = ResultCache.get(t.cacheKey);
			if (hit != null) {
				t.result = hit;
				t.cached = true;
			} else {
				allCached = false;
			}
		}
		if (allCached) {
			try {
				future.complete(finisher.apply(tasks));
			} catch (RuntimeException e) {
				future.completeExceptionally(e);
			}
			return future;
		}
		if (!CopilotBridge.isAvailable()) {
			future.completeExceptionally(new AiException(Msg.t("needCopilot", lang)));
			return future;
		}
		Job job = new Job();
		job.id = JOB_ID.matcher(webId).matches() ? webId : UUID.randomUUID().toString();
		job.webId = webId;
		job.kind = kind;
		job.lang = lang;
		job.failureKey = failureKey;
		job.tasks = tasks;
		job.created = System.currentTimeMillis();
		job.future = future;
		job.finisher = finisher;
		job.progress = progress;
		synchronized (JobRegistry.class) {
			JOBS.put(job.id, job);
			QUEUE.add(job);
			ensureWatchdog();
		}
		dispatchNext();
		return future;
	}

	/* ------------------------------ cola y despacho ------------------------------ */

	private static synchronized void ensureWatchdog() {
		if (watchdog == null) {
			watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "traza-jobs");
				t.setDaemon(true);
				return t;
			});
			watchdog.scheduleWithFixedDelay(JobRegistry::tick, 5, 5, TimeUnit.SECONDS);
		}
	}

	private static String promptFor(Job job) {
		return job.lang.equals("en")
			? "Traza: view task (" + job.kind + "). Call the traza_get_job tool with jobId=\"" + job.id + "\" and repeat until it says no tasks are left."
			: "Traza: tarea de la vista (" + job.kind + "). Llama a traza_get_job con jobId=\"" + job.id + "\" y repite hasta que indique que no quedan tareas.";
	}

	private static void dispatchNext() {
		Job next;
		synchronized (JobRegistry.class) {
			if (active != null && !active.closed) {
				return;
			}
			active = null;
			next = null;
			Iterator<Job> it = QUEUE.iterator();
			while (it.hasNext()) {
				Job j = it.next();
				it.remove();
				if (!j.closed) {
					next = j;
					break;
				}
			}
			if (next == null) {
				AgentSessions.setProgressSink(null);
				return;
			}
			active = next;
			next.dispatchedAt = System.currentTimeMillis();
		}
		final Job job = next;
		AgentSessions.setProgressSink(msg -> progress(job, msg));
		CopilotBridge.showNoticeOnce();
		progress(job, Msg.t("sentToChat", job.lang));
		boolean opened = CopilotBridge.openChat(promptFor(job), true);
		if (!opened) {
			fail(job, new AiException(Msg.t("needCopilot", job.lang)), "unavailable");
		}
	}

	private static void progress(Job job, String message) {
		if (job.progress != null && !job.closed) {
			try {
				job.progress.accept(message);
			} catch (RuntimeException e) {
				TrazaPlugin.warn("No se pudo enviar el progreso de un job (" + e.getClass().getSimpleName() + ").");
			}
		}
	}

	private static void close(Job job, String reason) {
		synchronized (JobRegistry.class) {
			job.closed = true;
			JOBS.remove(job.id);
			QUEUE.remove(job);
			CLOSED.put(job.id, reason);
			while (CLOSED.size() > 100) {
				Iterator<String> it = CLOSED.keySet().iterator();
				it.next();
				it.remove();
			}
			if (active == job) {
				active = null;
			}
		}
	}

	private static void fail(Job job, RuntimeException error, String reason) {
		close(job, reason);
		job.future.completeExceptionally(error);
		dispatchNext();
	}

	private static void tick() {
		try {
			long now = System.currentTimeMillis();
			List<Job> snapshot;
			synchronized (JobRegistry.class) {
				snapshot = new ArrayList<>(JOBS.values());
			}
			for (Job job : snapshot) {
				if (job.closed) {
					continue;
				}
				if (now - job.created >= TTL_MS) {
					fail(job, new AiException(Msg.t("timeout", job.lang)), "expired");
					continue;
				}
				boolean isActive;
				synchronized (JobRegistry.class) {
					isActive = active == job;
				}
				if (isActive && !job.touched && !job.hintShown && now - job.dispatchedAt >= HINT_AFTER_MS) {
					job.hintShown = true;
					String hint = Msg.t("copilotHint", job.lang);
					progress(job, hint);
					Ui.notice("Traza", hint);
				}
			}
		} catch (RuntimeException e) {
			TrazaPlugin.error("Error en la vigilancia de jobs.", e);
		}
	}

	/* ------------------------------ cancelacion ------------------------------ */

	public static void cancel(String webId) {
		List<Job> found = new ArrayList<>();
		synchronized (JobRegistry.class) {
			for (Job j : JOBS.values()) {
				if (j.webId.equals(webId)) {
					found.add(j);
				}
			}
		}
		for (Job j : found) {
			close(j, "cancelled");
			j.future.completeExceptionally(new java.util.concurrent.CancellationException(Msg.t("cancelled", j.lang)));
		}
		if (!found.isEmpty()) {
			dispatchNext();
		}
	}

	public static void cancelAll(Collection<String> webIds) {
		for (String id : new ArrayList<>(webIds)) {
			cancel(id);
		}
	}

	/** Cancela todos los jobs (parada del plugin). */
	public static void cancelEverything() {
		List<Job> all;
		synchronized (JobRegistry.class) {
			all = new ArrayList<>(JOBS.values());
		}
		for (Job j : all) {
			close(j, "cancelled");
			j.future.completeExceptionally(new java.util.concurrent.CancellationException(Msg.t("cancelled", j.lang)));
		}
		synchronized (JobRegistry.class) {
			if (watchdog != null) {
				watchdog.shutdownNow();
				watchdog = null;
			}
		}
		AgentSessions.setProgressSink(null);
	}

	/* ------------------------------ herramientas MCP ------------------------------ */

	private static Job find(String jobId) {
		synchronized (JobRegistry.class) {
			return JOBS.get(jobId);
		}
	}

	private static String closedText(String jobId, String lang) {
		String reason;
		synchronized (JobRegistry.class) {
			reason = CLOSED.get(jobId);
		}
		if (reason == null) {
			return null;
		}
		boolean en = lang.equals("en");
		switch (reason) {
		case "done":
			return en ? "The job has already finished: the result is in the Traza view. No tasks left." : "El job ya ha terminado: el resultado está en la vista de Traza. No quedan tareas.";
		case "cancelled":
			return en ? "The job was cancelled in the Traza view. No tasks left; this result is ignored." : "El job se canceló en la vista de Traza. No quedan tareas; este resultado se ignora.";
		case "expired":
			return en ? "The job expired. No tasks left." : "El job ha caducado. No quedan tareas.";
		default:
			return en ? "The job is no longer active. No tasks left." : "El job ya no está activo. No quedan tareas.";
		}
	}

	/** traza_get_job: siguiente tarea pendiente. */
	public static ToolText getJob(String jobId) {
		Job job = find(jobId);
		if (job == null) {
			String closed = closedText(jobId, Msg.language());
			return closed != null ? new ToolText(closed, false) : new ToolText("jobId desconocido: no existe ningún job con ese identificador.", true);
		}
		job.touched = true;
		int total = job.tasks.size();
		int index = -1;
		for (int i = 0; i < total; i++) {
			if (job.tasks.get(i).result == null) {
				index = i;
				break;
			}
		}
		if (index < 0) {
			return new ToolText(job.lang.equals("en") ? "No tasks left for this job." : "No quedan tareas en este job.", false);
		}
		JobTask t = job.tasks.get(index);
		progress(job, Msg.t("copilotWorking", job.lang).replace("{i}", String.valueOf(index + 1)).replace("{n}", String.valueOf(total)));
		StringBuilder sb = new StringBuilder();
		sb.append("Tarea ").append(index + 1).append(" de ").append(total).append(", taskId=").append(t.taskId).append("\n\n");
		sb.append(t.system).append("\n\n").append(t.user).append("\n\n");
		if (t.agent) {
			sb.append("Puedes usar traza_search, traza_read y traza_list (solo lectura) para investigar el código antes de responder.\n");
		}
		sb.append("Responde llamando a traza_submit_job con jobId=\"").append(job.id).append("\", taskId=\"").append(t.taskId)
			.append("\" y result=<objeto JSON>. No muestres el JSON al usuario.");
		return new ToolText(sb.toString(), false);
	}

	/** traza_submit_job: entrega y valida el resultado de una tarea. */
	public static ToolText submitJob(String jobId, String taskId, JsonElement result) {
		Job job = find(jobId);
		if (job == null) {
			String closed = closedText(jobId, Msg.language());
			return closed != null ? new ToolText(closed, false) : new ToolText("jobId desconocido o caducado.", true);
		}
		job.touched = true;
		JobTask task = null;
		for (JobTask t : job.tasks) {
			if (t.taskId.equals(taskId)) {
				task = t;
			}
		}
		if (task == null) {
			return new ToolText("taskId desconocido para este job.", true);
		}
		if (task.result != null) {
			return new ToolText("Esta tarea ya fue entregada. " + nextText(job), false);
		}
		Validation v = Checks.run(result, task.check);
		if (!v.errors.isEmpty()) {
			task.attempts++;
			if (task.attempts >= MAX_ATTEMPTS) {
				fail(job, new AiException(Msg.t(job.failureKey, job.lang)), "failed");
				return new ToolText(Msg.t(job.failureKey, job.lang), true);
			}
			String definition = PromptBuilders.FORMAT_DEFINITION.get(task.op);
			return new ToolText(PromptBuilders.formatFeedback(definition, v.errors, task.attempts + 1, MAX_ATTEMPTS), true);
		}
		task.result = v.value;
		ResultCache.put(task.cacheKey, v.value);
		boolean done = true;
		for (JobTask t : job.tasks) {
			if (t.result == null) {
				done = false;
			}
		}
		if (done) {
			close(job, "done");
			try {
				job.future.complete(job.finisher.apply(job.tasks));
			} catch (RuntimeException e) {
				job.future.completeExceptionally(e);
			}
			dispatchNext();
			return new ToolText("Listo: el resultado ya está en la vista de Traza.", false);
		}
		return new ToolText(nextText(job), false);
	}

	private static String nextText(Job job) {
		return "Siguiente: llama a traza_get_job con jobId=\"" + job.id + "\".";
	}

}
