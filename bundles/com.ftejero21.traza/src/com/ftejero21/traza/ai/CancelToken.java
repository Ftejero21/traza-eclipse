package com.ftejero21.traza.ai;

/** Token de cancelacion cooperativo (equivale al CancellationToken de VS Code). */
public final class CancelToken {
	/** Excepcion de cancelacion (mensaje "cancelled", como el Error('cancelled') original). */
	public static final class CancelledException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public CancelledException() {
			super("cancelled");
		}
	}

	public static final CancelToken NONE = new CancelToken();
	private volatile boolean cancelled;

	public void cancel() {
		cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void check() {
		if (cancelled) {
			throw new CancelledException();
		}
	}
}
