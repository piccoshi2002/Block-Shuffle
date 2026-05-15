package tech.reisu1337.blockshuffle.menus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.reisu1337.blockshuffle.BlockShuffle;
import tech.reisu1337.blockshuffle.events.PlayerListener;

public class BlockShuffleMenu implements InventoryHolder, Listener {
    private final Inventory inventory = Bukkit.createInventory(this, 9,
            Component.text("BlockShuffle!", NamedTextColor.GOLD));
    private final PlayerListener playerListener;

    private final String startMessage;
    private final String startError;
    private final BlockShuffle plugin;

    public BlockShuffleMenu(PlayerListener playerListener, YamlConfiguration settings, BlockShuffle plugin) {
        this.playerListener = playerListener;
        this.startMessage = settings.getString("start");
        this.startError = settings.getString("starterror");
        this.plugin = plugin;

        this.inventory.setItem(2, makeItem(Material.GRASS_BLOCK, "BlockShuffle"));
        this.inventory.setItem(3, makeItem(Material.STONE, "Simple BlockShuffle"));
        this.inventory.setItem(4, makeItem(Material.NETHERRACK, "Nether BlockShuffle"));
        this.inventory.setItem(5, makeItem(Material.LIME_WOOL, "Colour BlockShuffle"));
        this.inventory.setItem(6, makeItem(Material.BOOK, "Custom BlockShuffle"));
    }

    private ItemStack makeItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BlockShuffleMenu)) return;
        event.setCancelled(true);

        switch (event.getRawSlot()) {
            case 2 -> this.playerListener.setMaterialPath("materials");
            case 3 -> this.playerListener.setMaterialPath("easy_materials");
            case 4 -> this.playerListener.setMaterialPath("nether_materials");
            case 5 -> this.playerListener.setMaterialPath("colour_materials");
            case 6 -> this.playerListener.setMaterialPath("user_materials");
            default -> { return; }
        }

        Player player = (Player) event.getWhoClicked();
        Component prefix = Component.text("<BlockShuffle> ", NamedTextColor.GOLD);

        if (this.plugin.isInProgress()) {
            player.sendMessage(prefix.append(Component.text(this.startError, NamedTextColor.RED)));
        } else {
            player.sendMessage(prefix.append(Component.text(this.startMessage, NamedTextColor.GREEN)));
            this.playerListener.startGame();
            this.plugin.setInProgress(true);
        }
        player.closeInventory();
    }

    public void show(Player player) {
        player.openInventory(this.inventory);
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }
}
