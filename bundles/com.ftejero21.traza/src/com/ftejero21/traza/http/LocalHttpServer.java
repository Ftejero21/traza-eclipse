package com.ftejero21.traza.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.BundleFiles;
import com.ftejero21.traza.mcp.McpConfig;
import com.ftejero21.traza.mcp.McpServer;
import com.ftejero21.traza.prefs.TrazaPreferences;
import com.ftejero21.traza.ui.IdeTheme;

/**
 * Servidor HTTP/1.1 minimo sobre ServerSocket, solo en loopback. Sirve el MCP (POST /mcp, con Bearer) y la webview
 * (GET /view/&lt;viewToken&gt;/..., solo desde el bundle) en el mismo puerto.
 */
public final class LocalHttpServer {
	private static final int MAX_BODY = 8 * 1024 * 1024;
	private static final int MAX_LINE = 8 * 1024;
	private static final int MAX_HEADERS = 100;
	private static final int READ_TIMEOUT_MS = 30_000;
	private static final int POOL_SIZE = 8;
	private static final int MAX_CONNECTIONS = 16;
	private static final String CSP = "default-src 'none'; img-src 'self' data: blob:; font-src 'self' data:; style-src 'self' 'unsafe-inline'; "
		+ "script-src 'self'; frame-src 'self' data: blob:; connect-src 'none'";

	private static ServerSocket server;
	private static ExecutorService pool;
	private static Thread acceptThread;
	private static int port;
	private static volatile boolean running;
	private static final Semaphore SLOTS = new Semaphore(MAX_CONNECTIONS);
	private static final String VIEW_TOKEN = randomHex(16);
	private static final AtomicInteger THREADS = new AtomicInteger();

	private LocalHttpServer() {
	}

	private static String randomHex(int bytes) {
		byte[] b = new byte[bytes];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	/* ------------------------------ ciclo de vida ------------------------------ */

	/** Arranque idempotente. Intenta el puerto persistido; si esta ocupado o es 0, usa uno efimero y lo persiste. */
	public static synchronized void ensureStarted() {
		if (running) {
			return;
		}
		InetAddress loopback = InetAddress.getLoopbackAddress();
		int wanted = TrazaPreferences.mcpPort();
		ServerSocket s = null;
		if (wanted > 0 && wanted < 65536) {
			try {
				s = new ServerSocket(wanted, 50, loopback);
			} catch (IOException e) {
				TrazaPlugin.warn("El puerto MCP " + wanted + " esta ocupado; se usara otro y habra que aprobar de nuevo el servidor en Copilot.");
			}
		}
		try {
			if (s == null) {
				s = new ServerSocket(0, 50, loopback);
				if (wanted > 0) {
					TrazaPlugin.warn("Puerto MCP cambiado a " + s.getLocalPort() + ".");
				}
				TrazaPreferences.setMcpPort(s.getLocalPort());
			}
		} catch (IOException e) {
			TrazaPlugin.error("No se pudo iniciar el servidor local de Traza.", e);
			return;
		}
		server = s;
		port = s.getLocalPort();
		running = true;
		pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
			Thread t = new Thread(r, "traza-http-" + THREADS.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		acceptThread = new Thread(LocalHttpServer::acceptLoop, "traza-http-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
		TrazaPlugin.info("Servidor local de Traza escuchando en 127.0.0.1:" + port);
	}

	public static synchronized void stop() {
		running = false;
		if (server != null) {
			try {
				server.close();
			} catch (IOException e) {
				TrazaPlugin.warn("No se pudo cerrar el servidor local de Traza.");
			}
			server = null;
		}
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}

	public static synchronized boolean isRunning() {
		return running;
	}

	public static synchronized int port() {
		return port;
	}

	public static String mcpUrl() {
		return "http://127.0.0.1:" + port() + "/mcp";
	}

	public static String viewBase() {
		return "http://127.0.0.1:" + port() + "/view/" + VIEW_TOKEN + "/";
	}

	/* ------------------------------ aceptacion ------------------------------ */

	private static void acceptLoop() {
		ServerSocket s;
		synchronized (LocalHttpServer.class) {
			s = server;
		}
		while (running && s != null) {
			Socket socket;
			try {
				socket = s.accept();
			} catch (IOException e) {
				if (running) {
					TrazaPlugin.warn("Error al aceptar una conexion (" + e.getClass().getSimpleName() + ").");
				}
				return;
			}
			if (!SLOTS.tryAcquire()) {
				closeQuietly(socket);
				continue;
			}
			ExecutorService p;
			synchronized (LocalHttpServer.class) {
				p = pool;
			}
			if (p == null) {
				SLOTS.release();
				closeQuietly(socket);
				return;
			}
			try {
				p.execute(() -> {
					try {
						serve(socket);
					} finally {
						SLOTS.release();
						closeQuietly(socket);
					}
				});
			} catch (RuntimeException e) {
				SLOTS.release();
				closeQuietly(socket);
			}
		}
	}

	private static void closeQuietly(Socket s) {
		try {
			s.close();
		} catch (IOException e) {
			// El socket ya estaba cerrado; no hay nada mas que liberar.
			TrazaPlugin.info("Socket ya cerrado.");
		}
	}

	/* ------------------------------ peticiones ------------------------------ */

	private static final class Request {
		String method;
		String target;
		Map<String, String> headers = new HashMap<>();
		byte[] body = new byte[0];
		boolean keepAlive = true;
	}

	private static final class Response {
		int status;
		String contentType = "text/plain; charset=utf-8";
		byte[] body = new byte[0];
		final Map<String, String> headers = new HashMap<>();
		boolean close;

		static Response of(int status, String text) {
			Response r = new Response();
			r.status = status;
			r.body = text.getBytes(StandardCharsets.UTF_8);
			return r;
		}
	}

	private static final class HttpError extends Exception {
		private static final long serialVersionUID = 1L;
		final int status;

		HttpError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static String readLine(InputStream in) throws IOException, HttpError {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int c;
		while ((c = in.read()) >= 0) {
			if (c == '\n') {
				break;
			}
			if (buf.size() >= MAX_LINE) {
				throw new HttpError(431, "Linea de cabecera demasiado larga");
			}
			buf.write(c);
		}
		if (c < 0 && buf.size() == 0) {
			return null;
		}
		String line = buf.toString(StandardCharsets.ISO_8859_1);
		return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
	}

	private static Request readRequest(InputStream in, OutputStream out) throws IOException, HttpError {
		String line = readLine(in);
		while (line != null && line.isEmpty()) {
			line = readLine(in);
		}
		if (line == null) {
			return null;
		}
		String[] parts = line.split(" ");
		if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
			throw new HttpError(400, "Peticion no valida");
		}
		Request req = new Request();
		req.method = parts[0].toUpperCase(Locale.ROOT);
		req.target = parts[1];
		if (parts[2].equals("HTTP/1.0")) {
			req.keepAlive = false;
		}
		for (int i = 0;; i++) {
			String h = readLine(in);
			if (h == null) {
				throw new HttpError(400, "Cabeceras incompletas");
			}
			if (h.isEmpty()) {
				break;
			}
			if (i >= MAX_HEADERS) {
				throw new HttpError(431, "Demasiadas cabeceras");
			}
			int colon = h.indexOf(':');
			if (colon <= 0) {
				throw new HttpError(400, "Cabecera no valida");
			}
			req.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
		}
		String connection = req.headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT);
		if (connection.contains("close")) {
			req.keepAlive = false;
		}
		if (req.headers.getOrDefault("expect", "").equalsIgnoreCase("100-continue")) {
			out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
		}
		String te = req.headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT);
		if (te.contains("chunked")) {
			req.body = readChunked(in);
		} else if (req.headers.containsKey("content-length")) {
			long len;
			try {
				len = Long.parseLong(req.headers.get("content-length"));
			} catch (NumberFormatException e) {
				throw new HttpError(400, "Content-Length no valido");
			}
			if (len < 0) {
				throw new HttpError(400, "Content-Length no valido");
			}
			if (len > MAX_BODY) {
				throw new HttpError(413, "Cuerpo demasiado grande");
			}
			req.body = in.readNBytes((int) len);
			if (req.body.length != len) {
				throw new HttpError(400, "Cuerpo incompleto");
			}
		}
		return req;
	}

	private static byte[] readChunked(InputStream in) throws IOException, HttpError {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		while (true) {
			String sizeLine = readLine(in);
			if (sizeLine == null) {
				throw new HttpError(400, "Cuerpo incompleto");
			}
			int semi = sizeLine.indexOf(';');
			String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
			long size;
			try {
				size = Long.parseLong(hex, 16);
			} catch (NumberFormatException e) {
				throw new HttpError(400, "Chunk no valido");
			}
			if (size < 0 || body.size() + size > MAX_BODY) {
				throw new HttpError(413, "Cuerpo demasiado grande");
			}
			if (size == 0) {
				String trailer = readLine(in);
				int guard = 0;
				while (trailer != null && !trailer.isEmpty() && guard++ < MAX_HEADERS) {
					trailer = readLine(in);
				}
				return body.toByteArray();
			}
			byte[] chunk = in.readNBytes((int) size);
			if (chunk.length != size) {
				throw new HttpError(400, "Cuerpo incompleto");
			}
			body.write(chunk);
			readLine(in);
		}
	}

	private static void serve(Socket socket) {
		try {
			socket.setSoTimeout(READ_TIMEOUT_MS);
			socket.setTcpNoDelay(true);
			InputStream in = new java.io.BufferedInputStream(socket.getInputStream(), 8192);
			OutputStream out = new java.io.BufferedOutputStream(socket.getOutputStream(), 8192);
			while (running) {
				Request req;
				try {
					req = readRequest(in, out);
				} catch (HttpError e) {
					Response r = Response.of(e.status, e.getMessage());
					r.close = true;
					write(out, r, false, true);
					return;
				}
				if (req == null) {
					return;
				}
				Response res;
				try {
					res = route(req);
				} catch (RuntimeException e) {
					TrazaPlugin.error("Error al atender una peticion local.", e);
					res = Response.of(500, "Error interno");
				}
				boolean keep = req.keepAlive && !res.close;
				write(out, res, req.method.equals("HEAD"), !keep);
				if (!keep) {
					return;
				}
			}
		} catch (SocketTimeoutException e) {
			// Conexion inactiva: se cierra.
			return;
		} catch (IOException e) {
			// El cliente cerro la conexion.
			return;
		}
	}

	private static String reason(int status) {
		switch (status) {
		case 200:
			return "OK";
		case 202:
			return "Accepted";
		case 400:
			return "Bad Request";
		case 401:
			return "Unauthorized";
		case 403:
			return "Forbidden";
		case 404:
			return "Not Found";
		case 405:
			return "Method Not Allowed";
		case 413:
			return "Payload Too Large";
		case 431:
			return "Request Header Fields Too Large";
		default:
			return status >= 500 ? "Internal Server Error" : "Error";
		}
	}

	private static void write(OutputStream out, Response r, boolean head, boolean close) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
		sb.append("Content-Type: ").append(r.contentType).append("\r\n");
		sb.append("Content-Length: ").append(r.body.length).append("\r\n");
		sb.append("Connection: ").append(close ? "close" : "keep-alive").append("\r\n");
		sb.append("Cache-Control: no-store\r\n");
		sb.append("X-Content-Type-Options: nosniff\r\n");
		for (Map.Entry<String, String> h : r.headers.entrySet()) {
			sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
		}
		sb.append("\r\n");
		out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
		if (!head && r.body.length > 0) {
			out.write(r.body);
		}
		out.flush();
	}

	/* ------------------------------ enrutado y seguridad ------------------------------ */

	private static boolean hostAllowed(String host) {
		if (host == null) {
			return false;
		}
		String p = String.valueOf(port());
		return host.equalsIgnoreCase("127.0.0.1:" + p) || host.equalsIgnoreCase("localhost:" + p);
	}

	private static boolean originAllowed(String origin) {
		if (origin == null) {
			return true;
		}
		String p = String.valueOf(port());
		return origin.equalsIgnoreCase("http://127.0.0.1:" + p) || origin.equalsIgnoreCase("http://localhost:" + p);
	}

	private static boolean constantEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private static Response route(Request req) {
		if (!hostAllowed(req.headers.get("host")) || !originAllowed(req.headers.get("origin"))) {
			return Response.of(403, "Prohibido");
		}
		String target = req.target;
		int q = target.indexOf('?');
		String path = q >= 0 ? target.substring(0, q) : target;
		if (path.equals("/mcp")) {
			if (!req.method.equals("POST")) {
				Response r = Response.of(405, "Metodo no permitido");
				r.headers.put("Allow", "POST");
				return r;
			}
			String auth = req.headers.get("authorization");
			String expected = "Bearer " + McpConfig.token();
			if (auth == null || !constantEquals(auth, expected)) {
				Response r = Response.of(401, "No autorizado");
				r.headers.put("WWW-Authenticate", "Bearer");
				return r;
			}
			McpServer.Result result = McpServer.handleHttp(new String(req.body, StandardCharsets.UTF_8));
			Response r = new Response();
			r.status = result.status;
			r.contentType = "application/json; charset=utf-8";
			r.body = result.body == null ? new byte[0] : result.body.getBytes(StandardCharsets.UTF_8);
			return r;
		}
		if (path.startsWith("/view/")) {
			if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
				Response r = Response.of(405, "Metodo no permitido");
				r.headers.put("Allow", "GET, HEAD");
				return r;
			}
			return serveStatic(path.substring("/view/".length()));
		}
		return Response.of(404, "No encontrado");
	}

	/* ------------------------------ estaticos de la webview ------------------------------ */

	private static String mime(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (n.endsWith(".html")) {
			return "text/html; charset=utf-8";
		}
		if (n.endsWith(".js")) {
			return "text/javascript; charset=utf-8";
		}
		if (n.endsWith(".css")) {
			return "text/css; charset=utf-8";
		}
		if (n.endsWith(".json") || n.endsWith(".map")) {
			return "application/json; charset=utf-8";
		}
		if (n.endsWith(".svg")) {
			return "image/svg+xml";
		}
		if (n.endsWith(".png")) {
			return "image/png";
		}
		if (n.endsWith(".ico")) {
			return "image/x-icon";
		}
		if (n.endsWith(".woff2")) {
			return "font/woff2";
		}
		if (n.endsWith(".woff")) {
			return "font/woff";
		}
		if (n.endsWith(".txt")) {
			return "text/plain; charset=utf-8";
		}
		return "application/octet-stream";
	}

	/** Ruta normalizada (sin .., sin barras invertidas, sin rutas absolutas) o null si no es segura. */
	private static String safePath(String raw) {
		String decoded;
		try {
			decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (decoded.indexOf('\\') >= 0 || decoded.indexOf('\0') >= 0 || decoded.startsWith("/")) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (String segment : decoded.split("/", -1)) {
			if (segment.equals("..") || segment.equals(".") || segment.indexOf(':') >= 0) {
				return null;
			}
			if (segment.isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				out.append('/');
			}
			out.append(segment);
		}
		return out.toString();
	}

	private static Response serveStatic(String rest) {
		int slash = rest.indexOf('/');
		String token = slash < 0 ? rest : rest.substring(0, slash);
		String file = slash < 0 ? "" : rest.substring(slash + 1);
		if (!constantEquals(token, VIEW_TOKEN)) {
			return Response.of(404, "No encontrado");
		}
		String path = safePath(file);
		if (path == null) {
			return Response.of(404, "No encontrado");
		}
		if (path.isEmpty() || file.endsWith("/")) {
			path = path.isEmpty() ? "index.html" : path + "/index.html";
		}
		String entry = path.equals("traza-shim.js") ? "webview-host/traza-shim.js" : "webview/" + path;
		byte[] data;
		try {
			data = BundleFiles.readBytes(entry);
		} catch (IOException e) {
			return Response.of(404, "No encontrado");
		}
		Response r = new Response();
		r.status = 200;
		r.contentType = mime(path);
		if (path.equals("index.html")) {
			r.body = rewriteIndex(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
			r.headers.put("Content-Security-Policy", CSP);
		} else {
			r.body = data;
		}
		return r;
	}

	/** base href de la vista, shim como primer hijo del head y variables --vscode-* con theme=ide. */
	private static String rewriteIndex(String html) {
		String out = html.replaceFirst("<base href=\"[^\"]*\"\\s*/?>", java.util.regex.Matcher.quoteReplacement("<base href=\"/view/" + VIEW_TOKEN + "/\">"));
		StringBuilder head = new StringBuilder("\n  <script src=\"traza-shim.js\"></script>");
		if ("ide".equals(TrazaPreferences.themePref())) {
			IdeTheme theme = IdeTheme.current();
			head.append("\n  <style id=\"traza-ide-vars\">").append(theme.css.replace("</", "<\\/")).append("</style>");
		}
		return out.replaceFirst("(?i)<head>", java.util.regex.Matcher.quoteReplacement("<head>" + head));
	}
}
