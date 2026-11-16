package mc.bastion.rtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BastionMCRTP extends JavaPlugin implements Listener, CommandExecutor {
    private final MiniMessage mm = MiniMessage.miniMessage();
    // Support both MiniMessage and the common &#RRGGBB&l legacy RGB syntax.
    private static final Pattern LEGACY_CODES = Pattern.compile("(?i)&#([0-9a-f]{6})|&([0-9a-fk-or])");
    private FileConfiguration messages, menus;
    private final Map<UUID, Location> pos1 = new HashMap<>(), pos2 = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> inside = new HashSet<>(), busy = new HashSet<>();
    private final Map<UUID, String> openMenus = new HashMap<>();
    private final Map<String, Deque<Location>> recent = new HashMap<>();
    private final Set<UUID> activeSearches = new HashSet<>();
    private final Map<UUID, Map<PotionEffectType, PotionEffect>> previousEffects = new HashMap<>();
    private final Set<UUID> portalEffectActive = new HashSet<>();
    private final Map<UUID, Set<PotionEffectType>> appliedEffects = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig(); saveResourceIfMissing("messages.yml"); saveResourceIfMissing("menus.yml"); loadFiles();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("bastionrtp")).setExecutor(this);
    }
    @Override public void onDisable() {
        for (UUID id : new HashSet<>(portalEffectActive)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) clearPortalEffects(p);
        }
        activeSearches.clear(); busy.clear(); inside.clear(); openMenus.clear();
    }
    private void saveResourceIfMissing(String name) { if (!new File(getDataFolder(),name).exists()) saveResource(name,false); }
    private void loadFiles() {
        messages=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"messages.yml"));
        menus=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"menus.yml"));
    }
    private Component render(String raw) {
        if (raw == null) return Component.empty();
        // Convert hex/legacy codes into MiniMessage tags, retaining MiniMessage gradient support.
        Matcher matcher = LEGACY_CODES.matcher(raw);
        StringBuffer converted = new StringBuffer();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = "<#" + matcher.group(1) + ">";
            } else {
                replacement = switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> matcher.group();
                };
            }
            matcher.appendReplacement(converted, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(converted);
        try { return mm.deserialize(converted.toString()); }
        catch (Exception e) { return Component.text(raw); }
    }
    private void msg(Player p,String key,String... vars) {
        String raw=messages.getString("prefix","")+messages.getString(key,key);
        for(int i=0;i+1<vars.length;i+=2) raw=raw.replace("{"+vars[i]+"}",vars[i+1]);
        p.sendMessage(render(raw));
    }
    private boolean isWater(Block block) { return block.getType()==Material.WATER || block.getType()==Material.BUBBLE_COLUMN; }
    private boolean inPortal(Location loc) {
        if(loc==null || !loc.getWorld().getName().equals(getConfig().getString("portal.world",""))) return false;
        List<Integer> a=getConfig().getIntegerList("portal.pos1"), b=getConfig().getIntegerList("portal.pos2");
        if(a.size()!=3 || b.size()!=3) return false;
        int x=loc.getBlockX(),y=loc.getBlockY(),z=loc.getBlockZ();
        return x>=Math.min(a.get(0),b.get(0))&&x<=Math.max(a.get(0),b.get(0))
            &&y>=Math.min(a.get(1),b.get(1))&&y<=Math.max(a.get(1),b.get(1))
            &&z>=Math.min(a.get(2),b.get(2))&&z<=Math.max(a.get(2),b.get(2));
    }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if(e.getTo()==null || (e.getFrom().getBlockX()==e.getTo().getBlockX()&&e.getFrom().getBlockY()==e.getTo().getBlockY()&&e.getFrom().getBlockZ()==e.getTo().getBlockZ())) return;
        Player p=e.getPlayer(); UUID id=p.getUniqueId();
        boolean active=inPortal(e.getTo()) && isWater(e.getTo().getBlock());
        if(!active) { inside.remove(id); return; }
        if(inside.add(id) && p.hasPermission("bastionrtp.use")) openMenu(p);
    }
    private void applyPortalEffects(Player p) {
        if (!getConfig().getBoolean("portal-effects.enabled",true)) return;
        UUID id=p.getUniqueId();
        if (!portalEffectActive.add(id)) return;
        Map<PotionEffectType,PotionEffect> old=new HashMap<>();
        for (PotionEffectType type:List.of(PotionEffectType.DARKNESS,PotionEffectType.SLOWNESS)) {
            old.put(type,p.getPotionEffect(type));
        }
        previousEffects.put(id,old);
        Set<PotionEffectType> applied=new HashSet<>();
        appliedEffects.put(id,applied);
        int level=Math.max(1,Math.min(10,getConfig().getInt("portal-effects.slowness-level",5)));
        if (getConfig().getBoolean("portal-effects.darkness",true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,12000,0,false,false,false));
            applied.add(PotionEffectType.DARKNESS);
        }
        if (getConfig().getBoolean("portal-effects.slowness",true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,12000,level-1,false,false,false));
            applied.add(PotionEffectType.SLOWNESS);
        }
    }
    private void clearPortalEffects(Player p) {
        UUID id=p.getUniqueId();
        if (!portalEffectActive.remove(id)) return;
        Map<PotionEffectType,PotionEffect> old=previousEffects.remove(id);
        for (PotionEffectType type:appliedEffects.getOrDefault(id,Set.of())) {
            p.removePotionEffect(type);
            PotionEffect previous=old==null?null:old.get(type);
            if(previous!=null) p.addPotionEffect(previous);
        }
        appliedEffects.remove(id);
    }
    private void openMenu(Player p) {
        int size=menus.getInt("size",27); if(size<9||size>54||size%9!=0) size=27;
        Inventory inv=Bukkit.createInventory(null,size,render(menus.getString("title","<aqua>BASTION RTP")));
        for(String dimension:List.of("overworld","nether","end","exit")) {
            String path="items."+dimension+".";
            Material mat=Material.matchMaterial(menus.getString(path+"material",dimension.equals("exit")?"BARRIER":"STONE")); if(mat==null) mat=Material.STONE;
            ItemStack stack=new ItemStack(mat); ItemMeta meta=stack.getItemMeta();
            meta.displayName(render(menus.getString(path+"name",dimension.equals("exit")?"<red><bold>SALIR DEL MENU":dimension)));
            meta.lore(menus.getStringList(path+"lore").stream().map(this::render).toList());
            stack.setItemMeta(meta);
            int slot=menus.getInt(path+"slot",dimension.equals("exit")?size-1:0); if(slot>=0&&slot<size) inv.setItem(slot,stack);
        }
        openMenus.put(p.getUniqueId(),"rtp");
        p.openInventory(inv);
        applyPortalEffects(p);
    }
    @EventHandler public void onClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p) || !"rtp".equals(openMenus.get(p.getUniqueId()))) return;
        if(e.getView().getTopInventory()!=e.getClickedInventory()) { e.setCancelled(true); return; }
        e.setCancelled(true);
        if(e.getRawSlot()==menus.getInt("items.exit.slot",e.getView().getTopInventory().getSize()-1)) {
            p.closeInventory();
            return;
        }
        for(String dimension:List.of("overworld","nether","end")) {
            if(e.getRawSlot()==menus.getInt("items."+dimension+".slot",-1)) {
                p.closeInventory(); clearPortalEffects(p); teleportRandom(p,dimension); return;
            }
        }
    }
    @EventHandler public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        UUID id=e.getPlayer().getUniqueId();
        if (openMenus.remove(id)!=null && e.getPlayer() instanceof Player p) clearPortalEffects(p);
    }
    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { UUID id=e.getPlayer().getUniqueId(); inside.remove(id);busy.remove(id);activeSearches.remove(id);openMenus.remove(id);clearPortalEffects(e.getPlayer()); }
    private boolean hazardous(Material m) {
        return m==Material.LAVA || m==Material.MAGMA_BLOCK || m==Material.CACTUS || m==Material.FIRE || m==Material.SOUL_FIRE
            || m==Material.CAMPFIRE || m==Material.SOUL_CAMPFIRE || m==Material.POWDER_SNOW || m==Material.WITHER_ROSE;
    }
    private boolean safe(World w,int x,int y,int z) {
        if(y<=w.getMinHeight()+1 || y>=w.getMaxHeight()-2) return false;
        Block floor=w.getBlockAt(x,y-1,z), feet=w.getBlockAt(x,y,z), head=w.getBlockAt(x,y+1,z);
        Material base=floor.getType();
        return base.isSolid()&&!hazardous(base) && !floor.isLiquid() && feet.isPassable() && head.isPassable()
            && !feet.isLiquid()&&!head.isLiquid()&&!hazardous(feet.getType())&&!hazardous(head.getType());
    }
    private Location findSafe(World w,String dim,int x,int z) {
        if(dim.equals("overworld")) {
            int y=w.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
            return safe(w,x,y,z)?new Location(w,x+0.5,y,z+0.5):null;
        }
        // El End no tiene techo de bedrock: la heightmap evita escanear cientos de bloques vacios.
        if(dim.equals("end")) {
            int y=w.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
            return safe(w,x,y,z)?new Location(w,x+0.5,y,z+0.5):null;
        }
        int top=dim.equals("nether")?Math.min(120,w.getMaxHeight()-3):w.getMaxHeight()-3;
        int low=dim.equals("nether")?Math.max(35,w.getMinHeight()+2):Math.max(40,w.getMinHeight()+2);
        // Randomize scan start to avoid selecting the same cave layer each time.
        int span=Math.max(1,top-low+1), offset=ThreadLocalRandom.current().nextInt(span);
        for(int i=0;i<span;i++) {
            int y=low+(offset+i)%span;
            if(safe(w,x,y,z)) return new Location(w,x+0.5,y,z+0.5);
        }
        return null;
    }
    private void teleportRandom(Player p,String dim) {
        UUID id=p.getUniqueId();
        if(busy.contains(id)) { msg(p,"busy"); return; }
        long now=System.currentTimeMillis(), last=cooldown.getOrDefault(id,0L);
        long wait=Math.max(0,(getConfig().getLong("rtp.cooldown-seconds",300)*1000-(now-last)+999)/1000);
        if(wait>0&&!p.hasPermission("bastionrtp.bypasscooldown")&&!p.isOp()) {msg(p,"cooldown","seconds",Long.toString(wait));return;}
        World w=Bukkit.getWorld(getConfig().getString("worlds."+dim,""));
        if(w==null) {
            getLogger().warning("[RTP] Mundo no cargado: dimension="+dim+", configurado="+getConfig().getString("worlds."+dim));
            msg(p,"world-missing");return;
        }
        int min=getConfig().getInt("rtp."+dim+".min-distance"),max=getConfig().getInt("rtp."+dim+".max-distance");
        if(min<0||max<=min) {
            getLogger().warning("[RTP] Rango invalido para "+dim+": minimo="+min+", maximo="+max);
            msg(p,"teleport-failed");return;
        }
        int maximumConcurrent=Math.max(1,Math.min(8,getConfig().getInt("performance.max-concurrent-searches",2)));
        if(activeSearches.size()>=maximumConcurrent) { msg(p,"server-busy"); return; }
        busy.add(id);activeSearches.add(id);msg(p,"searching");
        int limit=Math.max(1,Math.min(200,getConfig().getInt("rtp.max-attempts",35)));
        getLogger().info("[RTP] Inicio: jugador="+p.getName()+", dimension="+dim+", mundo="+w.getName()+
                ", rango="+min+"-"+max+", intentos="+limit+", borde="+w.getWorldBorder().getSize());
        new SearchTask(p,id,w,dim,min,max,limit).next();
    }

    // Un solo chunk en vuelo por jugador: el contador avanza DESPUES de recibir el resultado.
    private final class SearchTask {
        final Player player;
        final UUID id;
        final World world;
        final String dimension;
        final int min,max,limit;
        int attempts,unsafe,outsideBorder,nearRecent,chunkErrors;
        boolean finished;

        SearchTask(Player player,UUID id,World world,String dimension,int min,int max,int limit) {
            this.player=player;this.id=id;this.world=world;this.dimension=dimension;
            this.min=min;this.max=max;this.limit=limit;
        }
        boolean active() {
            if(finished || !BastionMCRTP.this.isEnabled()) return false;
            if(!player.isOnline() || !busy.contains(id)) {
                finished=true;activeSearches.remove(id);busy.remove(id);return false;
            }
            return true;
        }
        void release() { activeSearches.remove(id);busy.remove(id); }
        void next() {
            if(!active()) return;
            if(attempts>=limit) {
                finished=true;release();
                getLogger().warning("[RTP] SIN DESTINO: jugador="+player.getName()+", dimension="+dimension+
                        ", mundo="+world.getName()+", intentos="+attempts+", terreno-inseguro="+unsafe+
                        ", fuera-del-borde="+outsideBorder+", cerca-de-RTP-reciente="+nearRecent+
                        ", errores-de-chunk="+chunkErrors+". Comprueba world border, terreno y consola.");
                msg(player,"no-safe-location");return;
            }
            attempts++;
            ThreadLocalRandom random=ThreadLocalRandom.current();
            double theta=random.nextDouble(0,2*Math.PI);
            double radius=Math.sqrt(random.nextDouble((double)min*min,(double)max*max));
            int x=(int)Math.round(Math.cos(theta)*radius), z=(int)Math.round(Math.sin(theta)*radius);
            if(!world.getWorldBorder().isInside(new Location(world,x+0.5,Math.max(world.getMinHeight(),64),z+0.5))) {
                outsideBorder++;scheduleNext();return;
            }
            try {
                world.getChunkAtAsync(x>>4,z>>4,true).whenComplete((chunk,error)-> {
                    Bukkit.getScheduler().runTask(BastionMCRTP.this,()-> {
                        if(!active()) return;
                        if(error!=null || chunk==null) {
                            chunkErrors++;
                            getLogger().warning("[RTP] Error cargando chunk en "+world.getName()+
                                    " x="+x+" z="+z+" intento="+attempts+": "+(error==null?"chunk nulo":error.toString()));
                            scheduleNext();return;
                        }
                        Location target;
                        try {target=findSafe(world,dimension,x,z);}
                        catch(Exception ex) {
                            chunkErrors++;
                            getLogger().warning("[RTP] Error evaluando terreno en x="+x+" z="+z+": "+ex);
                            scheduleNext();return;
                        }
                        if(target==null) {unsafe++;scheduleNext();return;}
                        Deque<Location> history=recent.computeIfAbsent(dimension,k->new ArrayDeque<>());
                        if(history.stream().anyMatch(loc->loc.getWorld().equals(world) && loc.distanceSquared(target)<90000)) {
                            nearRecent++;scheduleNext();return;
                        }
                        finished=true;
                        player.teleportAsync(target).whenComplete((success,tpError)->
                            Bukkit.getScheduler().runTask(BastionMCRTP.this,()-> {
                                release();
                                if(!player.isOnline()) return;
                                if(tpError==null && Boolean.TRUE.equals(success)) {
                                    cooldown.put(id,System.currentTimeMillis());
                                    history.addLast(target);
                                    while(history.size()>40)history.removeFirst();
                                    getLogger().info("[RTP] EXITO: "+player.getName()+" -> "+world.getName()+
                                            " x="+x+" y="+target.getBlockY()+" z="+z+" tras "+attempts+" intentos");
                                    msg(player,"teleport-success","world",dimension,"x",Integer.toString(x),"z",Integer.toString(z));
                                } else {
                                    getLogger().warning("[RTP] Fallo de teleportAsync para "+player.getName()+
                                            " a "+world.getName()+" x="+x+" z="+z+": "+tpError);
                                    msg(player,"teleport-failed");
                                }
                            })
                        );
                    });
                });
            } catch(Exception ex) {
                chunkErrors++;
                getLogger().warning("[RTP] No se pudo iniciar carga de chunk x="+x+" z="+z+": "+ex);
                scheduleNext();
            }
        }
        void scheduleNext() {
            if(active()) Bukkit.getScheduler().runTaskLater(BastionMCRTP.this,this::next,
                Math.max(2L,getConfig().getLong("performance.ticks-between-attempts",10L)));
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(!(sender instanceof Player p)) {sender.sendMessage("Solo jugadores.");return true;}
        if(!p.hasPermission("bastionrtp.admin")) {msg(p,"no-permission");return true;}
        if(args.length==0) {msg(p,"usage");return true;}
        UUID id=p.getUniqueId();
        switch(args[0].toLowerCase(Locale.ROOT)) {
            case "pos1", "pos2" -> {
                if(args[0].equalsIgnoreCase("pos1")) pos1.put(id,p.getLocation().clone()); else pos2.put(id,p.getLocation().clone());
                msg(p,"position","position",args[0]);
            }
            case "setportal" -> {
                Location a=pos1.get(id),b=pos2.get(id);
                if(a==null||b==null||!a.getWorld().equals(b.getWorld())) {msg(p,"portal-missing");break;}
                getConfig().set("portal.world",a.getWorld().getName());
                getConfig().set("portal.pos1",List.of(a.getBlockX(),a.getBlockY(),a.getBlockZ()));
                getConfig().set("portal.pos2",List.of(b.getBlockX(),b.getBlockY(),b.getBlockZ()));
                saveConfig();inside.clear();msg(p,"portal-saved");
            }
            case "reload" -> {reloadConfig();loadFiles();inside.clear();msg(p,"reloaded");}
            case "menu" -> openMenu(p);
            default -> msg(p,"usage");
        }
        return true;
    }
}
