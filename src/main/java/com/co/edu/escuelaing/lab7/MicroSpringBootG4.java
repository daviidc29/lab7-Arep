package com.co.edu.escuelaing.lab7;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MicroSpringBootG4 {

    private static final int DEFAULT_PORT = 35000;
    private static final String STATIC_ROOT = "public";
    private static final int MIN_WORKER_THREADS = 4;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final Map<String, RouteHandler> ROUTES = new HashMap<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    private static volatile ServerSocket serverSocket;
    private static volatile ExecutorService requestExecutor;

    public static void main(String[] args) throws Exception {
        clearRoutes();
        loadControllers(args);
        startServer(resolvePort(args));
    }

    private static void clearRoutes() {
        ROUTES.clear();
    }

    private static void loadControllers(String[] args) throws Exception {
        Set<Class<?>> controllers = new LinkedHashSet<>();

        if (args.length > 0 && isClassName(args[0])) {
            controllers.add(Class.forName(args[0]));
        } else {
            controllers.addAll(findControllersInClasspath());
        }

        for (Class<?> controllerClass : controllers) {
            registerController(controllerClass);
        }

        if (ROUTES.isEmpty()) {
            throw new IllegalStateException("No se encontraron controladores con @RestController y @GetMapping");
        }
    }

    private static int resolvePort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }

        int portArgumentIndex = isClassName(args[0]) ? 1 : 0;
        if (args.length > portArgumentIndex) {
            return Integer.parseInt(args[portArgumentIndex]);
        }
        return DEFAULT_PORT;
    }

    private static boolean isClassName(String value) {
        return value != null && value.contains(".") && !value.matches("\\d+");
    }

    private static void registerController(Class<?> controllerClass) throws Exception {
        if (!controllerClass.isAnnotationPresent(RestController.class)) {
            return;
        }

        Object controllerInstance = createInstance(controllerClass);

        for (Method method : controllerClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(GetMapping.class)) {
                continue;
            }

            if (!String.class.equals(method.getReturnType())) {
                throw new IllegalStateException("El método " + method.getName() + " debe retornar String");
            }

            String route = method.getAnnotation(GetMapping.class).value();
            method.setAccessible(true);
            ROUTES.put(route, new RouteHandler(controllerInstance, method));
        }
    }

    private static Object createInstance(Class<?> controllerClass) throws Exception {
        Constructor<?> constructor = controllerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static List<Class<?>> findControllersInClasspath() {
        List<Class<?>> classes = new ArrayList<>();
        String[] classpathEntries = System.getProperty("java.class.path").split(System.getProperty("path.separator"));

        for (String entry : classpathEntries) {
            Path root;
            try {
                root = Paths.get(entry);
            } catch (Exception e) {
                continue; 
            }

            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walk(root)
                        .filter(path -> path.toString().endsWith(".class"))
                        .filter(path -> !path.getFileName().toString().contains("$"))
                        .forEach(path -> loadIfControllerCandidate(root, path, classes));
            } catch (IOException ignored) {
            }
        }
        return classes;
    }

    private static void loadIfControllerCandidate(Path root, Path classFile, List<Class<?>> classes) {
        String className = root.relativize(classFile)
                .toString()
                .replace('/', '.')
                .replace('\\', '.')
                .replaceAll("\\.class$", "");

        try {
            Class<?> clazz = Class.forName(className);
            if (clazz.isAnnotationPresent(RestController.class)) {
                classes.add(clazz);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void startServer(int port) throws IOException {
        registerShutdownHook();
        requestExecutor = Executors.newFixedThreadPool(resolveWorkerThreads());
        RUNNING.set(true);

        try (ServerSocket localServerSocket = new ServerSocket(port)) {
            serverSocket = localServerSocket;
            localServerSocket.setReuseAddress(true);
            System.out.println("MicroSpringBootG4 escuchando en http://localhost:" + port);
            System.out.println("Pool de hilos activo con " + resolveWorkerThreads() + " trabajadores.");
            System.out.println("Use Ctrl+C para apagar el servidor de manera elegante.");

            while (RUNNING.get()) {
                try {
                    Socket clientSocket = localServerSocket.accept();
                    submitClient(clientSocket);
                } catch (SocketException e) {
                    if (RUNNING.get()) {
                        System.err.println("Error aceptando conexión: " + e.getMessage());
                    }
                    break;
                }
            }
        } finally {
            RUNNING.set(false);
            closeServerSocket();
            awaitExecutorShutdown();
            serverSocket = null;
            requestExecutor = null;
            System.out.println("Servidor detenido.");
        }
    }

    public static void stop() {
        if (!RUNNING.compareAndSet(true, false)) {
            return;
        }

        System.out.println("Iniciando apagado elegante del servidor...");
        closeServerSocket();
        if (requestExecutor != null) {
            requestExecutor.shutdown();
        }
    }

    private static void registerShutdownHook() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(MicroSpringBootG4::stop, "micro-spring-shutdown-hook"));
    }

    private static int resolveWorkerThreads() {
        return Math.max(MIN_WORKER_THREADS, Runtime.getRuntime().availableProcessors() * 2);
    }

    private static void submitClient(Socket clientSocket) {
        try {
            requestExecutor.submit(() -> processClient(clientSocket));
        } catch (RejectedExecutionException e) {
            closeQuietly(clientSocket);
            if (RUNNING.get()) {
                System.err.println("No fue posible encolar la solicitud: " + e.getMessage());
            }
        }
    }

    private static void processClient(Socket clientSocket) {
        try (Socket managedSocket = clientSocket) {
            handleClient(managedSocket);
        } catch (Exception e) {
            if (RUNNING.get()) {
                System.err.println("Error atendiendo solicitud: " + e.getMessage());
            }
        }
    }

    private static void awaitExecutorShutdown() {
        if (requestExecutor == null) {
            return;
        }

        requestExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                requestExecutor.shutdownNow();
                if (!requestExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    System.err.println("No fue posible detener todos los hilos dentro del tiempo esperado.");
                }
            }
        } catch (InterruptedException e) {
            requestExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void closeServerSocket() {
        ServerSocket localServerSocket = serverSocket;
        if (localServerSocket == null || localServerSocket.isClosed()) {
            return;
        }

        try {
            localServerSocket.close();
        } catch (IOException e) {
            System.err.println("Error cerrando el socket del servidor: " + e.getMessage());
        }
    }

    private static void closeQuietly(Socket clientSocket) {
        if (clientSocket == null || clientSocket.isClosed()) {
            return;
        }

        try {
            clientSocket.close();
        } catch (IOException ignored) {
        }
    }

    private static void handleClient(Socket clientSocket) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());

        String requestLine = in.readLine();
        if (requestLine == null || requestLine.isBlank()) {
            return;
        }

        while (true) {
            String headerLine = in.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                break;
            }
        }

        String[] requestParts = requestLine.split(" ");
        if (requestParts.length < 2) {
            writeTextResponse(out, 400, "Bad Request", "Solicitud HTTP inválida");
            return;
        }

        String httpMethod = requestParts[0];
        String rawUri = requestParts[1];

        if (!"GET".equals(httpMethod)) {
            writeTextResponse(out, 405, "Method Not Allowed", "Solo se soporta GET");
            return;
        }

        URI uri = URI.create(rawUri);
        String path = uri.getPath();
        Map<String, String> queryParams = parseQueryParams(uri.getRawQuery());

        RouteHandler handler = ROUTES.get(path);
        if (handler != null) {
            String responseBody = handler.invoke(queryParams);
            writeTextResponse(out, 200, "OK", responseBody);
            return;
        }

        if (serveStaticResource(path, out)) {
            return;
        }

        writeTextResponse(out, 404, "Not Found", "Recurso no encontrado: " + path);
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }

            String[] keyValue = pair.split("=", 2);
            String key = urlDecode(keyValue[0]);
            String value = keyValue.length > 1 ? urlDecode(keyValue[1]) : "";
            params.put(key, value);
        }
        return params;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean serveStaticResource(String path, BufferedOutputStream out) throws IOException {
        String normalizedPath = normalizeStaticPath(path);
        byte[] resourceBytes = loadStaticResource(normalizedPath);
        if (resourceBytes == null) {
            return false;
        }

        String contentType = normalizedPath.endsWith(".png") ? "image/png" : "text/html; charset=UTF-8";
        writeBinaryResponse(out, 200, "OK", contentType, resourceBytes);
        return true;
    }

    private static String normalizeStaticPath(String path) {
        if (path == null || "/".equals(path) || path.isBlank()) {
            return "/index.html";
        }
        return path;
    }

    private static byte[] loadStaticResource(String path) throws IOException {
        String resourcePath = STATIC_ROOT + path;

        try (InputStream resourceStream = MicroSpringBootG4.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (resourceStream != null) {
                return readAllBytes(resourceStream);
            }
        }

        Path filePath = Paths.get("src/main/resources", resourcePath);
        if (Files.exists(filePath)) {
            return Files.readAllBytes(filePath);
        }

        return null;
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(data)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    private static void writeTextResponse(BufferedOutputStream out, int statusCode, String statusText, String body)
            throws IOException {
        writeBinaryResponse(out, statusCode, statusText, "text/html; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBinaryResponse(BufferedOutputStream out, int statusCode, String statusText,
            String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static class RouteHandler {
        private final Object controller;
        private final Method method;

        private RouteHandler(Object controller, Method method) {
            this.controller = controller;
            this.method = method;
        }

        private String invoke(Map<String, String> queryParams)
                throws InvocationTargetException, IllegalAccessException {
            Object[] args = buildArguments(queryParams);
            Object result = method.invoke(controller, args);
            return result == null ? "" : result.toString();
        }

        private Object[] buildArguments(Map<String, String> queryParams) {
            Parameter[] parameters = method.getParameters();
            Object[] args = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);

                if (requestParam == null) {
                    throw new IllegalStateException(
                            "Todos los parámetros deben usar @RequestParam en el método " + method.getName());
                }

                if (!String.class.equals(parameter.getType())) {
                    throw new IllegalStateException(
                            "Solo se soportan parámetros String con @RequestParam en el método " + method.getName());
                }

                String value = queryParams.get(requestParam.value());
                if (value == null || value.isBlank()) {
                    value = requestParam.defaultValue();
                }
                args[i] = value;
            }
            return args;
        }
    }
}
