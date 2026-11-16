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
import org.bukkit.scheduler.BukkitRunnable;
import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class BastionMCRTP extends JavaPlugin implements Listener, CommandExecutor {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private FileConfiguration messages, menus;
    private final Map<UUID, Location> pos1 = new HashMap<>(), pos2 = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> inside = new HashSet<>(), busy = new HashSet<>();
    private final Map<UUID, String> openMenus = new HashMap<>();
    private final Map<String, Deque<Location>> recent = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig(); saveResourceIfMissing("messages.yml"); saveResourceIfMissing("menus.yml"); loadFiles();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("bastionrtp")).setExecutor(this);
    }
    private void saveResourceIfMissing(String name) { if (!new File(getDataFolder(),name).exists()) saveResource(name,false); }
    private void loadFiles() {
        messages=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"messages.yml"));
        menus=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"menus.yml"));
    }
    private Component render(String raw) { try { return mm.deserialize(raw); } catch(Exception e) { return Component.text(raw); } }
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
    private void openMenu(Player p) {
        int size=menus.getInt("size",27); if(size<9||size>54||size%9!=0) size=27;
        Inventory inv=Bukkit.createInventory(null,size,render(menus.getString("title","<aqua>BASTION RTP")));
        for(String dimension:List.of("overworld","nether","end")) {
            String path="items."+dimension+".";
            Material mat=Material.matchMaterial(menus.getString(path+"material","STONE")); if(mat==null) mat=Material.STONE;
            ItemStack stack=new ItemStack(mat); ItemMeta meta=stack.getItemMeta();
            meta.displayName(render(menus.getString(path+"name",dimension)));
            meta.lore(menus.getStringList(path+"lore").stream().map(this::render).toList());
            stack.setItemMeta(meta);
            int slot=menus.getInt(path+"slot",0); if(slot>=0&&slot<size) inv.setItem(slot,stack);
        }
        openMenus.put(p.getUniqueId(),"rtp"); p.openInventory(inv);
    }
    @EventHandler public void onClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p) || !"rtp".equals(openMenus.get(p.getUniqueId()))) return;
        if(e.getView().getTopInventory()!=e.getClickedInventory()) { e.setCancelled(true); return; }
        e.setCancelled(true);
        for(String dimension:List.of("overworld","nether","end")) {
            if(e.getRawSlot()==menus.getInt("items."+dimension+".slot",-1)) {
                openMenus.remove(p.getUniqueId()); p.closeInventory(); teleportRandom(p,dimension); return;
            }
        }
    }
    @EventHandler public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) { openMenus.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { UUID id=e.getPlayer().getUniqueId(); inside.remove(id);busy.remove(id);openMenus.remove(id); }
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
        if(w==null) {msg(p,"world-missing");return;}
        int min=getConfig().getInt("rtp."+dim+".min-distance"),max=getConfig().getInt("rtp."+dim+".max-distance");
        if(min<0||max<=min) {msg(p,"teleport-failed");return;}
        busy.add(id);msg(p,"searching");
        new BukkitRunnable() {
            int tries=0;
            @Override public void run() {
                if(!p.isOnline()||!busy.contains(id)) {busy.remove(id);cancel();return;}
                if(tries++>=Math.max(1,getConfig().getInt("rtp.max-attempts",35))) {busy.remove(id);msg(p,"no-safe-location");cancel();return;}
                ThreadLocalRandom random=ThreadLocalRandom.current();
                double theta=random.nextDouble(0,2*Math.PI);
                double radius=Math.sqrt(random.nextDouble((double)min*min,(double)max*max));
                int x=(int)Math.round(Math.cos(theta)*radius),z=(int)Math.round(Math.sin(theta)*radius);
                if(!w.getWorldBorder().isInside(new Location(w,x+0.5,Math.max(w.getMinHeight(),64),z+0.5))) return;
                // Chunk generation is deliberately bounded to one attempt every 2 ticks.
                w.getChunkAtAsync(x>>4,z>>4,true).thenAccept(chunk -> Bukkit.getScheduler().runTask(BastionMCRTP.this,()->{
                    if(!p.isOnline()||!busy.contains(id)) return;
                    Location target=findSafe(w,dim,x,z);
                    if(target==null) return;
                    Deque<Location> history=recent.computeIfAbsent(dim,k->new ArrayDeque<>());
                    if(history.stream().anyMatch(loc->loc.distanceSquared(target)<90000)) return;
                    busy.remove(id);cancel();
                    p.teleportAsync(target).thenAccept(success->Bukkit.getScheduler().runTask(BastionMCRTP.this,()->{
                        if(!p.isOnline()) return;
                        if(success) {
                            cooldown.put(id,System.currentTimeMillis());history.addLast(target);
                            while(history.size()>40)history.removeFirst();
                            msg(p,"teleport-success","world",dim,"x",Integer.toString(x),"z",Integer.toString(z));
                        } else msg(p,"teleport-failed");
                    }));
                })).exceptionally(ex->{getLogger().warning("Chunk RTP: "+ex.getMessage());return null;});
            }
        }.runTaskTimer(this,1L,2L);
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
