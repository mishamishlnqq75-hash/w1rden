package me.wardencrossbow;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class WardenCrossbowPlugin extends JavaPlugin implements Listener {

    private static final long COOLDOWN_MS = 90_000L;
    private static final double MAX_DISTANCE = 60.0;
    private static final double PROJECTILE_SPEED = 1.2;

    private NamespacedKey wardenCrossbowKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> shooting = new HashSet<>();

    @Override
    public void onEnable() {
        wardenCrossbowKey = new NamespacedKey(this, "warden_crossbow");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("wardenbow")).setExecutor(this);
        startCooldownDisplay();
        getLogger().info("WardenCrossbow включён!");
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
        shooting.clear();
    }

    private ItemStack createWardenCrossbow() {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName("§5Скалковый арбалет");

        ArrayList<String> lore = new ArrayList<>();
        lore.add("§7Заряжен силой Вардена");
        lore.add("§7");
        lore.add("§8ПКМ §7— выпустить звуковой заряд");
        lore.add("§8При попадании §7происходит взрыв");
        lore.add("§7КД: §f1 мин. 30 сек.");
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(
                wardenCrossbowKey,
                PersistentDataType.BYTE,
                (byte) 1
        );
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWardenCrossbow(ItemStack item) {
        if (item == null || item.getType() != Material.CROSSBOW || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        Byte value = meta.getPersistentDataContainer().get(
                wardenCrossbowKey,
                PersistentDataType.BYTE
        );
        return value != null && value == (byte) 1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭту команду может использовать только игрок.");
            return true;
        }

        player.getInventory().addItem(createWardenCrossbow());
        player.sendMessage("§5Скалковый арбалет §7выдан!");
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isWardenCrossbow(item)) return;

        event.setCancelled(true);

        if (shooting.contains(player.getUniqueId())) return;

        long now = System.currentTimeMillis();
        Long cooldownEnd = cooldowns.get(player.getUniqueId());

        if (cooldownEnd != null && cooldownEnd > now) {
            player.sendActionBar("§5Скалковый арбалет §8| §cКД: §f"
                    + formatTime(cooldownEnd - now));
            return;
        }

        shoot(player);
    }

    private void shoot(Player player) {
        UUID uuid = player.getUniqueId();
        shooting.add(uuid);
        cooldowns.put(uuid, System.currentTimeMillis() + COOLDOWN_MS);

        Location start = player.getEyeLocation().clone();
        Vector direction = start.getDirection().normalize();
        World world = start.getWorld();

        if (world != null) {
            world.playSound(start, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.0f);
            world.spawnParticle(Particle.SONIC_BOOM, start, 1, 0, 0, 0, 0);
        }

        new BukkitRunnable() {
            private Location location = start.clone();
            private double distance = 0;

            @Override
            public void run() {
                location.add(direction.clone().multiply(PROJECTILE_SPEED));
                distance += PROJECTILE_SPEED;

                World currentWorld = location.getWorld();
                if (currentWorld == null) {
                    stop();
                    return;
                }

                currentWorld.spawnParticle(Particle.SONIC_BOOM, location, 1, 0, 0, 0, 0);

                if (!location.getBlock().isPassable()) {
                    stop();
                    currentWorld.playSound(location, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.2f);
                    return;
                }

                for (Entity entity : currentWorld.getNearbyEntities(location, 1.0, 1.0, 1.0)) {
                    if (!(entity instanceof Player target) || target.equals(player)) continue;

                    hitPlayer(target);
                    stop();
                    return;
                }

                if (distance >= MAX_DISTANCE) stop();
            }

            private void stop() {
                shooting.remove(uuid);
                cancel();
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void hitPlayer(Player target) {
        Location location = target.getLocation().clone();
        World world = location.getWorld();
        if (world == null) return;

        world.playSound(location, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.8f);
        world.spawnParticle(
                Particle.SONIC_BOOM,
                location.clone().add(0, 1, 0),
                3, 0.2, 0.5, 0.2, 0
        );

        world.createExplosion(
                location.getX(),
                location.getY(),
                location.getZ(),
                4.0f,
                false,
                false
        );
    }

    private void startCooldownDisplay() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    Long end = cooldowns.get(uuid);
                    if (end == null) continue;

                    long remaining = end - now;

                    if (remaining <= 0) {
                        cooldowns.remove(uuid);
                        if (isWardenCrossbow(player.getInventory().getItemInMainHand())) {
                            player.sendActionBar("§5Скалковый арбалет §8| §aГОТОВ");
                        }
                        continue;
                    }

                    if (!isWardenCrossbow(player.getInventory().getItemInMainHand())) continue;

                    player.sendActionBar(
                            "§5Скалковый арбалет §8| §cКД: §f"
                                    + formatTime(remaining)
                    );
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private String formatTime(long milliseconds) {
        long totalSeconds = (milliseconds + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cooldowns.remove(uuid);
        shooting.remove(uuid);
    }
}
