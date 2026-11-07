package vn.nightmare.tcyt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public final class NightmareLink extends JavaPlugin implements Listener {
    private HttpClient http;
    private String baseUrl, pluginKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        baseUrl = getConfig().getString("api-base-url", "").replaceAll("/+$", "");
        pluginKey = getConfig().getString("plugin-key", "");
        if (!baseUrl.startsWith("https://") || pluginKey.length() < 32 || pluginKey.startsWith("REPLACE_")) {
            getLogger().severe("Configure HTTPS api-base-url and strong plugin-key.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null ||
            Bukkit.getPluginManager().getPlugin("PlayerPoints") == null ||
            Bukkit.getPluginManager().getPlugin("LuckPerms") == null ||
            Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("AuthMe, PlayerPoints, LuckPerms and Vault must be installed.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        Bukkit.getPluginManager().registerEvents(this, this);
        int syncSeconds = Math.max(30, getConfig().getInt("sync-seconds", 60));
        Bukkit.getScheduler().runTaskTimer(this, this::syncOnlinePlayers, 60L, syncSeconds * 20L);
        getLogger().info("Enabled. Sync sources: LuckPerms rank, PlayerPoints xu, Vault/EssentialsX balance.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in game."); return true; }
        if (args.length != 1 || !args[0].equalsIgnoreCase("sync")) {
            player.sendMessage("§cDùng: §f/nmtcyt sync"); return true;
        }
        if (!authMeLoggedIn(player)) {
            player.sendMessage("§cBạn phải đăng nhập AuthMe thành công trước."); return true;
        }
        player.sendMessage("§7Đang đồng bộ dữ liệu game...");
        syncOnePlayer(player);
        return true;
    }

    private boolean authMeLoggedIn(Player player) {
        try {
            Class<?> cls = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = cls.getMethod("getInstance").invoke(null);
            return api != null && Boolean.TRUE.equals(cls.getMethod("isAuthenticated", Player.class).invoke(api, player));
        } catch (Exception e) { getLogger().warning("AuthMe API check failed."); return false; }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) syncOnePlayer(event.getPlayer());
        }, 40L);
    }

    // Snapshot is read from the live server APIs, never parsed from chat/RCON output.
    private void syncOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                UUID uuid = player.getUniqueId();
                String rank = readLuckPermsPrimaryGroup(uuid);
                Number points = readPlayerPoints(uuid);
                Number balance = readVaultBalance(player);
                if (rank == null || points == null || balance == null) {
                    getLogger().fine("Skipping incomplete snapshot for " + player.getName());
                    continue;
                }
                JsonObject body = new JsonObject();
                body.addProperty("uuid", uuid.toString());
                body.addProperty("username", player.getName());
                body.addProperty("rank", rank);
                body.addProperty("points", points.longValue());
                body.addProperty("balance", balance.doubleValue());
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try { post("/internal/sync/player", body); }
                    catch (Exception e) { getLogger().warning("Player sync failed for " + player.getName() + ": " + e.getClass().getSimpleName()); }
                });
            } catch (Exception e) {
                getLogger().warning("Could not read game data for " + player.getName() + ": " + e.getClass().getSimpleName());
            }
        }
    }

    private String readLuckPermsPrimaryGroup(UUID uuid) throws Exception {
        Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
        Object luckPerms = provider.getMethod("get").invoke(null);
        Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
        Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
        if (user == null) return null; // Wait for LuckPerms cache; never guess a rank.
        Object group = Class.forName("net.luckperms.api.model.user.User").getMethod("getPrimaryGroup").invoke(user);
        return group instanceof String ? (String) group : null;
    }

    private Number readPlayerPoints(UUID uuid) throws Exception {
        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (plugin == null || !plugin.isEnabled()) return null;
        Object api = plugin.getClass().getMethod("getAPI").invoke(plugin);
        Object value = api.getClass().getMethod("look", UUID.class).invoke(api, uuid);
        return value instanceof Number ? (Number) value : null;
    }

    private Number readVaultBalance(Player player) throws Exception {
        Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
        Object registration = Bukkit.getServicesManager().getRegistration(economyClass);
        if (registration == null) return null;
        Object economy = registration.getClass().getMethod("getProvider").invoke(registration);
        Object value = economyClass.getMethod("getBalance", org.bukkit.OfflinePlayer.class).invoke(economy, player);
        return value instanceof Number ? (Number) value : null;
    }

    private void syncOnePlayer(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            String rank = readLuckPermsPrimaryGroup(uuid);
            Number points = readPlayerPoints(uuid);
            Number balance = readVaultBalance(player);
            if (rank == null || points == null || balance == null) {
                player.sendMessage("§cChưa đọc đủ dữ liệu LuckPerms/PlayerPoints/Vault."); return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("uuid", uuid.toString());
            body.addProperty("username", player.getName());
            body.addProperty("rank", rank);
            body.addProperty("points", points.longValue());
            body.addProperty("balance", balance.doubleValue());
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try { post("/internal/sync/player", body); send(player, "§aĐã đồng bộ hồ sơ lên website."); }
                catch (Exception e) { send(player, "§cĐồng bộ thất bại. Hãy kiểm tra cấu hình plugin-key/backend."); }
            });
        } catch (Exception e) { player.sendMessage("§cKhông đọc được dữ liệu game. Kiểm tra plugin phụ thuộc."); }
    }

    private JsonObject post(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json")
            .header("X-Plugin-Key", pluginKey)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) throw new IOException("HTTP " + res.statusCode());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private void send(Player player, String msg) {
        Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) player.sendMessage(msg); });
    }

  }
            
