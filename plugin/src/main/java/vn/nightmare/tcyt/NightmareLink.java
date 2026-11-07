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
        int pollSeconds = Math.max(15, getConfig().getInt("command-poll-seconds", 20));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::claimWebsiteCommand, 100L, pollSeconds * 20L);
        getLogger().info("Enabled. Snapshot sync and website credit queue are active.");
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

    // Claim is durable on the backend before any balance mutation. A command left in
    // processing after a crash is deliberately not replayed automatically.
    private void claimWebsiteCommand() {
        try {
            JsonObject response = post("/internal/commands/claim", new JsonObject());
            if (!response.has("command") || response.get("command").isJsonNull()) return;
            JsonObject command = response.getAsJsonObject("command");
            Bukkit.getScheduler().runTask(this, () -> processWebsiteCommand(command));
        } catch (Exception e) {
            getLogger().warning("Website command poll failed: " + e.getClass().getSimpleName());
        }
    }

    private void processWebsiteCommand(JsonObject command) {
        String id = command.get("id").getAsString();
        String username = command.get("username").getAsString();
        String type = command.get("type").getAsString();
        long amount = command.get("amount").getAsLong();
        boolean success = false;
        String message;
        try {
            if (amount < 1 || amount > 1_000_000L) throw new IllegalArgumentException("Số lượng vượt giới hạn.");
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(username);
            if ("points".equals(type)) {
                org.bukkit.plugin.Plugin pointsPlugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
                if (pointsPlugin == null || !pointsPlugin.isEnabled()) throw new IllegalStateException("PlayerPoints chưa hoạt động.");
                Object api = pointsPlugin.getClass().getMethod("getAPI").invoke(pointsPlugin);
                Object result = api.getClass().getMethod("give", UUID.class, int.class)
                    .invoke(api, target.getUniqueId(), (int) amount);
                if (result instanceof Boolean && !((Boolean) result)) throw new IllegalStateException("PlayerPoints từ chối cộng xu.");
            } else if ("money".equals(type)) {
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                Object registration = Bukkit.getServicesManager().getRegistration(economyClass);
                if (registration == null) throw new IllegalStateException("Không tìm thấy Vault Economy.");
                Object economy = registration.getClass().getMethod("getProvider").invoke(registration);
                Object result = economyClass.getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class)
                    .invoke(economy, target, (double) amount);
                Object ok = result.getClass().getMethod("transactionSuccess").invoke(result);
                if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("Vault từ chối cộng tiền.");
            } else throw new IllegalArgumentException("Loại giao dịch không hợp lệ.");
            success = true;
            message = "Đã cộng " + amount + ("points".equals(type) ? " xu" : " tiền") + " cho " + username + ".";
        } catch (Exception e) {
            message = e.getCause() != null && e.getCause().getMessage() != null
                ? e.getCause().getMessage() : (e.getMessage() == null ? "Không xử lý được giao dịch." : e.getMessage());
            getLogger().warning("Website command " + id + " failed: " + e.getClass().getSimpleName());
        }
        final boolean completed = success;
        final String resultMessage = message;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                JsonObject ack = new JsonObject();
                ack.addProperty("success", completed);
                ack.addProperty("message", resultMessage);
                post("/internal/commands/" + id + "/complete", ack);
            } catch (Exception e) {
                getLogger().severe("Could not acknowledge website command " + id + ". It remains processing for manual review.");
            }
        });
        if (success) Bukkit.broadcastMessage("§8[§cNIGHTMARE§8] §aĐã xử lý yêu cầu website cho §e" + username + "§a.");
    }

    private void send(Player player, String msg) {
        Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) player.sendMessage(msg); });
    }

  }
            
