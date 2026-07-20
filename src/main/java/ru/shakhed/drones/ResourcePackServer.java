package ru.shakhed.drones;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executors;

public final class ResourcePackServer {
    private final ShakhedDronesPlugin plugin;
    private HttpServer server;
    private byte[] bytes;
    private byte[] sha1;
    private String publicUrl;

    public ResourcePackServer(ShakhedDronesPlugin plugin) { this.plugin = plugin; }

    public void start() {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) return;
        try (InputStream input = plugin.getResource("shakheddrones-resourcepack.zip")) {
            if (input == null) throw new IOException("resource pack is not embedded in the plugin JAR");
            File output = new File(plugin.getDataFolder(), "ShakhedDrones-resourcepack.zip");
            output.getParentFile().mkdirs();
            Files.copy(input, output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            bytes = Files.readAllBytes(output.toPath());
            sha1 = MessageDigest.getInstance("SHA-1").digest(bytes);
            int port = plugin.getConfig().getInt("resource-pack.http-port", 8123);
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/shakheddrones-resourcepack.zip", this::serve);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            publicUrl = plugin.getConfig().getString("resource-pack.public-url", "").trim();
            if (publicUrl.isEmpty()) publicUrl = "http://127.0.0.1:" + port + "/shakheddrones-resourcepack.zip";
            plugin.getLogger().info("Resource pack server: " + publicUrl);
        } catch (IOException | NoSuchAlgorithmException exception) {
            plugin.getLogger().severe("Не удалось запустить раздачу ресурспака: " + exception.getMessage());
        }
    }

    public void stop() { if (server != null) server.stop(0); }

    public void send(Player player) {
        if (publicUrl == null || sha1 == null) return;
        player.setResourcePack(publicUrl, sha1, "3D-модели ShakhedDrones v0.2",
                plugin.getConfig().getBoolean("resource-pack.required", true));
    }

    private void serve(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
