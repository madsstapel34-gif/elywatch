package de.elywatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ElyWatch implements ClientModInitializer {

    /** Wird als config/elywatch.json gespeichert. */
    static class Config {
        boolean enabled = true;
        String webhookUrl = "";
        String roleId = "";          // ID der Discord-Rolle @Ely
        boolean areaSet = false;     // wird mit /elywatch area <radius> gesetzt
        double minX, maxX, minY = -64, maxY = 320, minZ, maxZ;
        int recheckMinutes = 10;     // so lange wird ein Spieler nicht erneut geprueft
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("elywatch.json");

    private Config cfg = new Config();

    private final Deque<String> queue = new ArrayDeque<>();
    private final Map<String, Long> checked = new HashMap<>();
    private String current = null;   // Spieler, dessen Inventar gerade geprueft wird
    private int waitTicks = 0;
    private int cooldown = 0;
    private int tick = 0;

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        registerCommands();
    }

    // ---------------- Tick-Logik ----------------

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        tick++;
        if (!cfg.enabled || !cfg.areaSet) return;
        if (cooldown > 0) cooldown--;

        if (tick % 20 == 0) scan(client);

        if (current == null) {
            if (cooldown == 0 && !queue.isEmpty()) {
                current = queue.poll();
                waitTicks = 0;
                client.player.networkHandler.sendChatCommand("invsee " + current);
            }
            return;
        }

        waitTicks++;
        if (client.currentScreen instanceof HandledScreen<?> screen && waitTicks >= 8) {
            checkScreen(client, screen);
            finish(client);
        } else if (waitTicks > 80) { // /invsee hat nichts geoeffnet (keine Rechte, Spieler offline, ...)
            finish(client);
        }
    }

    private void finish(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }
        current = null;
        cooldown = 30; // 1,5 Sekunden Pause zwischen zwei /invsee
    }

    private void scan(MinecraftClient client) {
        long now = System.currentTimeMillis();
        String self = client.player.getName().getString();
        Set<String> present = new HashSet<>();
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            String name = p.getName().getString();
            if (name.equals(self) || !inArea(p.getX(), p.getY(), p.getZ())) continue;
            present.add(name);
            Long last = checked.get(name);
            if (last == null || now - last > cfg.recheckMinutes * 60_000L) {
                checked.put(name, now);
                if (!queue.contains(name) && !name.equals(current)) queue.add(name);
            }
        }
        // Spieler, die den Bereich verlassen haben, werden vergessen
        checked.keySet().removeIf(n -> !present.contains(n));
    }

    private boolean inArea(double x, double y, double z) {
        return x >= cfg.minX && x <= cfg.maxX && y >= cfg.minY && y <= cfg.maxY && z >= cfg.minZ && z <= cfg.maxZ;
    }

    private void checkScreen(MinecraftClient client, HandledScreen<?> screen) {
        int count = 0;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory == client.player.getInventory()) continue; // eigenes Inventar ignorieren
            if (slot.getStack().isOf(Items.ELYTRA)) count += slot.getStack().getCount();
        }
        if (count > 0) {
            String pos = "";
            for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                if (p.getName().getString().equals(current)) {
                    pos = String.format(" (%d, %d, %d)", (int) p.getX(), (int) p.getY(), (int) p.getZ());
                }
            }
            client.player.sendMessage(Text.literal("[ElyWatch] " + current + " hat eine Elytra!"), false);
            sendWebhook("**" + current + "** hat eine Elytra in der RTP-Queue!" + pos);
        }
    }

    // ---------------- Discord ----------------

    private void sendWebhook(String text) {
        if (cfg.webhookUrl == null || cfg.webhookUrl.isBlank()) return;
        boolean ping = cfg.roleId != null && !cfg.roleId.isBlank();

        JsonObject body = new JsonObject();
        body.addProperty("content", (ping ? "<@&" + cfg.roleId + "> " : "") + text);
        JsonObject allowed = new JsonObject();
        JsonArray roles = new JsonArray();
        if (ping) roles.add(cfg.roleId);
        allowed.add("roles", roles);
        body.add("allowed_mentions", allowed);

        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.webhookUrl))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body.toString()))
                .build();
        HTTP.sendAsync(req, BodyHandlers.discarding()).exceptionally(e -> {
            System.err.println("[ElyWatch] Webhook-Fehler: " + e.getMessage());
            return null;
        });
    }

    // ---------------- Befehle ----------------

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("elywatch")
                .then(ClientCommandManager.literal("on").executes(c -> {
                    cfg.enabled = true; saveConfig();
                    c.getSource().sendFeedback(Text.literal("[ElyWatch] an"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(c -> {
                    cfg.enabled = false; saveConfig();
                    c.getSource().sendFeedback(Text.literal("[ElyWatch] aus"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("area")
                    .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, 500)).executes(c -> {
                        int r = IntegerArgumentType.getInteger(c, "radius");
                        var p = c.getSource().getPlayer();
                        cfg.minX = p.getX() - r; cfg.maxX = p.getX() + r;
                        cfg.minZ = p.getZ() - r; cfg.maxZ = p.getZ() + r;
                        cfg.minY = -64; cfg.maxY = 320;
                        cfg.areaSet = true;
                        saveConfig();
                        c.getSource().sendFeedback(Text.literal("[ElyWatch] Queue-Bereich gesetzt: Radius " + r + " um deine Position"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("test").executes(c -> {
                    sendWebhook("Test von ElyWatch - der Ping funktioniert!");
                    c.getSource().sendFeedback(Text.literal("[ElyWatch] Test gesendet"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(c -> {
                    c.getSource().sendFeedback(Text.literal(String.format(
                        "[ElyWatch] %s | Bereich %s | Webhook %s | Rolle %s",
                        cfg.enabled ? "an" : "aus",
                        cfg.areaSet ? "gesetzt" : "NICHT gesetzt (/elywatch area <radius>)",
                        cfg.webhookUrl.isBlank() ? "FEHLT" : "ok",
                        cfg.roleId.isBlank() ? "FEHLT" : "ok")));
                    return 1;
                }))
        ));
    }

    // ---------------- Config ----------------

    private void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Config loaded = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (loaded != null) cfg = loaded;
            }
        } catch (Exception e) {
            System.err.println("[ElyWatch] Config konnte nicht geladen werden: " + e.getMessage());
        }
        saveConfig(); // schreibt die Datei mit allen Feldern, falls sie noch nicht existiert
    }

    private void saveConfig() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
        } catch (Exception e) {
            System.err.println("[ElyWatch] Config konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
