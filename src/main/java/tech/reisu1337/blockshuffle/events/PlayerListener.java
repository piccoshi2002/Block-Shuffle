package tech.reisu1337.blockshuffle.events;

import com.google.common.collect.Sets;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
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

    // Adventure sounds (registry-safe for Paper 26.1.2)
    private static final Sound SND_BLOCK_FOUND  = Sound.sound(Key.key("minecraft:block.beacon.activate"),  Sound.Source.MASTER, 1f, 1f);
    private static final Sound SND_BLOCK_FAILED = Sound.sound(Key.key("minecraft:entity.villager.no"),      Sound.Source.MASTER, 1f, 1f);
    private static final Sound SND_POINT_EARNED = Sound.sound(Key.key("minecraft:entity.player.attack.sweep"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound SND_COUNTDOWN    = Sound.sound(Key.key("minecraft:block.note_block.hat"),    Sound.Source.MASTER, 1f, 1f);
    private static final Sound SND_GO           = Sound.sound(Key.key("minecraft:block.note_block.hat"),    Sound.Source.MASTER, 1f, 2f);

    // ── Per-round state ──────────────────────────────────────────────────────
    /** Players still in the game this round and their assigned block. */
    private final Map<UUID, Material> userMaterialMap = new ConcurrentHashMap<>();
    /** Players who successfully stood on their block this round. */
    private final Set<UUID> completedUsers = Sets.newConcurrentHashSet();
    /** All players participating in the current game. */
    private final Set<UUID> usersInGame = Sets.newConcurrentHashSet();
    /** Accumulated points per player across all rounds. */
    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>();
    /** Total blocks found per player across all rounds. */
    private final Map<UUID, Integer> blocksFound = new ConcurrentHashMap<>();

    // ── Misc state ───────────────────────────────────────────────────────────
    private final Random random = new Random();
    private final BlockShuffle plugin;
    private final YamlConfiguration settings;

    private List<Material> materials;
    private int bossBarTaskId      = -1;
    private int roundEndTaskId     = -1;
    private int actionBarTaskId    = -1;
    private BossBar bossBar;
    private Scoreboard scoreboard;
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
            this.blocksFound.put(uuid, 0);
            if (this.gameWorld == null) {
                this.gameWorld = player.getWorld();
            }
        }

        this.scoreboard = createSidebar();
        this.bossBar = createBossBar();
        startCountdown();
    }

    public void resetGame() {
        // Remove the custom scoreboard from all players before clearing state
        for (UUID uuid : this.usersInGame) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }

        this.userMaterialMap.clear();
        this.usersInGame.clear();
        this.completedUsers.clear();
        this.scores.clear();
        this.blocksFound.clear();
        this.countdownActive = false;
        this.currentRoundTicks = ROUND_TICKS_START;
        this.plugin.setInProgress(false);

        if (this.bossBar != null) this.bossBar.removeAll();
        cancelTask(roundEndTaskId);
        cancelTask(bossBarTaskId);
        cancelTask(actionBarTaskId);
        roundEndTaskId  = -1;
        bossBarTaskId   = -1;
        actionBarTaskId = -1;
        this.gameWorld  = null;
        this.scoreboard = null;
    }

    public void setMaterialPath(String materialPath) {
        this.materialPath = materialPath;
    }

    // ── Countdown ────────────────────────────────────────────────────────────

    private void startCountdown() {
        this.countdownActive = true;
        this.bossBar.setVisible(false);

        broadcast(Component.text("Game starting in " + COUNTDOWN_SECS + " seconds! Get ready!", NamedTextColor.YELLOW));

        for (int i = COUNTDOWN_SECS; i >= 1; i--) {
            final int secondsLeft = i;
            final long delay = (long)(COUNTDOWN_SECS - secondsLeft) * 20L;
            Bukkit.getScheduler().scheduleSyncDelayedTask(this.plugin, () -> {
                for (UUID uuid : this.usersInGame) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) continue;
                    p.showTitle(Title.title(
                        Component.text(secondsLeft, NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
                    ));
                    p.playSound(SND_COUNTDOWN);
                }
            }, delay);
        }

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
                p.playSound(SND_GO);
            }
            nextRound();
        }, (long) COUNTDOWN_SECS * 20L);
    }

    // ── Round logic ──────────────────────────────────────────────────────────

    private void nextRound() {
        cancelTask(roundEndTaskId);

        if (checkForWinner()) return;

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

        updateSidebar();
        startActionBarTask();

        this.bossBarTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this.plugin, this::updateBossBar, 0L, 20L);
        this.roundEndTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(
                this.plugin, this::onRoundEnd, this.currentRoundTicks);
    }

    private void onRoundEnd() {
        cancelTask(bossBarTaskId);
        cancelTask(actionBarTaskId);
        bossBarTaskId   = -1;
        actionBarTaskId = -1;

        int totalPlayers = this.usersInGame.size();
        int successCount = this.completedUsers.size();
        int failCount    = totalPlayers - successCount;

        if (successCount == 0 || failCount == 0) {
            if (successCount == 0) {
                // Everyone failed — play fail sound for all
                for (UUID uuid : this.usersInGame) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.playSound(SND_BLOCK_FAILED);
                }
                broadcast(buildMessage("Nobody found their block — no points awarded!", NamedTextColor.YELLOW));
            } else {
                // Everyone succeeded — shrink the timer
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
            // Mixed result — award points to successful players, fail sound to others
            for (UUID uuid : this.completedUsers) {
                this.scores.merge(uuid, 1, Integer::sum);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.playSound(SND_POINT_EARNED);
                    p.sendMessage(buildMessage("+1 point! (" + this.scores.get(uuid) + "/" + POINTS_TO_WIN + ")", NamedTextColor.GREEN));
                }
            }
            StringJoiner failedNames = new StringJoiner(", ");
            for (UUID uuid : this.usersInGame) {
                if (!this.completedUsers.contains(uuid)) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.playSound(SND_BLOCK_FAILED);
                        failedNames.add(p.getName());
                    }
                }
            }
            broadcast(buildMessage("Failed: " + failedNames + " — successful players earn a point!", NamedTextColor.RED));
        }

        updateSidebar();
        nextRound();
    }

    // ── Winner detection ─────────────────────────────────────────────────────

    private boolean checkForWinner() {
        int maxScore = this.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxScore < POINTS_TO_WIN) return false;

        List<UUID> leaders = this.scores.entrySet().stream()
                .filter(e -> e.getValue() == maxScore)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (leaders.size() == 1) {
            Player winner = Bukkit.getPlayer(leaders.get(0));
            String name = winner != null ? winner.getName() : "Unknown";
            broadcast(buildMessage("🏆 " + name + " wins with " + maxScore + " point(s)! 🏆", NamedTextColor.GOLD));
            concludeRGA();
            resetGame();
            return true;
        }

        StringJoiner tied = new StringJoiner(", ");
        for (UUID uuid : leaders) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) tied.add(p.getName());
        }
        broadcast(buildMessage("TIE at " + maxScore + " points between: " + tied + " — keep playing!", NamedTextColor.AQUA));
        return false;
    }

    // ── Sidebar scoreboard ────────────────────────────────────────────────────

    /**
     * Creates a fresh scoreboard with a sidebar objective and assigns it to
     * every player in the game.
     */
    private Scoreboard createSidebar() {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective(
                "blockshuffle", "dummy",
                Component.text("◆ BlockShuffle ◆", NamedTextColor.GOLD, TextDecoration.BOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (UUID uuid : this.usersInGame) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.setScoreboard(board);
        }
        return board;
    }

    /**
     * Rebuilds the sidebar lines to reflect current scores, blocks found, and
     * the found/searching indicator for this round.
     *
     * Uses the Team-prefix trick to hide score numbers: each line is a Team
     * whose prefix holds the visible text; the actual scoreboard entry is an
     * invisible unique color-code string. This works on all Paper builds.
     */
    private void updateSidebar() {
        if (this.scoreboard == null) return;

        Objective obj = this.scoreboard.getObjective("blockshuffle");
        if (obj == null) return;

        // Remove all existing teams and reset all entries
        for (Team t : this.scoreboard.getTeams()) t.unregister();
        for (String entry : this.scoreboard.getEntries()) this.scoreboard.resetScores(entry);

        // Sort players by score descending, then name for stability
        List<UUID> sorted = this.usersInGame.stream()
                .sorted(Comparator
                        .comparingInt((UUID u) -> this.scores.getOrDefault(u, 0))
                        .reversed()
                        .thenComparing(u -> {
                            Player p = Bukkit.getPlayer(u);
                            return p != null ? p.getName() : "";
                        }))
                .collect(Collectors.toList());

        // Each player = 2 lines; plus top + bottom blank = sorted.size()*2 + 2 lines total
        int lineIndex = sorted.size() * 2 + 2;

        // Helper: create a team whose prefix IS the visible text, entry is a
        // unique invisible string (stack of §r codes unique per slot index).
        // The score number is never shown because the entry itself is blank-looking.
        addLine(obj, "bs_top", buildInvisibleEntry(lineIndex), Component.empty(), lineIndex--);

        for (UUID uuid : sorted) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : "Unknown";
            int pts    = this.scores.getOrDefault(uuid, 0);
            int found  = this.blocksFound.getOrDefault(uuid, 0);
            boolean foundThisRound = this.completedUsers.contains(uuid);
            boolean stillSearching = this.userMaterialMap.containsKey(uuid);

            Component indicator = foundThisRound
                    ? Component.text("✔ ", NamedTextColor.GREEN)
                    : (stillSearching
                            ? Component.text("⧖ ", NamedTextColor.YELLOW)
                            : Component.text("✘ ", NamedTextColor.GRAY));

            Component nameLine = indicator.append(Component.text(name, NamedTextColor.WHITE));
            Component statsLine = Component.text("  pts:", NamedTextColor.GRAY)
                    .append(Component.text(pts, NamedTextColor.AQUA))
                    .append(Component.text(" found:", NamedTextColor.GRAY))
                    .append(Component.text(found, NamedTextColor.LIGHT_PURPLE));

            addLine(obj, "bs_name_" + uuid.toString().replace("-","").substring(0,8),
                    buildInvisibleEntry(lineIndex), nameLine,  lineIndex--);
            addLine(obj, "bs_stat_" + uuid.toString().replace("-","").substring(0,8),
                    buildInvisibleEntry(lineIndex), statsLine, lineIndex--);
        }

        addLine(obj, "bs_bot", buildInvisibleEntry(lineIndex), Component.empty(), lineIndex);
    }

    /**
     * Registers a team, sets its prefix to the display component, adds a unique
     * invisible entry to it, and assigns that entry a score on the objective.
     */
    private void addLine(Objective obj, String teamName, String entry, Component prefix, int score) {
        Team team = this.scoreboard.registerNewTeam(teamName);
        team.prefix(prefix);
        team.addEntry(entry);
        obj.getScore(entry).setScore(score);
    }

    /**
     * Builds a unique invisible entry string for a given line index using
     * stacked §r reset codes. These are visually empty but each unique,
     * satisfying the scoreboard's requirement that entries be distinct.
     */
    private String buildInvisibleEntry(int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < index; i++) sb.append("§r");
        return sb.toString();
    }

    // ── Action bar ────────────────────────────────────────────────────────────

    /**
     * Starts a repeating task that sends each player their current target block
     * as an action bar message (above the hotbar) every second.
     * Stops automatically when cancelled in onRoundEnd / resetGame.
     */
    private void startActionBarTask() {
        cancelTask(actionBarTaskId);
        actionBarTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this.plugin, () -> {
            for (UUID uuid : this.usersInGame) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;

                Component bar;
                if (this.completedUsers.contains(uuid)) {
                    bar = Component.text("✔ Found! Waiting for round to end…", NamedTextColor.GREEN, TextDecoration.ITALIC);
                } else {
                    Material target = this.userMaterialMap.get(uuid);
                    if (target == null) continue;
                    bar = Component.text("Find: ", NamedTextColor.GRAY)
                            .append(Component.text(formatMaterialName(target), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                }
                p.sendActionBar(bar);
            }
        }, 0L, 20L); // every second
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
            this.blocksFound.merge(uuid, 1, Integer::sum);

            player.playSound(SND_BLOCK_FOUND);
            broadcast(buildMessage(player.getName() + " found their block!", NamedTextColor.GREEN));
            updateSidebar();

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
        this.blocksFound.remove(uuid);

        if (this.usersInGame.isEmpty()) {
            concludeRGA();
            resetGame();
            return;
        }

        updateSidebar();

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
