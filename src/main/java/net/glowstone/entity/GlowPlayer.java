package net.glowstone.entity;

import com.destroystokyo.paper.Title;
import com.flowpowered.network.Message;
import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import net.glowstone.ChunkManager.ChunkLock;
import net.glowstone.*;
import net.glowstone.GlowChunk.Key;
import net.glowstone.block.GlowBlock;
import net.glowstone.block.ItemTable;
import net.glowstone.block.blocktype.BlockBed;
import net.glowstone.block.entity.TESign;
import net.glowstone.block.entity.TileEntity;
import net.glowstone.block.itemtype.ItemFood;
import net.glowstone.block.itemtype.ItemType;
import net.glowstone.constants.*;
import net.glowstone.entity.meta.ClientSettings;
import net.glowstone.entity.meta.MetadataIndex;
import net.glowstone.entity.meta.MetadataIndex.StatusFlags;
import net.glowstone.entity.meta.MetadataMap;
import net.glowstone.entity.meta.profile.PlayerProfile;
import net.glowstone.entity.objects.GlowItem;
import net.glowstone.inventory.GlowInventory;
import net.glowstone.inventory.InventoryMonitor;
import net.glowstone.io.PlayerDataService.PlayerReader;
import net.glowstone.net.GlowSession;
import net.glowstone.net.message.login.LoginSuccessMessage;
import net.glowstone.net.message.play.entity.*;
import net.glowstone.net.message.play.game.*;
import net.glowstone.net.message.play.game.NamedSoundEffectMessage.SoundCategory;
import net.glowstone.net.message.play.game.StateChangeMessage.Reason;
import net.glowstone.net.message.play.game.TitleMessage.Action;
import net.glowstone.net.message.play.game.UserListItemMessage.Entry;
import net.glowstone.net.message.play.inv.*;
import net.glowstone.net.message.play.player.PlayerAbilitiesMessage;
import net.glowstone.net.message.play.player.ResourcePackSendMessage;
import net.glowstone.net.message.play.player.UseBedMessage;
import net.glowstone.net.protocol.ProtocolType;
import net.glowstone.scoreboard.GlowScoreboard;
import net.glowstone.scoreboard.GlowTeam;
import net.glowstone.util.Position;
import net.glowstone.util.StatisticMap;
import net.glowstone.util.TextMessage;
import net.glowstone.util.nbt.CompoundTag;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.*;
import org.bukkit.Effect.Type;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.serialization.DelegateDeserialization;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.InventoryView.Property;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.map.MapView;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;
import org.json.simple.JSONObject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Represents an in-game player.
 *
 * @author Graham Edgecombe
 */
@DelegateDeserialization(GlowOfflinePlayer.class)
public final class GlowPlayer extends GlowHumanEntity implements Player {

    /**
     * A static entity id to use when telling the client about itself.
     */
    private static final int SELF_ID = 0;

    /**
     * This player's session.
     */
    private final GlowSession session;

    /**
     * The entities that the client knows about.
     */
    private final Set<GlowEntity> knownEntities = new HashSet<>();

    /**
     * The entities that are hidden from the client.
     */
    private final Set<UUID> hiddenEntities = new HashSet<>();

    /**
     * The chunks that the client knows about.
     */
    private final Set<Key> knownChunks = new HashSet<>();

    /**
     * A queue of BlockChangeMessages to be sent.
     */
    private final List<BlockChangeMessage> blockChanges = new LinkedList<>();

    /**
     * A queue of messages that should be sent after block changes are processed.
     * Used for sign updates and other situations where the block must be sent first.
     */
    private final List<Message> afterBlockChanges = new LinkedList<>();

    /**
     * The set of plugin channels this player is listening on
     */
    private final Set<String> listeningChannels = new HashSet<>();

    /**
     * The player's statistics, achievements, and related data.
     */
    private final StatisticMap stats = new StatisticMap();

    /**
     * Whether the player has played before (will be false on first join).
     */
    private final boolean hasPlayedBefore;

    /**
     * The time the player first played, or 0 if unknown.
     */
    private final long firstPlayed;

    /**
     * The time the player last played, or 0 if unknown.
     */
    private final long lastPlayed;
    private final Player.Spigot spigot = new Player.Spigot() {
        @Override
        public void playEffect(Location location, Effect effect, int id, int data, float offsetX, float offsetY, float offsetZ, float speed, int particleCount, int radius) {
            if (effect.getType() == Type.PARTICLE) {
                MaterialData material = new MaterialData(id, (byte) data);
                showParticle(location, effect, material, offsetX, offsetY, offsetZ, speed, particleCount);
            } else {
                playEffect_(location, effect, data);
            }
        }

    };
    /**
     * The time the player joined.
     */
    private long joinTime;
    /**
     * The settings sent by the client.
     */
    private ClientSettings settings = ClientSettings.DEFAULT;
    /**
     * The lock used to prevent chunks from unloading near the player.
     */
    private ChunkLock chunkLock;
    /**
     * The tracker for changes to the currently open inventory.
     */
    private InventoryMonitor invMonitor;
    /**
     * The display name of this player, for chat purposes.
     */
    private String displayName;
    /**
     * The name a player has in the player list
     */
    private String playerListName;
    /**
     * Cumulative amount of experience points the player has collected.
     */
    private int totalExperience;
    /**
     * The current level (or skill point amount) of the player.
     */
    private int level;
    /**
     * The progress made to the next level, from 0 to 1.
     */
    private float experience;
    /**
     * The human entity's current food level
     */
    private int food = 20;
    /**
     * The player's current exhaustion level.
     */
    private float exhaustion;
    /**
     * The player's current saturation level.
     */
    private float saturation;
    /**
     * Whether to perform special scaling of the player's health.
     */
    private boolean healthScaled;
    /**
     * The scale at which to display the player's health.
     */
    private double healthScale = 20;
    /**
     * This player's current time offset.
     */
    private long timeOffset;
    /**
     * Whether the time offset is relative.
     */
    private boolean timeRelative = true;
    /**
     * The player-specific weather, or null for normal weather.
     */
    private WeatherType playerWeather;
    /**
     * The player's compass target.
     */
    private Location compassTarget;
    /**
     * Whether this player's sleeping state is ignored when changing time.
     */
    private boolean sleepingIgnored;
    /**
     * The bed in which the player currently lies
     */
    private GlowBlock bed;
    /**
     * The bed spawn location of a player
     */
    private Location bedSpawn;
    /**
     * Whether to use the bed spawn even if there is no bed block.
     */
    private boolean bedSpawnForced;
    /**
     * The location of the sign the player is currently editing, or null.
     */
    private Location signLocation;
    /**
     * Whether the player is permitted to fly.
     */
    private boolean canFly;
    /**
     * Whether the player is currently flying.
     */
    private boolean flying;
    /**
     * The player's base flight speed.
     */
    private float flySpeed = 0.1f;
    /**
     * The player's base walking speed.
     */
    private float walkSpeed = 0.2f;
    /**
     * The scoreboard the player is currently subscribed to.
     */
    private GlowScoreboard scoreboard;
    /**
     * The player's current title, if any
     */
    private Title currentTitle = new Title("");
    /**
     * The one block the player is currently digging.
     */
    private GlowBlock digging;

    public Location teleportedTo = null;
    /**
     * The one itemstack the player is currently usage and associated time.
     */
    @Getter
    @Setter
    private ItemStack usageItem;
    @Getter
    @Setter
    private long usageTime;

    /**
     * Creates a new player and adds it to the world.
     *
     * @param session The player's session.
     * @param profile The player's profile with name and UUID information.
     * @param reader  The PlayerReader to be used to initialize the player.
     */
    public GlowPlayer(GlowSession session, PlayerProfile profile, PlayerReader reader) {
        super(initLocation(session, reader), profile);
        setBoundingBox(0.6, 1.8);
        this.session = session;

        chunkLock = world.newChunkLock(getName());

        // enable compression if needed
        int compression = session.getServer().getCompressionThreshold();
        if (compression > 0) {
            session.enableCompression(compression);
        }

        // send login response
        session.send(new LoginSuccessMessage(profile.getUniqueId().toString(), profile.getName()));

        switch (session.getVersion()) {
            case GlowServer.LEGACY_PROTOCOL_1_9:
                session.setProtocol(ProtocolType.PLAY_107);
                break;
            case GlowServer.LEGACY_PROTOCOL_1_9_2:
                session.setProtocol(ProtocolType.PLAY_109);
                break;
            default:
                session.setProtocol(ProtocolType.PLAY);
                break;
        }

        // read data from player reader
        hasPlayedBefore = reader.hasPlayedBefore();
        if (hasPlayedBefore) {
            firstPlayed = reader.getFirstPlayed();
            lastPlayed = reader.getLastPlayed();
            bedSpawn = reader.getBedSpawnLocation();
        } else {
            firstPlayed = 0;
            lastPlayed = 0;
        }

        //creates InventoryMonitor to avoid NullPointerException
        invMonitor = new InventoryMonitor(getOpenInventory());
    }

    /**
     * Read the location from a PlayerReader for entity initialization. Will
     * fall back to a reasonable default rather than returning null.
     *
     * @param session The player's session.
     * @param reader  The PlayerReader to get the location from.
     * @return The location to spawn the player.
     */
    private static Location initLocation(GlowSession session, PlayerReader reader) {
        if (reader.hasPlayedBefore()) {
            Location loc = reader.getLocation();
            if (loc != null) {
                return loc;
            }
        }
        return session.getServer().getWorlds().get(0).getSpawnLocation();
    }

    public void join(GlowSession session, PlayerReader reader) {
        // send join game
        // in future, handle hardcore, difficulty, and level type
        String type = world.getWorldType().getName().toLowerCase();
        int gameMode = getGameMode().getValue();
        if (server.isHardcore()) {
            gameMode |= 0x8;
        }
        session.send(new JoinGameMessage(SELF_ID, gameMode, world.getEnvironment().getId(), world.getDifficulty().getValue(), session.getServer().getMaxPlayers(), type, world.getGameRuleMap().getBoolean("reducedDebugInfo")));
        setGameModeDefaults();

        // send server brand and supported plugin channels
        session.send(PluginMessage.fromString("MC|Brand", server.getName()));
        sendSupportedChannels();
        joinTime = System.currentTimeMillis();
        reader.readData(this);
        reader.close();

        // Add player to list of online players
        getServer().setPlayerOnline(this, true);

        // save data back out
        saveData();

        streamBlocks(); // stream the initial set of blocks
        setCompassTarget(world.getSpawnLocation()); // set our compass target
        sendTime();
        sendWeather();
        sendRainDensity();
        sendSkyDarkness();
        sendAbilities();

        scoreboard = server.getScoreboardManager().getMainScoreboard();
        scoreboard.subscribe(this);

        invMonitor = new InventoryMonitor(getOpenInventory());
        updateInventory(); // send inventory contents

        // send initial location
        session.send(new PositionRotationMessage(location));

        if (!server.getResourcePackURL().isEmpty()) {
            setResourcePack(server.getResourcePackURL(), server.getResourcePackHash());
        }
    }


    ////////////////////////////////////////////////////////////////////////////
    // Damages

    @Override
    public String toString() {
        return "GlowPlayer{name=" + getName() + "}";
    }

    @Override
    public void damage(double amount) {
        if (getGameMode().equals(GameMode.CREATIVE)) {
            return;
        }
        damage(amount, DamageCause.CUSTOM);
    }

    @Override
    public void damage(double amount, Entity cause) {
        if (getGameMode().equals(GameMode.CREATIVE)) {
            return;
        }
        super.damage(amount, cause);
        sendHealth();
    }


    ////////////////////////////////////////////////////////////////////////////
    // Internals

    @Override
    public void damage(double amount, DamageCause cause) {
        if (getGameMode().equals(GameMode.CREATIVE) && !cause.equals(DamageCause.VOID)) {
            return;
        }

        // todo: better idea
        double old = getHealth();
        super.damage(amount, cause);
        if (old != getHealth()) addExhaustion(0.3f);
        sendHealth();
    }

    /**
     * Get the network session attached to this player.
     *
     * @return The GlowSession of the player.
     */
    public GlowSession getSession() {
        return session;
    }

    /**
     * Get the join time in milliseconds, to be saved as last played time.
     *
     * @return The player's join time.
     */
    public long getJoinTime() {
        return joinTime;
    }

    /**
     * Destroys this entity by removing it from the world and marking it as not
     * being active.
     */
    @Override
    public void remove() {
        remove(true);
    }

    public void remove(boolean asyncSave) {
        knownChunks.clear();
        chunkLock.clear();
        saveData(asyncSave);
        getInventory().removeViewer(this);
        getInventory().getCraftingInventory().removeViewer(this);
        permissions.clearPermissions();
        getServer().setPlayerOnline(this, false);

        if (scoreboard != null) {
            scoreboard.unsubscribe(this);
            scoreboard = null;
        }
        super.remove();
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public void pulse() {
        super.pulse();

        if (usageItem != null) {
            if (usageItem == getItemInHand()) {
                if (--usageTime == 0) {
                    ItemType item = ItemTable.instance().getItem(usageItem.getType());
                    if (item instanceof ItemFood) {
                        ((ItemFood) item).eat(this, usageItem);
                    }
                }
            } else {
                usageItem = null;
                usageTime = 0;
            }
        }

        if (exhaustion > 4.0f) {
            exhaustion -= 4.0f;

            if (saturation > 0f) {
                saturation = Math.max(saturation - 1f, 0f);
                sendHealth();
            } else if (world.getDifficulty() != Difficulty.PEACEFUL) {
                FoodLevelChangeEvent event = EventFactory.callEvent(new FoodLevelChangeEvent(this, Math.max(food - 1, 0)));
                if (!event.isCancelled()) {
                    food = event.getFoodLevel();
                }
                sendHealth();
            }
        }

        if (getHealth() < getMaxHealth()) {
            if (food > 18 && ticksLived % 80 == 0 || world.getDifficulty() == Difficulty.PEACEFUL) {

                EntityRegainHealthEvent event1 = new EntityRegainHealthEvent(this, 1f, RegainReason.SATIATED);
                EventFactory.callEvent(event1);
                if (!event1.isCancelled()) {
                    setHealth(getHealth() + 1);
                }
                exhaustion = Math.min(exhaustion + 3.0f, 40.0f);

                saturation -= 3;
            }
        }


        if (food == 0 && getHealth() > 1 && ticksLived % 80 == 0) {
            damage(1, DamageCause.STARVATION);
        }

        // stream world
        streamBlocks();
        processBlockChanges();

        // add to playtime
        incrementStatistic(Statistic.PLAY_ONE_TICK);

        // update inventory
        for (InventoryMonitor.Entry entry : invMonitor.getChanges()) {
            sendItemChange(entry.slot, entry.item);
        }

        // send changed metadata
        List<MetadataMap.Entry> changes = metadata.getChanges();
        if (!changes.isEmpty()) {
            session.send(new EntityMetadataMessage(SELF_ID, changes));
        }

        // update or remove entities
        List<Integer> destroyIds = new LinkedList<>();
        for (Iterator<GlowEntity> it = knownEntities.iterator(); it.hasNext(); ) {
            GlowEntity entity = it.next();
            if (isWithinDistance(entity)) {
                entity.createUpdateMessage().forEach(session::send);
            } else {
                destroyIds.add(entity.getEntityId());
                it.remove();
            }
        }
        if (!destroyIds.isEmpty()) {
            session.send(new DestroyEntitiesMessage(destroyIds));
        }

        // add entities
        for (GlowEntity entity : world.getEntityManager()) {
            if (entity != this && isWithinDistance(entity) && !entity.isDead() &&
                    !knownEntities.contains(entity) && !hiddenEntities.contains(entity.getUniqueId())) {
                knownEntities.add(entity);
                entity.createSpawnMessage().forEach(session::send);
            }
        }

        if (vehicleChanged) {
            session.send(new AttachEntityMessage(SELF_ID, vehicle != null ? vehicle.getEntityId() : -1, false));
        }

        getAttributeManager().sendMessages(session);
    }

    /**
     * Process and send pending BlockChangeMessages.
     */
    private void processBlockChanges() {
        List<BlockChangeMessage> messages = new ArrayList<>(blockChanges);
        blockChanges.clear();

        // separate messages by chunk
        // inner map is used to only send one entry for same coordinates
        Map<Key, Map<BlockVector, BlockChangeMessage>> chunks = new HashMap<>();
        for (BlockChangeMessage message : messages) {
            if (message != null) {
                Key key = new Key(message.getX() >> 4, message.getZ() >> 4);
                if (canSeeChunk(key)) {
                    Map<BlockVector, BlockChangeMessage> map = chunks.get(key);
                    if (map == null) {
                        map = new HashMap<>();
                        chunks.put(key, map);
                    }
                    map.put(new BlockVector(message.getX(), message.getY(), message.getZ()), message);
                }
            }
        }

        // send away
        for (Map.Entry<Key, Map<BlockVector, BlockChangeMessage>> entry : chunks.entrySet()) {
            Key key = entry.getKey();
            List<BlockChangeMessage> value = new ArrayList<>(entry.getValue().values());

            if (value.size() == 1) {
                session.send(value.get(0));
            } else if (value.size() > 1) {
                session.send(new MultiBlockChangeMessage(key.getX(), key.getZ(), value));
            }
        }

        // now send post-block-change messages
        List<Message> postMessages = new ArrayList<>(afterBlockChanges);
        afterBlockChanges.clear();
        postMessages.forEach(session::send);
    }

    /**
     * Streams chunks to the player's client.
     */
    private void streamBlocks() {
        Set<Key> previousChunks = new HashSet<>(knownChunks);
        ArrayList<Key> newChunks = new ArrayList<>();

        int centralX = location.getBlockX() >> 4;
        int centralZ = location.getBlockZ() >> 4;

        int radius = Math.min(server.getViewDistance(), 1 + settings.getViewDistance());
        for (int x = centralX - radius; x <= centralX + radius; x++) {
            for (int z = centralZ - radius; z <= centralZ + radius; z++) {
                Key key = new Key(x, z);
                if (knownChunks.contains(key)) {
                    previousChunks.remove(key);
                } else {
                    newChunks.add(key);
                }
            }
        }

        // early end if there's no changes
        if (newChunks.isEmpty() && previousChunks.isEmpty()) {
            return;
        }

        // sort chunks by distance from player - closer chunks sent first
        Collections.sort(newChunks, (a, b) -> {
            double dx = 16 * a.getX() + 8 - location.getX();
            double dz = 16 * a.getZ() + 8 - location.getZ();
            double da = dx * dx + dz * dz;
            dx = 16 * b.getX() + 8 - location.getX();
            dz = 16 * b.getZ() + 8 - location.getZ();
            double db = dx * dx + dz * dz;
            return Double.compare(da, db);
        });

        // populate then send chunks to the player
        // done in two steps so that all the new chunks are finalized before any of them are sent
        // this prevents sending a chunk then immediately sending block changes in it because
        // one of its neighbors has populated

        // first step: force population then acquire lock on each chunk
        for (Key key : newChunks) {
            world.getChunkManager().forcePopulation(key.getX(), key.getZ());
            knownChunks.add(key);
            chunkLock.acquire(key);
        }

        boolean skylight = world.getEnvironment() == Environment.NORMAL;

        for (Key key : newChunks) {
            GlowChunk chunk = world.getChunkAt(key.getX(), key.getZ());
            if (session.getVersion() == GlowServer.LEGACY_PROTOCOL_1_9 || session.getVersion() == GlowServer.LEGACY_PROTOCOL_1_9_2) {
                session.send(chunk.toMessage(skylight).toLegacy());
            } else {
                session.send(chunk.toMessage(skylight));
            }
        }

        // send visible tile entity data
        for (Key key : newChunks) {
            GlowChunk chunk = world.getChunkAt(key.getX(), key.getZ());
            for (TileEntity entity : chunk.getRawTileEntities()) {
                entity.update(this);
            }
        }

        // and remove old chunks
        for (Key key : previousChunks) {
            session.send(new UnloadChunkMessage(key.getX(), key.getZ()));
            knownChunks.remove(key);
            chunkLock.release(key);
        }

        previousChunks.clear();
    }

    /**
     * Spawn the player at the given location after they have already joined.
     * Used for changing worlds and respawning after death.
     *
     * @param location The location to place the player.
     */
    private void spawnAt(Location location) {
        // switch worlds
        GlowWorld oldWorld = world;
        world.getEntityManager().unregister(this);
        world = (GlowWorld) location.getWorld();
        world.getEntityManager().register(this);

        // switch chunk set
        // no need to send chunk unload messages - respawn unloads all chunks
        knownChunks.clear();
        chunkLock.clear();
        chunkLock = world.newChunkLock(getName());

        // spawn into world
        String type = world.getWorldType().getName().toLowerCase();
        session.send(new RespawnMessage(world.getEnvironment().getId(), world.getDifficulty().getValue(), getGameMode().getValue(), type));
        setRawLocation(location, false); // take us to spawn position
        streamBlocks(); // stream blocks
        setCompassTarget(world.getSpawnLocation()); // set our compass target
        session.send(new PositionRotationMessage(location));
        teleportedTo = location.clone();

        sendWeather();
        sendRainDensity();
        sendSkyDarkness();
        sendTime();
        updateInventory();

        // fire world change if needed
        if (oldWorld != world) {
            EventFactory.callEvent(new PlayerChangedWorldEvent(this, oldWorld));
        }
    }

    /**
     * Respawn the player after they have died.
     */
    public void respawn() {
        // restore health
        setHealth(getMaxHealth());

        // determine spawn destination
        boolean spawnAtBed = true;
        Location dest = getBedSpawnLocation();
        if (dest == null) {
            dest = world.getSpawnLocation();
            spawnAtBed = false;
            if (bedSpawn != null) {
                setBedSpawnLocation(null);
                sendMessage("Your home bed was missing or obstructed");
            }
        }

        // fire event and perform spawn
        PlayerRespawnEvent event = new PlayerRespawnEvent(this, dest, spawnAtBed);
        EventFactory.callEvent(event);
        if (event.getRespawnLocation().getWorld().equals(getWorld()) && !knownEntities.isEmpty()) {
            // we need to manually reset all known entities if the player respawns in the same world
            List<Integer> entityIds = new ArrayList<>(knownEntities.size());
            entityIds.addAll(knownEntities.stream().map(GlowEntity::getEntityId).collect(Collectors.toList()));
            session.send(new DestroyEntitiesMessage(entityIds));
            knownEntities.clear();
        }
        spawnAt(event.getRespawnLocation());

        // just in case any items are left in their inventory after they respawn
        updateInventory();
    }

    /**
     * Checks whether the player can see the given chunk.
     *
     * @param chunk The chunk to check.
     * @return If the chunk is known to the player's client.
     */
    public boolean canSeeChunk(Key chunk) {
        return knownChunks.contains(chunk);
    }

    /**
     * Checks whether the player can see the given entity.
     *
     * @param entity The entity to check.
     * @return If the entity is known to the player's client.
     */
    public boolean canSeeEntity(GlowEntity entity) {
        return knownEntities.contains(entity);
    }

    /**
     * Open the sign editor interface at the specified location.
     *
     * @param loc The location to open the editor at
     */
    public void openSignEditor(Location loc) {
        signLocation = loc.clone();
        signLocation.setX(loc.getBlockX());
        signLocation.setY(loc.getBlockY());
        signLocation.setZ(loc.getBlockZ());
        session.send(new SignEditorMessage(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }

    /**
     * Check that the specified location matches that of the last opened sign
     * editor, and if so, clears the last opened sign editor.
     *
     * @param loc The location to check
     * @return Whether the location matched.
     */
    public boolean checkSignLocation(Location loc) {
        if (loc.equals(signLocation)) {
            signLocation = null;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Get a UserListItemMessage entry representing adding this player.
     *
     * @return The entry (action ADD_PLAYER) with this player's information.
     */
    public Entry getUserListEntry() {
        TextMessage displayName = null;
        if (playerListName != null && !playerListName.isEmpty()) {
            displayName = new TextMessage(playerListName);
        }
        return UserListItemMessage.add(getProfile(), getGameMode().getValue(), 0, displayName);
    }

    /**
     * Send a UserListItemMessage to every player that can see this player.
     *
     * @param updateMessage The message to send.
     */
    private void updateUserListEntries(UserListItemMessage updateMessage) {
        server.getRawOnlinePlayers().stream().filter(player -> player.canSee(this)).forEach(player -> player.getSession().send(updateMessage));
    }

    @Override
    public void setVelocity(Vector velocity) {
        PlayerVelocityEvent event = EventFactory.callEvent(new PlayerVelocityEvent(this, velocity));
        if (!event.isCancelled()) {
            velocity = event.getVelocity();
            super.setVelocity(velocity);
            session.send(new EntityVelocityMessage(SELF_ID, velocity));
        }
    }

    /**
     * Set this player's client settings.
     *
     * @param settings The settings to set.
     */
    public void setSettings(ClientSettings settings) {
        this.settings = settings;
       // metadata.set(MetadataIndex.PLAYER_SKIN_FLAGS, settings.getSkinFlags()); // TODO 1.9 - This has been removed
    }

    ////////////////////////////////////////////////////////////////////////////
    // Basic stuff

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> ret = new HashMap<>();
        ret.put("name", getName());
        return ret;
    }

    @Override
    public EntityType getType() {
        return EntityType.PLAYER;
    }

    @Override
    public void setGlowing(boolean b) {

    }

    @Override
    public boolean isGlowing() {
        return false;
    }

    @Override
    public void setInvulnerable(boolean b) {

    }

    @Override
    public boolean isInvulnerable() {
        return false;
    }

    @Override
    public InetSocketAddress getAddress() {
        return session.getAddress();
    }

    @Override
    public boolean isOnline() {
        return session.isActive() && session.isOnline();
    }

    @Override
    public boolean isBanned() {
        return server.getBanList(BanList.Type.NAME).isBanned(getName());
    }

    @Override
    @Deprecated
    public void setBanned(boolean banned) {
        server.getBanList(BanList.Type.NAME).addBan(getName(), null, null, null);
    }

    @Override
    public boolean isWhitelisted() {
        return server.getWhitelist().containsProfile(new PlayerProfile(getName(), getUniqueId()));
    }

    @Override
    public void setWhitelisted(boolean value) {
        if (value) {
            server.getWhitelist().add(this);
        } else {
            server.getWhitelist().remove(new PlayerProfile(getName(), getUniqueId()));
        }
    }

    @Override
    public Player getPlayer() {
        return this;
    }

    @Override
    public boolean hasPlayedBefore() {
        return hasPlayedBefore;
    }

    @Override
    public long getFirstPlayed() {
        return firstPlayed;
    }

    ////////////////////////////////////////////////////////////////////////////
    // HumanEntity overrides

    @Override
    public long getLastPlayed() {
        return lastPlayed;
    }

    @Override
    public boolean isOp() {
        return getServer().getOpsList().containsUUID(getUniqueId());
    }

    @Override
    public void setOp(boolean value) {
        if (value) {
            getServer().getOpsList().add(this);
        } else {
            getServer().getOpsList().remove(new PlayerProfile(getName(), getUniqueId()));
        }
        permissions.recalculatePermissions();
    }

    ////////////////////////////////////////////////////////////////////////////
    // Editable properties

    @Override
    public List<Message> createSpawnMessage() {
        List<Message> result = super.createSpawnMessage();
        if (bed != null) {
            result.add(new UseBedMessage(getEntityId(), bed.getX(), bed.getY(), bed.getZ()));
        }
        return result;
    }

    @Override
    public String getDisplayName() {
        if (displayName != null) {
            return displayName;
        }
        GlowTeam team = (GlowTeam) getScoreboard().getPlayerTeam(this);
        if (team != null) {
            return team.getPlayerDisplayName(getName());
        }
        return getName();
    }

    @Override
    public void setDisplayName(String name) {
        displayName = name;
    }

    @Override
    public String getPlayerListName() {
        return playerListName == null || playerListName.isEmpty() ? getName() : playerListName;
    }

    @Override
    public void setPlayerListName(String name) {
        // update state
        playerListName = name;

        // send update message
        TextMessage displayName = null;
        if (playerListName != null && !playerListName.isEmpty()) {
            displayName = new TextMessage(playerListName);
        }
        updateUserListEntries(UserListItemMessage.displayNameOne(getUniqueId(), displayName));
    }

    @Override
    public Location getCompassTarget() {
        return compassTarget;
    }

    @Override
    public void setCompassTarget(Location loc) {
        compassTarget = loc;
        session.send(new SpawnPositionMessage(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }

    /**
     * Returns whether the player spawns at their bed even if there is no bed block.
     *
     * @return Whether the player is forced to spawn at their bed.
     */
    public boolean isBedSpawnForced() {
        return bedSpawnForced;
    }

    @Override
    public Location getBedSpawnLocation() {
        if (bedSpawn == null) {
            return null;
        }

        // Find head of bed
        GlowBlock block = (GlowBlock) bedSpawn.getBlock();
        GlowBlock head = BlockBed.getHead(block);
        GlowBlock foot = BlockBed.getFoot(block);
        // If there is a bed, try to find an empty spot next to the bed
        if (head != null && head.getType() == Material.BED_BLOCK) {
            Block spawn = BlockBed.getExitLocation(head, foot);
            return spawn == null ? null : spawn.getLocation().add(0.5, 0.1, 0.5);
        } else {
            // If there is no bed and spawning is forced and there is space to spawn
            if (bedSpawnForced) {
                Material bottom = head.getType();
                Material top = head.getRelative(BlockFace.UP).getType();
                // Do not check floor when forcing spawn
                if (BlockBed.isValidSpawn(bottom) && BlockBed.isValidSpawn(top)) {
                    return bedSpawn.clone().add(0.5, 0.1, 0.5); // No blocks are blocking the spawn
                }
            }
            return null;
        }
    }

    @Override
    public void setBedSpawnLocation(Location bedSpawn) {
        setBedSpawnLocation(bedSpawn, false);
    }

    @Override
    public void setBedSpawnLocation(Location location, boolean force) {
        bedSpawn = location;
        bedSpawnForced = force;
    }

    @Override
    public boolean isSleepingIgnored() {
        return sleepingIgnored;
    }

    @Override
    public void setSleepingIgnored(boolean isSleeping) {
        sleepingIgnored = isSleeping;
    }

    @Override
    public void setGameMode(GameMode mode) {
        if (getGameMode() != mode) {
            PlayerGameModeChangeEvent event = new PlayerGameModeChangeEvent(this, mode);
            if (EventFactory.callEvent(event).isCancelled()) {
                return;
            }

            super.setGameMode(mode);
            updateUserListEntries(UserListItemMessage.gameModeOne(getUniqueId(), mode.getValue()));
            session.send(new StateChangeMessage(Reason.GAMEMODE, mode.getValue()));
        }
        setGameModeDefaults();
    }

    ////////////////////////////////////////////////////////////////////////////
    // Entity status

    private void setGameModeDefaults() {
        GameMode mode = getGameMode();
        setAllowFlight(mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
        metadata.setBit(MetadataIndex.STATUS, StatusFlags.INVISIBLE, mode == GameMode.SPECTATOR);
    }

    @Override
    public boolean isSneaking() {
        return metadata.getBit(MetadataIndex.STATUS, StatusFlags.SNEAKING);
    }

    @Override
    public void setSneaking(boolean sneak) {
        if (EventFactory.callEvent(new PlayerToggleSneakEvent(this, sneak)).isCancelled()) {
            return;
        }

        metadata.setBit(MetadataIndex.STATUS, StatusFlags.SNEAKING, sneak);
    }

    @Override
    public boolean isSprinting() {
        return metadata.getBit(MetadataIndex.STATUS, StatusFlags.SPRINTING);
    }

    @Override
    public void setSprinting(boolean sprinting) {
        if (EventFactory.callEvent(new PlayerToggleSprintEvent(this, sprinting)).isCancelled()) {
            return;
        }

        metadata.setBit(MetadataIndex.STATUS, StatusFlags.SPRINTING, sprinting);
    }

    @Override
    public double getEyeHeight() {
        return getEyeHeight(false);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Player capabilities

    @Override
    public double getEyeHeight(boolean ignoreSneaking) {
        // Height of player's eyes above feet. Matches CraftBukkit.
        if (ignoreSneaking || !isSneaking()) {
            return 1.62;
        } else {
            return 1.54;
        }
    }

    @Override
    public boolean isGliding() {
        return false;
    }

    @Override
    public void setGliding(boolean b) {

    }

    @Override
    public void setAI(boolean b) {

    }

    @Override
    public boolean hasAI() {
        return false;
    }

    @Override
    public void setCollidable(boolean b) {

    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public int getArrowsStuck() {
        return 0;
    }

    @Override
    public void setArrowsStuck(int i) {

    }

    @Override
    public boolean getAllowFlight() {
        return canFly;
    }

    @Override
    public void setAllowFlight(boolean flight) {
        canFly = flight;
        if (!canFly) flying = false;
        sendAbilities();
    }

    @Override
    public boolean isFlying() {
        return flying;
    }

    @Override
    public void setFlying(boolean value) {
        flying = value && canFly;
        sendAbilities();
    }

    @Override
    public float getFlySpeed() {
        return flySpeed;
    }

    @Override
    public void setFlySpeed(float value) throws IllegalArgumentException {
        flySpeed = value;
        sendAbilities();
    }

    @Override
    public float getWalkSpeed() {
        return walkSpeed;
    }

    @Override
    public void setWalkSpeed(float value) throws IllegalArgumentException {
        walkSpeed = value;
        sendAbilities();
    }

    ////////////////////////////////////////////////////////////////////////////
    // Experience and levelling

    private void sendAbilities() {
        boolean creative = getGameMode() == GameMode.CREATIVE;
        int flags = (creative ? 8 : 0) | (canFly ? 4 : 0) | (flying ? 2 : 0) | (creative ? 1 : 0);
        // division is conversion from Bukkit to MC units
        session.send(new PlayerAbilitiesMessage(flags, flySpeed / 2f, walkSpeed / 2f));
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public void setLevel(int level) {
        this.level = Math.max(level, 0);
        sendExperience();
    }

    @Override
    public int getTotalExperience() {
        return totalExperience;
    }

    @Override
    public void setTotalExperience(int exp) {
        totalExperience = Math.max(exp, 0);
        sendExperience();
    }

    @Override
    public void giveExp(int xp) {
        totalExperience += xp;

        // gradually award levels based on xp points
        float value = 1.0f / getExpToLevel();
        for (int i = 0; i < xp; ++i) {
            experience += value;
            if (experience >= 1) {
                experience -= 1;
                value = 1.0f / getExpToLevel(++level);
            }
        }
        sendExperience();
    }

    @Override
    public float getExp() {
        return experience;
    }

    @Override
    public void setExp(float percentToLevel) {
        experience = Math.min(Math.max(percentToLevel, 0), 1);
        sendExperience();
    }

    @Override
    public int getExpToLevel() {
        return getExpToLevel(level);
    }

    private int getExpToLevel(int level) {
        if (level >= 30) {
            return 62 + (level - 30) * 7;
        } else if (level >= 15) {
            return 17 + (level - 15) * 3;
        } else {
            return 17;
        }
    }

    @Override
    public void giveExpLevels(int amount) {
        setLevel(getLevel() + amount);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Health and food handling

    private void sendExperience() {
        session.send(new ExperienceMessage(getExp(), getLevel(), getTotalExperience()));
    }

    @Override
    public void setHealth(double health) {
        super.setHealth(health);
        sendHealth();
    }

    @Override
    public void setMaxHealth(double health) {
        super.setMaxHealth(health);
        sendHealth();
    }

    @Override
    public boolean isHealthScaled() {
        return healthScaled;
    }

    @Override
    public void setHealthScaled(boolean scale) {
        healthScaled = scale;
        sendHealth();
    }

    @Override
    public double getHealthScale() {
        return healthScale;
    }

    @Override
    public void setHealthScale(double scale) throws IllegalArgumentException {
        healthScaled = true;
        healthScale = scale;
        sendHealth();
    }

    @Override
    public Entity getSpectatorTarget() {
        return null;
    }

    @Override
    public void setSpectatorTarget(Entity entity) {

    }

    @Override
    public void sendTitle(String title, String subtitle) {

    }

    public void setFoodLevelAndSaturation(int food, float saturation) {
        this.food = Math.max(Math.min(food, 20), 0);
        this.saturation = Math.min(this.saturation + food * saturation * 2.0F, this.food);
        sendHealth();
    }

    @Override
    public int getFoodLevel() {
        return food;
    }

    @Override
    public void setFoodLevel(int food) {
        this.food = Math.min(food, 20);
        sendHealth();
    }

    private boolean shouldCalculateExhaustion() {
        return getGameMode() == GameMode.SURVIVAL | getGameMode() == GameMode.ADVENTURE;
    }

    // todo: effects
    // todo: swim
    // todo: jump
    // todo: food poisioning
    // todo: jump and sprint
    public void addExhaustion(float exhaustion) {
        if (shouldCalculateExhaustion()) {
            this.exhaustion = Math.min(this.exhaustion + exhaustion, 40f);
        }
    }

    public void addMoveExhaustion(Location move) {
        if (shouldCalculateExhaustion() && !teleported) {
            double distanceSquared = location.distanceSquared(move);
            if (distanceSquared > 0) { // update packet and rotation
                double distance = Math.sqrt(distanceSquared);
                if (isSprinting()) {
                    addExhaustion((float) (0.1f * distance));
                } else {
                    addExhaustion((float) (0.01f * distance));
                }
            }
        }
    }

    @Override
    public float getExhaustion() {
        return exhaustion;
    }

    @Override
    public void setExhaustion(float value) {
        exhaustion = value;
    }

    @Override
    public float getSaturation() {
        return saturation;
    }

    @Override
    public void setSaturation(float value) {
        saturation = Math.min(value, food);
        sendHealth();
    }

    ////////////////////////////////////////////////////////////////////////////
    // Actions

    private void sendHealth() {
        float finalHealth = (float) (getHealth() / getMaxHealth() * getHealthScale());
        session.send(new HealthMessage(finalHealth, getFoodLevel(), getSaturation()));
    }

    /**
     * Teleport the player.
     *
     * @param location The destination to teleport to.
     * @return Whether the teleport was a success.
     */
    @Override
    public boolean teleport(Location location) {
        return teleport(location, TeleportCause.UNKNOWN);
    }

    @Override
    public boolean teleport(Location location, TeleportCause cause) {
        checkNotNull(location, "location cannot be null");
        checkNotNull(location.getWorld(), "location's world cannot be null");
        checkNotNull(cause, "cause cannot be null");

        if (this.location != null && this.location.getWorld() != null) {
            PlayerTeleportEvent event = new PlayerTeleportEvent(this, this.location, location, cause);
            if (EventFactory.callEvent(event).isCancelled()) {
                return false;
            }
            location = event.getTo();
        }

        if (location.getWorld() != world) {
            spawnAt(location);
        } else {

            world.getEntityManager().move(this, location);
            //Position.copyLocation(location, this.previousLocation);
            //Position.copyLocation(location, this.location);
            session.send(new PositionRotationMessage(location));
            teleportedTo = location.clone();
        }

        teleportedTo = location.clone();
        return true;
    }

    public void endTeleport() {
        Position.copyLocation(teleportedTo, location);
        teleportedTo = null;
        teleported = true;
    }

    @Override
    protected boolean teleportToSpawn() {
        Location target = getBedSpawnLocation();
        if (target == null) {
            target = server.getWorlds().get(0).getSpawnLocation();
        }

        PlayerPortalEvent event = EventFactory.callEvent(new PlayerPortalEvent(this, location.clone(), target, null));
        if (event.isCancelled()) {
            return false;
        }
        target = event.getTo();

        spawnAt(target);
        teleported = true;

        awardAchievement(Achievement.THE_END, false);
        return true;
    }

    @Override
    protected boolean teleportToEnd() {
        if (!server.getAllowEnd()) {
            return false;
        }
        Location target = null;
        for (World world : server.getWorlds()) {
            if (world.getEnvironment() == Environment.THE_END) {
                target = world.getSpawnLocation();
                break;
            }
        }
        if (target == null) {
            return false;
        }

        PlayerPortalEvent event = EventFactory.callEvent(new PlayerPortalEvent(this, location.clone(), target, null));
        if (event.isCancelled()) {
            return false;
        }
        target = event.getTo();

        spawnAt(target);
        teleported = true;

        awardAchievement(Achievement.END_PORTAL, false);
        return true;
    }

    /**
     * This player enters the specified bed and is marked as sleeping.
     *
     * @param block the bed
     */
    public void enterBed(GlowBlock block) {
        checkNotNull(block, "Bed block cannot be null");
        Preconditions.checkState(bed == null, "Player already in bed");

        GlowBlock head = BlockBed.getHead(block);
        GlowBlock foot = BlockBed.getFoot(block);
        if (EventFactory.callEvent(new PlayerBedEnterEvent(this, head)).isCancelled()) {
            return;
        }

        // Occupy the bed
        BlockBed.setOccupied(head, foot, true);
        bed = head;
        sleeping = true;
        setRawLocation(head.getLocation(), false);

        getSession().send(new UseBedMessage(SELF_ID, head.getX(), head.getY(), head.getZ()));
        UseBedMessage msg = new UseBedMessage(getEntityId(), head.getX(), head.getY(), head.getZ());
        world.getRawPlayers().stream().filter(p -> p != this && p.canSeeEntity(this)).forEach(p -> p.getSession().send(msg));
    }

    /**
     * This player leaves their bed causing them to quit sleeping.
     *
     * @param setSpawn Whether to set the bed spawn of the player
     */
    public void leaveBed(boolean setSpawn) {
        Preconditions.checkState(bed != null, "Player is not in bed");
        GlowBlock head = BlockBed.getHead(bed);
        GlowBlock foot = BlockBed.getFoot(bed);

        // Determine exit location
        Block exitBlock = BlockBed.getExitLocation(head, foot);
        if (exitBlock == null) { // If no empty blocks were found fallback to block above bed
            exitBlock = head.getRelative(BlockFace.UP);
        }
        Location exitLocation = exitBlock.getLocation().add(0.5, 0.1, 0.5); // Use center of block

        // Set their spawn (normally omitted if their bed gets destroyed instead of them leaving it)
        if (setSpawn) {
            setBedSpawnLocation(head.getLocation());
        }

        // Empty the bed
        BlockBed.setOccupied(head, foot, false);
        bed = null;
        sleeping = false;

        // And eject the player
        setRawLocation(exitLocation, false);
        teleported = true;

        // Call event
        EventFactory.callEvent(new PlayerBedLeaveEvent(this, head));

        getSession().send(new AnimateEntityMessage(SELF_ID, AnimateEntityMessage.OUT_LEAVE_BED));
        AnimateEntityMessage msg = new AnimateEntityMessage(getEntityId(), AnimateEntityMessage.OUT_LEAVE_BED);
        world.getRawPlayers().stream().filter(p -> p != this && p.canSeeEntity(this)).forEach(p -> p.getSession().send(msg));
    }

    @Override
    public void sendMessage(String message) {
        sendRawMessage(message);
    }

    @Override
    public void sendMessage(String[] messages) {
        for (String line : messages) {
            sendMessage(line);
        }
    }

    @Override
    public void sendRawMessage(String message) {
        // old-style formatting to json conversion is in TextMessage
        session.send(new ChatMessage(message));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void sendActionBarMessage(String message) {
        // "old" formatting workaround because apparently "new" styling doesn't work as of 01/18/2015
        JSONObject json = new JSONObject();
        json.put("text", message);
        session.send(new ChatMessage(new TextMessage(json), 2));
    }

    @Override
    public void spawnParticle(Particle particle, Location location, int i) {

    }

    @Override
    public void spawnParticle(Particle particle, double v, double v1, double v2, int i) {

    }

    @Override
    public <T> void spawnParticle(Particle particle, Location location, int i, T t) {

    }

    @Override
    public <T> void spawnParticle(Particle particle, double v, double v1, double v2, int i, T t) {

    }

    @Override
    public void spawnParticle(Particle particle, Location location, int i, double v, double v1, double v2) {

    }

    @Override
    public void spawnParticle(Particle particle, double v, double v1, double v2, int i, double v3, double v4, double v5) {

    }

    @Override
    public <T> void spawnParticle(Particle particle, Location location, int i, double v, double v1, double v2, T t) {

    }

    @Override
    public <T> void spawnParticle(Particle particle, double v, double v1, double v2, int i, double v3, double v4, double v5, T t) {

    }

    @Override
    public void spawnParticle(Particle particle, Location location, int i, double v, double v1, double v2, double v3) {

    }

    @Override
    public void spawnParticle(Particle particle, double v, double v1, double v2, int i, double v3, double v4, double v5, double v6) {

    }

    @Override
    public <T> void spawnParticle(Particle particle, Location location, int i, double v, double v1, double v2, double v3, T t) {

    }

    @Override
    public <T> void spawnParticle(Particle particle, double v, double v1, double v2, int i, double v3, double v4, double v5, double v6, T t) {

    }

    @Override
    public boolean getAffectsSpawning() {
        return false;
    }

    @Override
    public void setAffectsSpawning(boolean affects) {

    }

    @Override
    public int getViewDistance() {
        return 0;
    }

    @Override
    public void setViewDistance(int viewDistance) {

    }

    @Override
    public void kickPlayer(String message) {
        remove(true);
        session.disconnect(message == null ? "" : message);
    }

    @Override
    public boolean performCommand(String command) {
        return getServer().dispatchCommand(this, command);
    }

    @Override
    public void chat(String text) {
        chat(text, false);
    }

    /**
     * Says a message (or runs a command).
     *
     * @param text  message sent by the player.
     * @param async whether the message was received asynchronously.
     */
    public void chat(String text, boolean async) {
        if (text.startsWith("/")) {
            Runnable task = () -> {
                server.getLogger().info(getName() + " issued command: " + text);
                try {
                    PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(this, text);
                    if (!EventFactory.callEvent(event).isCancelled()) {
                        server.dispatchCommand(this, event.getMessage().substring(1));
                    }
                } catch (Exception ex) {
                    sendMessage(ChatColor.RED + "An internal error occurred while executing your command.");
                    server.getLogger().log(Level.SEVERE, "Exception while executing command: " + text, ex);
                }
            };

            // if async is true, this task should happen synchronously
            // otherwise, we're sync already, it can happen here
            if (async) {
                server.getScheduler().runTask(null, task);
            } else {
                task.run();
            }
        } else {
            AsyncPlayerChatEvent event = EventFactory.onPlayerChat(async, this, text);
            if (event.isCancelled()) {
                return;
            }

            String message = String.format(event.getFormat(), getDisplayName(), event.getMessage());
            getServer().getLogger().info(message);
            for (Player recipient : event.getRecipients()) {
                recipient.sendMessage(message);
            }
        }
    }

    @Override
    public void saveData() {
        saveData(true);
    }

    public void saveData(boolean async) {
        if (async) {
            new Thread() {
                @Override
                public void run() {
                    server.getPlayerDataService().writeData(GlowPlayer.this);
                }
            }.start();
        } else {
            server.getPlayerDataService().writeData(this);
        }
    }

    @Override
    public void loadData() {
        server.getPlayerDataService().readData(this);
    }

    @Override
    @Deprecated
    public void setTexturePack(String url) {
        setResourcePack(url);
    }

    @Override
    public void setResourcePack(String url) {
        setResourcePack(url, "");
    }

    ////////////////////////////////////////////////////////////////////////////
    // Effect and data transmission

    @Override
    public void setResourcePack(String url, String hash) {
        session.send(new ResourcePackSendMessage(url, hash));
    }

    @Override
    public PlayerResourcePackStatusEvent.Status getResourcePackStatus() {
        return null;
    }

    @Override
    public String getResourcePackHash() {
        return null;
    }

    @Override
    public boolean hasResourcePack() {
        return false;
    }

    @Override
    public void playNote(Location loc, Instrument instrument, Note note) {
        Sound sound;
        switch (instrument) {
            case PIANO:
                sound = Sound.BLOCK_NOTE_HARP;
                break;
            case BASS_DRUM:
                sound = Sound.BLOCK_NOTE_BASEDRUM;
                break;
            case SNARE_DRUM:
                sound = Sound.BLOCK_NOTE_SNARE;
                break;
            case STICKS:
                sound = Sound.BLOCK_NOTE_HAT;
                break;
            case BASS_GUITAR:
                sound = Sound.BLOCK_NOTE_BASS;
                break;
            default:
                throw new IllegalArgumentException("Invalid instrument");
        }
        playSound(loc, sound, 3.0f, note.getId());
    }

    @Override
    public void playNote(Location loc, byte instrument, byte note) {
        playNote(loc, Instrument.getByType(instrument), new Note(note));
    }

    @Override
    public void playEffect(Location loc, Effect effect, int data) {
        int id = effect.getId();
        boolean ignoreDistance = effect == Effect.WITHER_SPAWN || effect == Effect.ENDERDRAGON_DIE;
        session.send(new PlayEffectMessage(id, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), data, ignoreDistance));
    }

    private void playEffect_(Location loc, Effect effect, int data) { // fix name collision with Spigot below
        playEffect(loc, effect, data);
    }

    @Override
    public <T> void playEffect(Location loc, Effect effect, T data) {
        playEffect(loc, effect, GlowEffect.getDataValue(effect, data));
    }

    @Override
    public void playSound(Location location, Sound sound, float volume, float pitch) {
        playSound(location, GlowSound.getName(sound), volume, pitch);
    }

    @Override
    public void playSound(Location location, String sound, float volume, float pitch) {
        if (location == null || sound == null) return;
        // the loss of precision here is a bit unfortunate but it's what CraftBukkit does
        double x = location.getBlockX() + 0.5;
        double y = location.getBlockY() + 0.5;
        double z = location.getBlockZ() + 0.5;
        session.send(new NamedSoundEffectMessage(sound, SoundCategory.MASTER, x, y, z, volume, pitch)); //TODO: Put the real category
    }

    @Override
    public Player.Spigot spigot() {
        return spigot;
    }

    @Override
    public Location getOrigin() {
        return null;
    }


    //@Override
    public void showParticle(Location loc, Effect particle, MaterialData material, float offsetX, float offsetY, float offsetZ, float speed, int amount) {
        if (location == null || particle == null || particle.getType() != Type.PARTICLE) return;

        int id = GlowParticle.getId(particle);
        boolean longDistance = GlowParticle.isLongDistance(particle);
        float x = (float) loc.getX();
        float y = (float) loc.getY();
        float z = (float) loc.getZ();
        int[] extData = GlowParticle.getData(particle, material);
        session.send(new PlayParticleMessage(id, longDistance, x, y, z, offsetX, offsetY, offsetZ, speed, amount, extData));
    }

    @Override
    public void sendBlockChange(Location loc, Material material, byte data) {
        sendBlockChange(loc, material.getId(), data);
    }

    @Override
    public void sendBlockChange(Location loc, int material, byte data) {
        sendBlockChange(new BlockChangeMessage(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), material, data));
    }

    public void sendBlockChange(BlockChangeMessage message) {
        // only send message if the chunk is within visible range
        Key key = new Key(message.getX() >> 4, message.getZ() >> 4);
        if (canSeeChunk(key)) {
            blockChanges.add(message);
        }
    }

    @Override
    public boolean sendChunkChange(Location loc, int sx, int sy, int sz, byte[] data) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void sendSignChange(Location location, String[] lines) throws IllegalArgumentException {
        checkNotNull(location, "location cannot be null");
        checkNotNull(lines, "lines cannot be null");
        checkArgument(lines.length == 4, "lines.length must equal 4");

        afterBlockChanges.add(UpdateSignMessage.fromPlainText(location.getBlockX(), location.getBlockY(), location.getBlockZ(), lines));
    }

    /**
     * Send a sign change, similar to {@link #sendSignChange(Location, String[])},
     * but using complete TextMessages instead of strings.
     *
     * @param location the location of the sign
     * @param lines    the new text on the sign or null to clear it
     * @throws IllegalArgumentException if location is null
     * @throws IllegalArgumentException if lines is non-null and has a length less than 4
     */
    public void sendSignChange(TESign sign, Location location, TextMessage[] lines) throws IllegalArgumentException {
        checkNotNull(location, "location cannot be null");
        checkNotNull(lines, "lines cannot be null");
        checkArgument(lines.length == 4, "lines.length must equal 4");

        if (session.getProtocolType() == ProtocolType.PLAY) {
            CompoundTag tag = new CompoundTag();
            sign.saveNbt(tag);
            afterBlockChanges.add(new UpdateBlockEntityMessage(location.getBlockX(), location.getBlockY(), location.getBlockZ(), GlowBlockEntity.SIGN.getValue(), tag));
        } else {
            afterBlockChanges.add(new UpdateSignMessage(location.getBlockX(), location.getBlockY(), location.getBlockZ(), lines));
        }
    }

    /**
     * Send a block entity change to the given location.
     *
     * @param location The location of the block entity.
     * @param type     The type of block entity being sent.
     * @param nbt      The NBT structure to send to the client.
     */
    public void sendBlockEntityChange(Location location, GlowBlockEntity type, CompoundTag nbt) {
        checkNotNull(location, "Location cannot be null");
        checkNotNull(type, "Type cannot be null");
        checkNotNull(nbt, "NBT cannot be null");

        afterBlockChanges.add(new UpdateBlockEntityMessage(location.getBlockX(), location.getBlockY(), location.getBlockZ(), type.getValue(), nbt));
    }

    @Override
    public void sendMap(MapView map) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void sendMessage(BaseComponent component) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void sendMessage(BaseComponent... components) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setPlayerListHeaderFooter(BaseComponent[] header, BaseComponent[] footer) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setPlayerListHeaderFooter(BaseComponent header, BaseComponent footer) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setTitleTimes(int fadeInTicks, int stayTicks, int fadeOutTicks) {
        currentTitle = new Title(currentTitle.getTitle(), currentTitle.getSubtitle(), fadeInTicks, stayTicks, fadeOutTicks);
    }

    @Override
    public void setSubtitle(BaseComponent[] subtitle) {
        currentTitle = new Title(currentTitle.getTitle(), subtitle, currentTitle.getFadeIn(), currentTitle.getStay(), currentTitle.getFadeOut());
    }

    @Override
    public void setSubtitle(BaseComponent subtitle) {
        currentTitle = new Title(currentTitle.getTitle(), new BaseComponent[]{subtitle}, currentTitle.getFadeIn(), currentTitle.getStay(), currentTitle.getFadeOut());
    }

    @Override
    public void showTitle(BaseComponent[] title) {
        sendTitle(new Title(title));
    }

    @Override
    public void showTitle(BaseComponent title) {
        sendTitle(new Title(title));
    }

    @Override
    public void showTitle(BaseComponent[] title, BaseComponent[] subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        sendTitle(new Title(title, subtitle, fadeInTicks, stayTicks, fadeOutTicks));
    }

    @Override
    public void showTitle(BaseComponent title, BaseComponent subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        sendTitle(new Title(title, subtitle, fadeInTicks, stayTicks, fadeOutTicks));
    }

    @Override
    public void sendTitle(Title title) {
        session.sendAll(TitleMessage.fromTitle(title));
    }

    @Override
    public void updateTitle(Title title) {
        sendTitle(title); // TODO: update existing title instead of sending a new title
    }

    @Override
    public void hideTitle() {
        currentTitle = new Title("");
        session.send(new TitleMessage(Action.CLEAR));
    }

    ////////////////////////////////////////////////////////////////////////////
    // Achievements and statistics

    @Override
    public boolean hasAchievement(Achievement achievement) {
        return stats.hasAchievement(achievement);
    }

    @Override
    public void awardAchievement(Achievement achievement) {
        awardAchievement(achievement, true);
    }

    /**
     * Awards the given achievement if the player already has the parent achievement,
     * otherwise does nothing. If {@code awardParents} is true, award the player all
     * parent achievements and the given achievement, making this method equivalent
     * to {@link #awardAchievement(Achievement)}.
     *
     * @param achievement  the achievement to award.
     * @param awardParents whether parent achievements should be awarded.
     * @return {@code true} if the achievement was awarded, {@code false} otherwise
     */
    public boolean awardAchievement(Achievement achievement, boolean awardParents) {
        if (hasAchievement(achievement)) return false;

        Achievement parent = achievement.getParent();
        if (parent != null && !hasAchievement(parent)) {
            if (!awardParents || !awardAchievement(parent, true)) {
                // does not have or failed to award required parent achievement
                return false;
            }
        }

        PlayerAchievementAwardedEvent event = new PlayerAchievementAwardedEvent(this, achievement);
        if (EventFactory.callEvent(event).isCancelled()) {
            return false; // event was cancelled
        }

        stats.setAchievement(achievement, true);
        sendAchievement(achievement, true);

        if (server.getAnnounceAchievements()) {
            // todo: make message fancier (hover, translated names)
            server.broadcastMessage(getName() + " has just earned the achievement " + ChatColor.GREEN + "[" + GlowAchievement.getFancyName(achievement) + "]");
        }
        return true;
    }

    @Override
    public void removeAchievement(Achievement achievement) {
        if (!hasAchievement(achievement)) return;

        stats.setAchievement(achievement, false);
        sendAchievement(achievement, false);
    }

    private void sendAchievement(Achievement achievement, boolean has) {
        Map<String, Integer> values = new HashMap<>();
        values.put(GlowAchievement.getName(achievement), has ? 1 : 0);
        session.send(new StatisticMessage(values));
    }

    @Override
    public int getStatistic(Statistic statistic) throws IllegalArgumentException {
        return stats.get(statistic);
    }

    @Override
    public int getStatistic(Statistic statistic, Material material) throws IllegalArgumentException {
        return stats.get(statistic, material);
    }

    @Override
    public int getStatistic(Statistic statistic, EntityType entityType) throws IllegalArgumentException {
        return stats.get(statistic, entityType);
    }

    @Override
    public void setStatistic(Statistic statistic, int newValue) throws IllegalArgumentException {
        stats.set(statistic, newValue);
    }

    @Override
    public void setStatistic(Statistic statistic, Material material, int newValue) throws IllegalArgumentException {
        stats.set(statistic, material, newValue);
    }

    @Override
    public void setStatistic(Statistic statistic, EntityType entityType, int newValue) {
        stats.set(statistic, entityType, newValue);
    }

    @Override
    public void incrementStatistic(Statistic statistic) {
        stats.add(statistic, 1);
    }

    @Override
    public void incrementStatistic(Statistic statistic, int amount) {
        stats.add(statistic, amount);
    }

    @Override
    public void incrementStatistic(Statistic statistic, Material material) {
        stats.add(statistic, material, 1);
    }

    @Override
    public void incrementStatistic(Statistic statistic, Material material, int amount) {
        stats.add(statistic, material, amount);
    }

    @Override
    public void incrementStatistic(Statistic statistic, EntityType entityType) throws IllegalArgumentException {
        stats.add(statistic, entityType, 1);
    }

    @Override
    public void incrementStatistic(Statistic statistic, EntityType entityType, int amount) throws IllegalArgumentException {
        stats.add(statistic, entityType, amount);
    }

    @Override
    public void decrementStatistic(Statistic statistic) throws IllegalArgumentException {
        stats.add(statistic, -1);
    }

    @Override
    public void decrementStatistic(Statistic statistic, int amount) throws IllegalArgumentException {
        stats.add(statistic, -amount);
    }

    @Override
    public void decrementStatistic(Statistic statistic, Material material) throws IllegalArgumentException {
        stats.add(statistic, material, -1);
    }

    @Override
    public void decrementStatistic(Statistic statistic, Material material, int amount) throws IllegalArgumentException {
        stats.add(statistic, material, -amount);
    }

    @Override
    public void decrementStatistic(Statistic statistic, EntityType entityType) throws IllegalArgumentException {
        stats.add(statistic, entityType, -1);
    }

    @Override
    public void decrementStatistic(Statistic statistic, EntityType entityType, int amount) {
        stats.add(statistic, entityType, -amount);
    }

    public void sendStats() {
        session.send(stats.toMessage());
    }

    ////////////////////////////////////////////////////////////////////////////
    // Inventory

    @Override
    public void updateInventory() {
        session.send(new SetWindowContentsMessage(invMonitor.getId(), invMonitor.getContents()));
    }

    public void sendItemChange(int slot, ItemStack item) {
        session.send(new SetWindowSlotMessage(invMonitor.getId(), slot, item));
    }

    @Override
    public void setItemOnCursor(ItemStack item) {
        super.setItemOnCursor(item);
        session.send(new SetWindowSlotMessage(-1, -1, item));
    }

    @Override
    public MainHand getMainHand() {
        return null;
    }

    @Override
    public boolean setWindowProperty(Property prop, int value) {
        if (!super.setWindowProperty(prop, value)) return false;
        session.send(new WindowPropertyMessage(invMonitor.getId(), prop.getId(), value));
        return true;
    }

    @Override
    public void openInventory(InventoryView view) {
        session.send(new CloseWindowMessage(invMonitor.getId()));

        super.openInventory(view);

        invMonitor = new InventoryMonitor(getOpenInventory());
        int viewId = invMonitor.getId();
        if (viewId != 0) {
            String title = view.getTitle();
            boolean defaultTitle = view.getType().getDefaultTitle().equals(title);
            if (view.getTopInventory() instanceof PlayerInventory && defaultTitle) {
                title = ((PlayerInventory) view.getTopInventory()).getHolder().getName();
            }
            Message open = new OpenWindowMessage(viewId, invMonitor.getType(), title, ((GlowInventory) view.getTopInventory()).getRawSlots());
            session.send(open);
        }

        updateInventory();
    }

    @Override
    public InventoryView openMerchant(Villager villager, boolean b) {
        return null;
    }

    @Override
    public GlowItem drop(ItemStack stack) {
        GlowItem dropping = super.drop(stack);
        if (dropping != null) {
            PlayerDropItemEvent event = new PlayerDropItemEvent(this, dropping);
            EventFactory.callEvent(event);
            if (event.isCancelled()) {
                dropping.remove();
                dropping = null;
            }
        }
        return dropping;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Player-specific time and weather

    @Override
    public void setPlayerTime(long time, boolean relative) {
        timeOffset = (time % GlowWorld.DAY_LENGTH + GlowWorld.DAY_LENGTH) % GlowWorld.DAY_LENGTH;
        timeRelative = relative;
        sendTime();
    }

    @Override
    public long getPlayerTime() {
        if (timeRelative) {
            // add timeOffset ticks to current time
            return (world.getTime() + timeOffset) % GlowWorld.DAY_LENGTH;
        } else {
            // return time offset
            return timeOffset;
        }
    }

    @Override
    public long getPlayerTimeOffset() {
        return timeOffset;
    }

    @Override
    public boolean isPlayerTimeRelative() {
        return timeRelative;
    }

    @Override
    public void resetPlayerTime() {
        setPlayerTime(0, true);
    }

    public void sendTime() {
        long time = getPlayerTime();
        if (!timeRelative || !world.getGameRuleMap().getBoolean("doDaylightCycle")) {
            time *= -1; // negative value indicates fixed time
        }
        session.send(new TimeMessage(world.getFullTime(), time));
    }

    @Override
    public WeatherType getPlayerWeather() {
        return playerWeather;
    }

    @Override
    public void setPlayerWeather(WeatherType type) {
        playerWeather = type;
        sendWeather();
    }

    @Override
    public void resetPlayerWeather() {
        playerWeather = null;
        sendWeather();
        sendRainDensity();
        sendSkyDarkness();
    }

    public void sendWeather() {
        boolean stormy = playerWeather == null ? getWorld().hasStorm() : playerWeather == WeatherType.DOWNFALL;
        session.send(new StateChangeMessage(stormy ? Reason.START_RAIN : Reason.STOP_RAIN, 0));
    }

    public void sendRainDensity() {
        session.send(new StateChangeMessage(Reason.RAIN_DENSITY, getWorld().getRainDensity()));
    }

    public void sendSkyDarkness() {
        session.send(new StateChangeMessage(Reason.SKY_DARKNESS, getWorld().getSkyDarkness()));
    }

    ////////////////////////////////////////////////////////////////////////////
    // Player visibility

    @Override
    public void hidePlayer(Player player) {
        checkNotNull(player, "player cannot be null");
        if (equals(player) || !player.isOnline() || !session.isActive()) return;
        if (hiddenEntities.contains(player.getUniqueId())) return;

        hiddenEntities.add(player.getUniqueId());
        if (knownEntities.remove(player)) {
            session.send(new DestroyEntitiesMessage(Arrays.asList(player.getEntityId())));
        }
        session.send(UserListItemMessage.removeOne(player.getUniqueId()));
    }

    @Override
    public void showPlayer(Player player) {
        checkNotNull(player, "player cannot be null");
        if (equals(player) || !player.isOnline() || !session.isActive()) return;
        if (!hiddenEntities.contains(player.getUniqueId())) return;

        hiddenEntities.remove(player.getUniqueId());
        session.send(new UserListItemMessage(UserListItemMessage.Action.ADD_PLAYER, ((GlowPlayer) player).getUserListEntry()));
    }

    @Override
    public boolean canSee(Player player) {
        return !hiddenEntities.contains(player.getUniqueId());
    }

    /**
     * Called when a player hidden to this player disconnects.
     * This is necessary so the player is visible again after they reconnected.
     *
     * @param player The disconnected player
     */
    public void stopHidingDisconnectedPlayer(Player player) {
        hiddenEntities.remove(player.getUniqueId());
    }

    ////////////////////////////////////////////////////////////////////////////
    // Scoreboard

    @Override
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    @Override
    public void setScoreboard(Scoreboard scoreboard) throws IllegalArgumentException, IllegalStateException {
        checkNotNull(scoreboard, "Scoreboard must not be null");
        if (!(scoreboard instanceof GlowScoreboard)) {
            throw new IllegalArgumentException("Scoreboard must be GlowScoreboard");
        }
        if (this.scoreboard == null) {
            throw new IllegalStateException("Player has not loaded or is already offline");
        }
        this.scoreboard.unsubscribe(this);
        this.scoreboard = (GlowScoreboard) scoreboard;
        this.scoreboard.subscribe(this);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Conversable

    @Override
    public boolean isConversing() {
        return false;
    }

    @Override
    public void acceptConversationInput(String input) {

    }

    @Override
    public boolean beginConversation(Conversation conversation) {
        return false;
    }

    @Override
    public void abandonConversation(Conversation conversation) {

    }

    @Override
    public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {

    }

    ////////////////////////////////////////////////////////////////////////////
    // Plugin messages

    @Override
    public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        StandardMessenger.validatePluginMessage(getServer().getMessenger(), source, channel, message);
        if (listeningChannels.contains(channel)) {
            // only send if player is listening for it
            session.send(new PluginMessage(channel, message));
        }
    }

    @Override
    public Set<String> getListeningPluginChannels() {
        return Collections.unmodifiableSet(listeningChannels);
    }

    /**
     * Add a listening channel to this player.
     *
     * @param channel The channel to add.
     */
    public void addChannel(String channel) {
        checkArgument(listeningChannels.size() < 128, "Cannot add more than 127 channels!");
        if (listeningChannels.add(channel)) {
            EventFactory.callEvent(new PlayerRegisterChannelEvent(this, channel));
        }
    }

    /**
     * Remove a listening channel from this player.
     *
     * @param channel The channel to remove.
     */
    public void removeChannel(String channel) {
        if (listeningChannels.remove(channel)) {
            EventFactory.callEvent(new PlayerUnregisterChannelEvent(this, channel));
        }
    }

    /**
     * Send the supported plugin channels to the client.
     */
    private void sendSupportedChannels() {
        Set<String> listening = server.getMessenger().getIncomingChannels();

        if (!listening.isEmpty()) {
            // send NUL-separated list of channels we support
            ByteBuf buf = Unpooled.buffer(16 * listening.size());
            for (String channel : listening) {
                buf.writeBytes(channel.getBytes(StandardCharsets.UTF_8));
                buf.writeByte(0);
            }
            session.send(new PluginMessage("REGISTER", buf.array()));
            buf.release();
        }
    }

    public void enchanted(int clicked) {
        level -= clicked + 1;
        if (level < 0) {
            level = 0;
            experience = 0;
            totalExperience = 0;
        }
        setLevel(level);
        setXpSeed(new Random().nextInt()); //TODO use entity's random instance?
    }

    ////////////////////////////////////////////////////////////////////////////
    // Titles

    @Override
    public Title getTitle() {
        return currentTitle.clone();
    }

    @Override
    public void clearTitle() {
        session.send(new TitleMessage(Action.CLEAR));
    }

    @Override
    public void resetTitle() {
        currentTitle = new Title("");
        session.send(new TitleMessage(Action.RESET));
    }

    public GlowBlock getDigging() {
        return digging;
    }

    public void setDigging(GlowBlock block) {
        digging = block;
    }

    @Override
    public AttributeInstance getAttribute(Attribute attribute) {
        return null;
    }
}
