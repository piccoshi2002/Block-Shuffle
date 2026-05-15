package tech.reisu1337.blockshuffle.events;

import com.google.common.collect.Sets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tech.reisu1337.blockshuffle.BlockShuffle;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PlayerListener implements Listener {

    // ── Constants ────────────────────────────────────────────────────────────
    private static final int POINTS_TO_WIN      = 3;
    private static final int ROUND_TICKS_START  = 6000; // 5 minutes
    private static final int ROUND_TICKS_MIN    = 1200; // 1 minute floor
    private static final int ROUND_TICKS_SHRINK = 1200; // shrink by 1 minute when all succeed
    private static final int COUNTDOWN_SECS     = 5;

    // ── Per-round state ──────────────────────────────────────────────────────
    /** Players still in the game this round and their assigned block. */
    private final Map<UUID, Material> userMaterialMap = new ConcurrentHashMap<>();
    /** Players who successfully stood on their block this round. */
    private final Set<UUID> completedUsers = Sets.newConcurrentHashSet();
    /** All players participating in the current game. */
    private final Set<UUID> usersInGame = Sets.newConcurrentHashSet();
    /** Accumulated points per player across all rounds. */
    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>();

    // ── Misc state ───────────────────────────────────────────────────────────
    private final Random random = new Random();
    private final BlockShuffle plugin;
    private final YamlConfiguration settings;

    private List<Material> materials;
    private int bossBarTaskId    = -1;
    private int roundEndTaskId   = -1;
    private BossBar bossBar;
    private long roundStartTime;
    private String materialPath;
    /** World the game is being played in — captured at game start for the RGA conclude command. */
    private World gameWorld;
    /** Current round duration in ticks — starts at ROUND_TICKS_START, shrinks when all succeed. */
    private int currentRoundTicks = ROUND_TICKS_START;
    /** True while the 5-second countdown is running (blocks movement detection). */
    private boolean countdownActive = false;

    // ── Constructor ──────────────────────────────────────────────────────────

    public PlayerListener(YamlConfiguration settings, BlockShuffle plugin) {
        this.settings = settings;
        this.plugin = plugin;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void startGame() {
        this.materials = this.settings.getStringList(this.materialPath).stream()
                .map(Material::getMaterial)
                .collect(Collectors.toList());

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            this.usersInGame.add(uuid);
            this.scores.put(uuid, 0);
            // Capture the world from the first player we see
            if (this.gameWorld == null) {
                this.gameWorld = player.getWorld();
            }
        }

        this.bossBar = createBossBar();
        startCountdown();
    }

    public void resetGame() {
        this.userMaterialMap.clear();
        this.usersInGame.clear();
        this.completedUsers.clear();
        this.scores.clear();
        this.countdownActive = false;
        this.currentRoundTicks = ROUND_TICKS_START;
        this.plugin.setInProgress(false);

        if (this.bossBar != null) this.bossBar.removeAll();
        cancelTask(roundEndTaskId);
        cancelTask(bossBarTaskId);
        roundEndTaskId = -1;
        bossBarTaskId  = -1;
        this.gameWorld = null;
    }

    public void setMaterialPath(String materialPath) {
        this.materialPath = materialPath;
    }

    // ── Countdown ────────────────────────────────────────────────────────────

    private void startCountdown() {
        this.countdownActive = true;
        this.bossBar.setVisible(false);

        broadcast(Component.text("Game starting in " + COUNTDOWN_SECS + " seconds! Get ready!", NamedTextColor.YELLOW));

        // Schedule one task per countdown tick
        for (int i = COUNTDOWN_SECS; i >= 1; i--) {
            final int secondsLeft = i;
            final long delay = (long)(COUNTDOWN_SECS - secondsLeft) * 20L;
            Bukkit.getScheduler().scheduleSyncDelayedTask(this.plugin, () -> {
                for (UUID uuid : this.usersInGame) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) continue;
                    // Show countdown number as a title
                    p.showTitle(Title.title(
                        Component.text(secondsLeft, NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
                    ));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                }
            }, delay);
        }

        // After countdown finishes, kick off the first round
        Bukkit.getScheduler().scheduleSyncDelayedTask(this.plugin, () -> {
            this.countdownActive = false;
            for (UUID uuid : this.usersInGame) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                p.showTitle(Title.title(
                    Component.text("GO!", NamedTextColor.GREEN, TextDecoration.BOLD),
                    Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(200))
                ));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 2f);
            }
            nextRound();
        }, (long) COUNTDOWN_SECS * 20L);
    }

    // ── Round logic ──────────────────────────────────────────────────────────

    private void nextRound() {
        cancelTask(roundEndTaskId);

        // ── Score the previous round (skip on the very first call) ──────────
        // completedUsers is empty at game start, so this block is effectively
        // a no-op for round 1 — scoring only runs from round 2 onward after
        // results have been evaluated in onRoundEnd().
        // (Scoring is actually applied in onRoundEnd; nextRound just sets up
        //  the new round after scoring is done.)

        if (checkForWinner()) return;

        // ── Assign blocks for the new round ─────────────────────────────────
        this.completedUsers.clear();
        this.userMaterialMap.clear();
        this.bossBar.setVisible(true);
        this.roundStartTime = System.currentTimeMillis();

        for (UUID uuid : this.usersInGame) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            Material block = getRandomMaterial();
            String friendlyName = formatMaterialName(block);
            this.userMaterialMap.put(uuid, block);
            BlockShuffle.LOGGER.log(Level.INFO, player.getName() + " got " + friendlyName);
            player.sendMessage(
                prefix()
                    .append(Component.text("Find and stand on ", NamedTextColor.WHITE))
                    .append(Component.text(friendlyName, NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text(" — you have " + (this.currentRoundTicks / 1200) + " minute(s)!", NamedTextColor.WHITE))
            );
        }

        broadcastScoreboard();

        this.bossBarTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this.plugin, this::updateBossBar, 0L, 20L);
        this.roundEndTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                this.plugin, this::onRoundEnd, this.currentRoundTicks);
    }

    /**
     * Called when the timer expires. Scores the round, then hands off to nextRound().
     */
    private void onRoundEnd() {
        cancelTask(bossBarTaskId);
        bossBarTaskId = -1;

        int totalPlayers  = this.usersInGame.size();
        int successCount  = this.completedUsers.size();
        int failCount     = totalPlayers - successCount;

        if (successCount == 0 || failCount == 0) {
            // Nobody gets a point — either everyone succeeded or everyone failed
            if (successCount == 0) {
                broadcast(buildMessage("Nobody found their block — no points awarded!", NamedTextColor.YELLOW));
            } else {
                // Everyone succeeded — shrink the timer for next round
                int newTicks = Math.max(ROUND_TICKS_MIN, this.currentRoundTicks - ROUND_TICKS_SHRINK);
                if (newTicks < this.currentRoundTicks) {
                    this.currentRoundTicks = newTicks;
                    broadcast(buildMessage(
                        "Everyone found their block — no points awarded! Timer reduced to "
                        + (this.currentRoundTicks / 1200) + " minute(s)!",
                        NamedTextColor.YELLOW));
                } else {
                    broadcast(buildMessage(
                        "Everyone found their block — no points awarded! (Timer already at minimum: 1 minute)",
                        NamedTextColor.YELLOW));
                }
            }
        } else {
            // Award a point to each player who succeeded
            for (UUID uuid : this.completedUsers) {
                this.scores.merge(uuid, 1, Integer::sum);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.sendMessage(buildMessage("+1 point! (" + this.scores.get(uuid) + "/" + POINTS_TO_WIN + ")", NamedTextColor.GREEN));
                }
            }
            // Announce who failed
            StringJoiner failedNames = new StringJoiner(", ");
            for (UUID uuid : this.usersInGame) {
                if (!this.completedUsers.contains(uuid)) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) failedNames.add(p.getName());
                }
            }
            broadcast(buildMessage("Failed: " + failedNames + " — successful players earn a point!", NamedTextColor.RED));
        }

        // Hand off to next round (which will check for a winner first)
        nextRound();
    }

    // ── Winner detection ─────────────────────────────────────────────────────

    /**
     * Returns true (and ends the game) if a winner can be determined.
     * Handles ties: if two or more players are tied at ≥ POINTS_TO_WIN,
     * the game continues until one of them pulls ahead.
     */
    private boolean checkForWinner() {
        int maxScore = this.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxScore < POINTS_TO_WIN) return false;

        List<UUID> leaders = this.scores.entrySet().stream()
                .filter(e -> e.getValue() == maxScore)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (leaders.size() == 1) {
            // Clear winner
            Player winner = Bukkit.getPlayer(leaders.get(0));
            String name = winner != null ? winner.getName() : "Unknown";
            broadcast(buildMessage("🏆 " + name + " wins with " + maxScore + " point(s)! 🏆", NamedTextColor.GOLD));
            concludeRGA();
            resetGame();
            return true;
        }

        // Tie at ≥ POINTS_TO_WIN — continue until one breaks ahead
        StringJoiner tied = new StringJoiner(", ");
        for (UUID uuid : leaders) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) tied.add(p.getName());
        }
        broadcast(buildMessage("TIE at " + maxScore + " points between: " + tied + " — keep playing!", NamedTextColor.AQUA));
        return false;
    }

    // ── Scoreboard broadcast ─────────────────────────────────────────────────

    private void broadcastScoreboard() {
        Component header = Component.text("─── Scores ───", NamedTextColor.DARK_AQUA, TextDecoration.BOLD);
        broadcast(header);

        this.scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    String name = p != null ? p.getName() : "Unknown";
                    broadcast(Component.text("  " + name + ": " + entry.getValue() + "/" + POINTS_TO_WIN, NamedTextColor.WHITE));
                });

        broadcast(Component.text("──────────────", NamedTextColor.DARK_AQUA));
    }

    // ── Boss bar ─────────────────────────────────────────────────────────────

    private BossBar createBossBar() {
        BossBar bar = Bukkit.createBossBar("BlockShuffle", BarColor.PINK, BarStyle.SOLID);
        for (UUID uuid : this.usersInGame) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) bar.addPlayer(p);
        }
        return bar;
    }

    private void updateBossBar() {
        long elapsed   = System.currentTimeMillis() - this.roundStartTime;
        long totalMs   = (this.currentRoundTicks / 20L) * 1000L;
        long remaining = totalMs - elapsed;
        double progress = Math.max(0.0, Math.min(1.0, remaining / (double) totalMs));
        this.bossBar.setProgress(progress);
        this.bossBar.setTitle("Time Remaining: " + (remaining / 1000) + "s");
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerMoveEvent(PlayerMoveEvent event) {
        if (this.countdownActive) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!this.userMaterialMap.containsKey(uuid)) return;

        Material materialBelow = player.getLocation().getBlock()
                .getRelative(BlockFace.DOWN).getBlockData().getMaterial();

        if (this.userMaterialMap.get(uuid) == materialBelow) {
            this.userMaterialMap.remove(uuid);
            this.completedUsers.add(uuid);

            broadcast(buildMessage(player.getName() + " found their block!", NamedTextColor.GREEN));

            // If every player has now been resolved (found or timed out), end
            // the round immediately rather than waiting for the timer.
            if (this.userMaterialMap.isEmpty()) {
                cancelTask(roundEndTaskId);
                onRoundEnd();
            }
        }
    }

    @EventHandler
    public void onPlayerQuitEvent(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!this.usersInGame.contains(uuid)) return;

        this.usersInGame.remove(uuid);
        this.userMaterialMap.remove(uuid);
        this.completedUsers.remove(uuid);
        this.scores.remove(uuid);

        if (this.usersInGame.isEmpty()) {
            concludeRGA();
            resetGame();
            return;
        }

        // If the quitting player was the last one still looking, resolve now
        if (this.userMaterialMap.isEmpty() && !this.countdownActive) {
            cancelTask(roundEndTaskId);
            onRoundEnd();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Material getRandomMaterial() {
        return this.materials.get(this.random.nextInt(this.materials.size()));
    }

    private String formatMaterialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private Component prefix() {
        return Component.text("<BlockShuffle> ", NamedTextColor.GOLD);
    }

    private Component buildMessage(String text, NamedTextColor color) {
        return prefix().append(Component.text(text, color));
    }

    private void broadcast(Component message) {
        Bukkit.broadcast(message);
    }

    /**
     * Dispatches the RGA conclude command so Ronlab Game Assistant knows the
     * minigame has ended. RGA handles stripping _the_nether / _the_end suffixes
     * itself, so we can pass any dimension world name directly.
     */
    private void concludeRGA() {
        if (this.gameWorld == null) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rga conclude " + this.gameWorld.getName());
    }

    private void cancelTask(int taskId) {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }
}
