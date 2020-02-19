//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package me.bristermitten.privatemines.service;

import com.boydti.fawe.FaweAPI;
import com.boydti.fawe.object.schematic.Schematic;
import com.boydti.fawe.object.visitor.FastIterator;
import com.boydti.fawe.util.EditSessionBuilder;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.bukkit.BukkitUtil;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;
import me.bristermitten.privatemines.PrivateMines;
import me.bristermitten.privatemines.config.BlockType;
import me.bristermitten.privatemines.config.PMConfig;
import me.bristermitten.privatemines.data.MineLocations;
import me.bristermitten.privatemines.data.PrivateMine;
import me.bristermitten.privatemines.data.SellNPC;
import me.bristermitten.privatemines.world.MineWorldManager;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

public class MineFactory {
    public static final boolean DEFAULT_MINE_OPEN = true;
    private final MineWorldManager manager;
    private final File file;
    private final PMConfig config;
    private final PrivateMines plugin;

    private final EditSession extent;
    private final World world;

    public MineFactory(PrivateMines plugin, MineWorldManager manager, PMConfig config) {
        this.plugin = plugin;
        this.manager = manager;
        this.file = new File(plugin.getDataFolder(), config.getSchematicName());
        this.config = config;

        world = FaweAPI.getWorld(manager.getMinesWorld().getName());

        this.extent = new EditSessionBuilder(world).allowedRegionsEverywhere().limitUnlimited()
                .fastmode(true).build();
    }

    @SuppressWarnings("deprecation")
    public PrivateMine create(Player owner) {
        try {
            Location location = manager.nextFreeLocation();
            Schematic schematic = loadSchematic();
            Clipboard clipboard = schematic.getClipboard();

            if (clipboard == null) {
                plugin.getLogger().severe("Schematic does not have a Clipboard!");

                return null;
            }

            location.setY(clipboard.getOrigin().getBlockY());

            Vector centerVector = BukkitUtil.toVector(location);
            schematic.paste(extent, centerVector, false, true, null);

            Region region = clipboard.getRegion();
            region.setWorld(world);
            region.shift(centerVector.subtract(clipboard.getOrigin()));

            Location spawnLoc = null;
            Location npcLoc = null;
            Vector min = null;
            Vector max = null;

            Map<BlockType, Material> blockTypes = config.getBlockTypes();
            Material spawnMaterial = blockTypes.get(BlockType.SPAWNPOINT);
            Material cornerMaterial = blockTypes.get(BlockType.CORNER);
            Material npcMaterial = blockTypes.get(BlockType.NPC);

            for (Vector pt : new FastIterator(region, extent)) {
                Material type = Material.values()[world.getLazyBlock(pt).getType()];

                if (type == Material.AIR) continue;

                if (spawnLoc == null && type == spawnMaterial) {
                    spawnLoc = new Location(location.getWorld(), pt.getX(), pt.getY(), pt.getZ());
                    world.setBlock(pt, new BaseBlock(0));
                    continue;
                }

                if (type == cornerMaterial) {
                    if (min == null) {
                        min = new Vector(pt);
                        continue;
                    }
                    if (max == null) {
                        max = new Vector(pt);
                        continue;
                    }
                    plugin.getLogger().warning("Mine schematic contains >2 corner blocks!");
                    continue;
                }
                if (type == npcMaterial) {
                    npcLoc =
                            new Location(location.getWorld(), pt.getX(), pt.getY(), pt.getZ())
                                    .getBlock().getLocation();
                    npcLoc.add(npcLoc.getX() > 0 ? 0.5 : -0.5, 0.0, npcLoc.getZ() > 0 ? 0.5 : -0.5);
                    world.setBlock(pt, new BaseBlock(0));
                }
            }

            if (spawnLoc == null) spawnLoc = location.getWorld().getHighestBlockAt(location).getLocation();

            if (min == null || max == null || min.equals(max)) {
                throw new RuntimeException("Mine schematic did not define 2 corner blocks, mine cannot be formed");
            }
            if (npcLoc == null) npcLoc = spawnLoc;

            RegionManager regionManager =
                    WorldGuardPlugin.inst().getRegionManager(location.getWorld());

            ProtectedRegion worldGuardRegion = createMainWorldGuardRegion(owner, region);
            regionManager.addRegion(worldGuardRegion);

            ProtectedRegion mineRegion = createMineWorldGuardRegion(owner, min, max, worldGuardRegion);

            regionManager.addRegion(mineRegion);

            MineLocations locations = new MineLocations(spawnLoc, min, max, mineRegion);
            NPC npc = SellNPC.createSellNPC(
                    config.getNPCName(),
                    config.getNPCSkin(),
                    npcLoc,
                    owner.getUniqueId());


            return new PrivateMine(
                    owner.getUniqueId(),
                    DEFAULT_MINE_OPEN,
                    config.getDefaultBlock(),
                    region,
                    locations,
                    worldGuardRegion,
                    npc.getUniqueId(),
                    config.getTaxPercentage());

        } catch (WorldEditException | CircularInheritanceException | IOException e) {
            e.printStackTrace();
            return null;
        }

    }

    @NotNull
    private ProtectedRegion createMineWorldGuardRegion(Player owner, Vector min, Vector max, ProtectedRegion parent) throws CircularInheritanceException {
        ProtectedRegion mineRegion = new ProtectedCuboidRegion(
                owner.getUniqueId().toString() + "-mine",
                min.toBlockPoint(),
                max.toBlockPoint());

        mineRegion.setParent(parent);
        mineRegion.setFlag(DefaultFlag.BLOCK_BREAK, State.ALLOW);
        return mineRegion;
    }

    @NotNull
    private ProtectedRegion createMainWorldGuardRegion(Player owner, Region r) {
        ProtectedRegion region = new ProtectedCuboidRegion(
                owner.getUniqueId().toString(),
                r.getMinimumPoint().toBlockPoint(),
                r.getMaximumPoint().toBlockPoint());

        region.setFlag(DefaultFlag.BLOCK_BREAK, State.DENY);
        region.setFlag(DefaultFlag.BLOCK_PLACE, State.DENY);
        region.setFlag(DefaultFlag.MOB_SPAWNING, State.DENY);

        DefaultDomain domain = new DefaultDomain();
        domain.addPlayer(owner.getUniqueId());
        region.setOwners(domain);
        return region;
    }

    private Schematic loadSchematic() throws IOException {
        ClipboardFormat format = ClipboardFormat.SCHEMATIC;
        return format.load(new FileInputStream(file));
    }

    public MineWorldManager getManager() {
        return manager;
    }
}