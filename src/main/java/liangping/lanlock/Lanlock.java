package xiaoming.lanlock;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class LanLock implements DedicatedServerModInitializer {
    public static final String MOD_ID = "lanlock";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private PasswordManager passwordManager;
    private final Map<UUID, AuthState> authStates = new HashMap<>();
    private final Set<UUID> authenticatedPlayers = new HashSet<>();
    private final Set<UUID> warnedPlayers = new HashSet<>();
    private UUID hostPlayerUuid = null;
    
    private enum AuthState {
        WAITING_REGISTER,
        WAITING_LOGIN,
        AUTHENTICATED
    }

    @Override
    public void onInitializeServer() {
        LOGGER.info("[{}] v2.0 模组已加载", MOD_ID);
        
        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(this::onPlayerDisconnect);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        registerCommands();
        
        LOGGER.info("[{}] 初始化完成", MOD_ID);
    }
    
    private void onPlayerJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        ServerPlayerEntity player = handler.getPlayer();
        UUID uuid = player.getUuid();
        
        if (hostPlayerUuid == null && isLocalHost(player)) {
            hostPlayerUuid = uuid;
            LOGGER.info("检测到房主: {}", player.getName().getString());
        }
        
        if (uuid.equals(hostPlayerUuid)) {
            authenticatedPlayers.add(uuid);
            authStates.put(uuid, AuthState.AUTHENTICATED);
            player.sendMessage(Text.literal("§a[系统] 房主身份已确认，无需密码验证"), false);
            return;
        }
        
        if (passwordManager == null) {
            passwordManager = new PasswordManager(server);
        }
        
        if (passwordManager.isRegistered(uuid)) {
            authStates.put(uuid, AuthState.WAITING_LOGIN);
            player.sendMessage(Text.literal("§e[系统] 请输入密码登录，使用 /login <密码>"), false);
        } else {
            authStates.put(uuid, AuthState.WAITING_REGISTER);
            player.sendMessage(Text.literal("§e[系统] 首次加入，请设置密码：/register <密码> <确认密码>"), false);
        }
    }
    
    private void onPlayerDisconnect(ServerPlayNetworkHandler handler, MinecraftServer server) {
        UUID uuid = handler.getPlayer().getUuid();
        authStates.remove(uuid);
        authenticatedPlayers.remove(uuid);
        warnedPlayers.remove(uuid);
        if (uuid.equals(hostPlayerUuid)) {
            hostPlayerUuid = null;
        }
    }
    
    private void onServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            AuthState state = authStates.getOrDefault(uuid, AuthState.AUTHENTICATED);
            
            if (state != AuthState.AUTHENTICATED && !uuid.equals(hostPlayerUuid)) {
                player.setVelocity(0, 0, 0);
                if (server.getTicks() % 40 == 0) {
                    if (state == AuthState.WAITING_REGISTER) {
                        player.sendMessage(Text.literal("§c[系统] 请先注册：/register <密码> <确认密码>"), false);
                    } else if (state == AuthState.WAITING_LOGIN) {
                        player.sendMessage(Text.literal("§c[系统] 请先登录：/login <密码>"), false);
                    }
                }
            }
            
            if (uuid.equals(hostPlayerUuid)) continue;
            
            if (player.interactionManager.getGameMode() == GameMode.CREATIVE) {
                player.changeGameMode(GameMode.SURVIVAL);
                if (!warnedPlayers.contains(uuid)) {
                    player.sendMessage(Text.literal("§c[系统] 创造模式已被禁用，已自动切换为生存模式！"), false);
                    warnedPlayers.add(uuid);
                    LOGGER.info("玩家 {} 尝试进入创造模式，已被强制切回生存", player.getName().getString());
                }
            }
        }
    }
    
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            
            dispatcher.register(CommandManager.literal("register")
                .executes(context -> {
                    context.getSource().sendError(Text.literal("§c用法: /register <密码> <确认密码>"));
                    return 0;
                })
                .then(CommandManager.argument("password", net.minecraft.command.argument.StringArgumentType.word())
                    .then(CommandManager.argument("confirm", net.minecraft.command.argument.StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            UUID uuid = player.getUuid();
                            String pass = net.minecraft.command.argument.StringArgumentType.getString(context, "password");
                            String confirm = net.minecraft.command.argument.StringArgumentType.getString(context, "confirm");
                            
                            if (authStates.get(uuid) != AuthState.WAITING_REGISTER) {
                                player.sendMessage(Text.literal("§c[系统] 你已经注册过了，请使用 /login 登录"), false);
                                return 0;
                            }
                            if (!pass.equals(confirm)) {
                                player.sendMessage(Text.literal("§c[系统] 两次输入的密码不一致！"), false);
                                return 0;
                            }
                            if (pass.length() < 4) {
                                player.sendMessage(Text.literal("§c[系统] 密码长度至少4位！"), false);
                                return 0;
                            }
                            
                            passwordManager.register(uuid, pass);
                            authStates.put(uuid, AuthState.AUTHENTICATED);
                            authenticatedPlayers.add(uuid);
                            player.sendMessage(Text.literal("§a[系统] 注册成功！欢迎加入！"), false);
                            LOGGER.info("玩家 {} 注册成功", player.getName().getString());
                            return 1;
                        })
                    )
                )
            );
            
            dispatcher.register(CommandManager.literal("login")
                .executes(context -> {
                    context.getSource().sendError(Text.literal("§c用法: /login <密码>"));
                    return 0;
                })
                .then(CommandManager.argument("password", net.minecraft.command.argument.StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player == null) return 0;
                        UUID uuid = player.getUuid();
                        String pass = net.minecraft.command.argument.StringArgumentType.getString(context, "password");
                        
                        if (authStates.get(uuid) != AuthState.WAITING_LOGIN) {
                            player.sendMessage(Text.literal("§c[系统] 你不需要登录"), false);
                            return 0;
                        }
                        if (passwordManager.verifyPassword(uuid, pass)) {
                            authStates.put(uuid, AuthState.AUTHENTICATED);
                            authenticatedPlayers.add(uuid);
                            player.sendMessage(Text.literal("§a[系统] 登录成功！欢迎回来！"), false);
                            LOGGER.info("玩家 {} 登录成功", player.getName().getString());
                            return 1;
                        } else {
                            player.sendMessage(Text.literal("§c[系统] 密码错误！请重试"), false);
                            return 0;
                        }
                    })
                )
            );
            
            dispatcher.register(CommandManager.literal("give")
                .executes(context -> {
                    context.getSource().sendError(Text.literal("§c[系统] /give 指令已被禁用！"));
                    return 0;
                })
                .then(CommandManager.argument("targets", net.minecraft.command.argument.EntityArgumentType.players())
                    .then(CommandManager.argument("item", net.minecraft.command.argument.ItemStackArgumentType.itemStack(registryAccess))
                        .executes(context -> {
                            context.getSource().sendError(Text.literal("§c[系统] /give 指令已被禁用！"));
                            return 0;
                        })
                        .then(CommandManager.argument("count", net.minecraft.command.argument.IntegerArgumentType.integer(1))
                            .executes(context -> {
                                context.getSource().sendError(Text.literal("§c[系统] /give 指令已被禁用！"));
                                return 0;
                            })
                        )
                    )
                )
            );
            
            dispatcher.register(CommandManager.literal("gamemode")
                .then(CommandManager.literal("creative")
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player != null && player.getUuid().equals(hostPlayerUuid)) {
                            player.changeGameMode(GameMode.CREATIVE);
                            player.sendMessage(Text.literal("§a[系统] 房主身份，创造模式已切换"), false);
                            return 1;
                        }
                        context.getSource().sendError(Text.literal("§c[系统] 创造模式已被禁用！"));
                        return 0;
                    })
                )
                .then(CommandManager.literal("1")
                    .executes(context -> {
                        context.getSource().sendError(Text.literal("§c[系统] 创造模式已被禁用！"));
                        return 0;
                    })
                )
            );
        });
    }
    
    private boolean isLocalHost(ServerPlayerEntity player) {
        String ip = player.getIp();
        return ip.equals("127.0.0.1") || ip.equals("localhost") || ip.startsWith("192.168.") || ip.startsWith("10.");
    }
}