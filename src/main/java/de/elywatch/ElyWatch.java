package de.elywatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElyWatch implements ClientModInitializer {

    /** Wird als config/elywatch.json gespeichert. */
    static class Config {
        boolean enabled = true;
        String webhookUrl = "";
        String roleId = "";          // ID der Discord-Rolle @Ely
        int recheckMinutes = 10;     // so lange wird ein Spieler nicht erneut geprueft
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("elywatch.json");

    // Erkennt den Broadcast: "[PREMIUM] Name sucht einen 1vs1-RTP-Gegner in Normale Welt. ..."
    private static final Pattern QUEUE_PATTERN =
            Pattern.compile("([A-Za-z0-9_]{3,16}) sucht einen 1vs1-RTP-Gegner");

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
        ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
        registerCommands();
    }

    // ---------------- Chat: wer sucht einen RTP-Gegner? ----------------

    private void onGameMessage(Text message, boolean overlay) {
        if (overlay || !cfg.enabled) return;
        Matcher m = QUEUE_PATTERN.matcher(message.getString());
        if (!m.find()) return;

        String name = m.group(1);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || name.equals(client.player.getName().getString())) return;

        long now = System.currentTimeMillis();
        Long last = checked.get(name);
        if (last != null && now - last < cfg.recheckMinutes * 60_000L) return;
        checked.put(name, now);
        if (!queue.contains(name) && !name.equals(current)) queue.add(name);
    }

    // ---------------- Tick-Logik ----------------

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        tick++;
        if (!cfg.enabled) return;
        if (cooldown > 0) cooldown--;

        if (tick % 1200 == 0) { // alle 60 Sekunden alte Eintraege aufraeumen
            long now = System.currentTimeMillis();
            checked.values().removeIf(t -> now - t > cfg.recheckMinutes * 60_000L);
        }

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

    private void checkScreen(MinecraftClient client, HandledScreen<?> screen) {
        int count = 0;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory == client.player.getInventory()) continue; // eigenes Inventar ignorieren
            if (slot.getStack().isOf(Items.ELYTRA)) count += slot.getStack().getCount();
        }
        if (count > 0) {
            client.player.sendMessage(Text.literal("[ElyWatch] " + current + " hat eine Elytra!"), false);
            sendWebhook("**" + current + "** sucht einen 1vs1-RTP-Gegner und hat eine Elytra!");
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
