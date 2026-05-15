package tech.reisu1337.blockshuffle.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import tech.reisu1337.blockshuffle.BlockShuffle;
import tech.reisu1337.blockshuffle.events.PlayerListener;
import tech.reisu1337.blockshuffle.menus.BlockShuffleMenu;

public class BlockShuffleCommand implements CommandExecutor {
    private final String stopGame;
    private final String stopError;

    private final PlayerListener playerListener;
    private final BlockShuffleMenu blockShuffleMenu;
    private final BlockShuffle plugin;

    public BlockShuffleCommand(PlayerListener playerListener, BlockShuffleMenu blockShuffleMenu, BlockShuffle plugin, YamlConfiguration settings) {
        this.stopGame = settings.getString("stopgame");
        this.stopError = settings.getString("stoperror");
        this.playerListener = playerListener;
        this.blockShuffleMenu = blockShuffleMenu;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("blockshuffle")) return true;

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("You cannot execute this command from the console.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("blockshuffle.admin")) {
            player.sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            this.blockShuffleMenu.show(player);
        } else if (args[0].equalsIgnoreCase("stop")) {
            if (!this.plugin.isInProgress()) {
                player.sendMessage(prefix().append(Component.text(this.stopError, NamedTextColor.RED)));
            } else {
                this.playerListener.resetGame();
                Bukkit.broadcast(prefix().append(Component.text(this.stopGame, NamedTextColor.GREEN)));
            }
        } else {
            player.sendMessage(Component.text("Usage: /blockshuffle [stop]", NamedTextColor.YELLOW));
        }
        return true;
    }

    private Component prefix() {
        return Component.text("<BlockShuffle> ", NamedTextColor.GOLD);
    }
}
