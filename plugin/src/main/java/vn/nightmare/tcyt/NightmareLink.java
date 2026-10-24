package vn.nightmare.tcyt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NightmareLink extends JavaPlugin {
    private HttpClient http;
    private String baseUrl, pluginKey;
    private Properties ledger;
    private Path ledgerPath;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override public void onEnable() {
        saveDefaultConfig();
        baseUrl = getConfig().getString("api-base-url", "").replaceAll("/+$", "");
        pluginKey = getConfig().getString("plugin-key", "");
        if (!baseUrl.startsWith("https://") || pluginKey.length() < 32 || pluginKey.startsWith("REPLACE_")) {
            getLogger().severe("Configure HTTPS api-base-url and strong plugin-key.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null ||
            Bukkit.getPluginManager().getPlugin("PlayerPoints") == null) {
            getLogger().severe("AuthMe and PlayerPoints must be installed.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        ledgerPath = getDataFolder().toPath().resolve("delivery-ledger.properties");
        ledger = new Properties();
        try {
            Files.createDirectories(getDataFolder().toPath());
            if (Files.exists(ledgerPath)) try (var in = Files.newInputStream(ledgerPath)) { ledger.load(in); }
        } catch (IOException e) {
            getLogger().severe("Cannot open delivery ledger; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        int seconds = Math.max(10, getConfig().getInt("poll-seconds", 10));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::poll, 40L, seconds * 20L);
        getLogger().info("Enabled. Use /nmtcyt link (alias /web link).");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use this command in game."); return true; }
        if (args.length != 1 || !args[0].equalsIgnoreCase("link")) {
            player.sendMessage("§cDùng: §f/nmtcyt link"); return true;
        }
        if (!authMeLoggedIn(player)) {
            player.sendMessage("§cBạn phải đăng nhập AuthMe thành công trước."); return true;
        }
        JsonObject body = new JsonObject();
        body.addProperty("uuid", player.getUniqueId().toString());
        body.addProperty("username", player.getName());
        player.sendMessage("§7Đang tạo mã liên kết...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                JsonObject result = post("/internal/link/create", body);
                if (!result.has("success") || !result.get("success").getAsBoolean()) {
                    send(player, "§cKhông tạo được mã. Hãy thử lại sau."); return;
                }
                send(player, "§aMã liên kết: §f" + result.get("code").getAsString());
                send(player, "§eMã dùng một lần, hết hạn sau 5 phút. Không chia sẻ mã này.");
            } catch (Exception e) {
                getLogger().warning("Link request failed: " + e.getClass().getSimpleName());
                send(player, "§cKhông kết nối được website. Hãy thử lại sau.");
            }
        });
        return true;
    }

    private boolean authMeLoggedIn(Player player) {
        try {
            Class<?> cls = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = cls.getMethod("getInstance").invoke(null);
            return api != null && Boolean.TRUE.equals(cls.getMethod("isAuthenticated", Player.class).invoke(api, player));
        } catch (Exception e) { getLogger().warning("AuthMe API check failed."); return false; }
    }

    private void poll() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            JsonObject response = post("/internal/points/claim", new JsonObject());
            if (!response.has("job") || response.get("job").isJsonNull()) { busy.set(false); return; }
            JsonObject job = response.getAsJsonObject("job");
            String id = job.get("id").getAsString(), uuid = job.get("uuid").getAsString();
            int points = job.get("points").getAsInt();
            Bukkit.getScheduler().runTask(this, () -> process(id, uuid, points));
        } catch (Exception e) {
            getLogger().warning("Queue poll failed: " + e.getClass().getSimpleName());
            busy.set(false);
        }
    }

    private void process(String orderId, String uuidText, int points) {
        try {
            String old = ledger.getProperty(orderId);
            if ("DONE".equals(old)) { ackAsync(orderId, "done"); return; }
            if ("STARTED".equals(old) || "REVIEW".equals(old)) {
                ledger.setProperty(orderId, "REVIEW"); saveLedger();
                ackAsync(orderId, "review");
                getLogger().severe("Order " + orderId + " needs manual review; refusing to grant twice.");
                return;
            }
            UUID uuid = UUID.fromString(uuidText);
            if (points < 1 || points > 100000000) {
                ledger.setProperty(orderId, "REVIEW"); saveLedger(); ackAsync(orderId, "review"); return;
            }

            // Persist STARTED before grant. A crash in an uncertain window goes to
            // manual review instead of automatic re-granting.
            ledger.setProperty(orderId, "STARTED"); saveLedger();
            try {
                Object pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
                if (pp == null || !pp.isEnabled()) throw new IllegalStateException("PlayerPoints unavailable");
                Object api = pp.getClass().getMethod("getAPI").invoke(pp);
                Method give = api.getClass().getMethod("give", UUID.class, int.class);
                Object ok = give.invoke(api, uuid, points);
                if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("PlayerPoints returned false");
                ledger.setProperty(orderId, "DONE"); saveLedger();
                ackAsync(orderId, "done");
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) online.sendMessage("§aNIGHTMARE TCYT: Bạn đã nhận §e" + points + " §ađiểm.");
            } catch (Exception e) {
                ledger.setProperty(orderId, "REVIEW"); saveLedger(); ackAsync(orderId, "review");
                getLogger().severe("Uncertain delivery for " + orderId + "; manual review required.");
            }
        } catch (Exception e) {
            getLogger().severe("Queue job could not be processed safely; review required.");
            ackAsync(orderId, "review");
        } finally { busy.set(false); }
    }

    private void ackAsync(String id, String status) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                JsonObject body = new JsonObject(); body.addProperty("status", status);
                post("/internal/points/" + id + "/ack", body);
            } catch (Exception e) {
                getLogger().warning("ACK failed for " + id + "; retry will use local ledger.");
            }
        });
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

    private synchronized void saveLedger() throws IOException {
        Path tmp = ledgerPath.resolveSibling("delivery-ledger.tmp");
        try (var out = Files.newOutputStream(tmp)) { ledger.store(out, "NIGHTMARE TCYT delivery ledger; do not delete"); }
        try { Files.move(tmp, ledgerPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ex) { Files.move(tmp, ledgerPath, StandardCopyOption.REPLACE_EXISTING); }
    }
  }
            
