package com.ftejero21.traza.mcp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ftejero21.traza.core.EditorHint;
import com.ftejero21.traza.core.FlowStore.GeneratedFlow;
import com.google.gson.JsonObject;

/** Tareas FLOW/PROPOSE abiertas por traza_flow/traza_change y peticiones pendientes de los comandos y enlaces. Thread-safe. */
public final class TaskRegistry {
	private static final int MAX_TASKS = 20;
	private static final long TTL_MS = 30 * 60_000L;

	/** Tarea de entrega: Copilot la resuelve con traza_submit_flow o traza_submit_change. */
	public static final class Task {
		public String id;
		public String kind;
		/** Pregunta enmascarada. */
		public String question;
		public String originalQuestion;
		public String lang;
		public boolean scenarioAsked;
		/** Modo con el que abrir la vista (enlaces traza://). */
		public String mode;
		public String folder;
		/** Peticion de cambio que se resuelve tras generar el flujo (tarea FLOW). */
		public String followUpChange;
		/** PROPOSE: flujo sobre el que se propone y su titulo. */
		public JsonObject flow;
		public String flowTitle;
		/** PROPOSE: flujo recien generado (se muestra aunque la propuesta falle). */
		public GeneratedFlow generated;
		public boolean multi;
		public java.util.Set<String> mountIds = new java.util.HashSet<>();
		public int attempts;
		public long expires;
	}

	/** Peticion registrada por un comando o enlace: pista del editor activo, modo y followUp. */
	public static final class PendingRequest {
		public String requestId;
		public String kind;
		public EditorHint hint;
		public String mode;
		public String followUpChange;
		public String folder;
		public long expires;
	}

	private static final Map<String, Task> TASKS = new LinkedHashMap<>();
	private static final Map<String, PendingRequest> PENDING = new LinkedHashMap<>();

	private TaskRegistry() {
	}

	private static void sweep(long now) {
		TASKS.values().removeIf(t -> t.expires <= now);
		PENDING.values().removeIf(p -> p.expires <= now);
	}

	public static synchronized Task add(Task task) {
		long now = System.currentTimeMillis();
		sweep(now);
		task.id = UUID.randomUUID().toString();
		task.expires = now + TTL_MS;
		TASKS.put(task.id, task);
		while (TASKS.size() > MAX_TASKS) {
			Iterator<String> it = TASKS.keySet().iterator();
			it.next();
			it.remove();
		}
		return task;
	}

	/** Tarea vigente (la marca como usada recientemente). */
	public static synchronized Task get(String id) {
		long now = System.currentTimeMillis();
		sweep(now);
		Task t = TASKS.remove(id);
		if (t != null) {
			TASKS.put(id, t);
		}
		return t;
	}

	public static synchronized void remove(String id) {
		TASKS.remove(id);
	}

	public static synchronized PendingRequest addPending(PendingRequest p) {
		long now = System.currentTimeMillis();
		sweep(now);
		p.requestId = UUID.randomUUID().toString();
		p.expires = now + TTL_MS;
		PENDING.put(p.requestId, p);
		while (PENDING.size() > MAX_TASKS) {
			Iterator<String> it = PENDING.keySet().iterator();
			it.next();
			it.remove();
		}
		return p;
	}

	public static synchronized PendingRequest pending(String requestId) {
		sweep(System.currentTimeMillis());
		return requestId == null ? null : PENDING.get(requestId);
	}
}
