package com.ftejero21.traza.ai;

/** Error con mensaje apto para el usuario (nunca contiene codigo ni prompts). */
public final class AiException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public AiException(String message) {
		super(message);
	}
}
