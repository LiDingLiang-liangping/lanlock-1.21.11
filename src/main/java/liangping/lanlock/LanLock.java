package liangping.lanlock;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class LanLock implements ModInitializer {
    public static final String MOD_ID = "lanlock";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private PasswordManager passwordManager;
    private final Map<UUID, AuthState> authStates = new HashMap<>();
    private final Set<UUID> authenticatedPlayers = new HashSet<>();
    private final Set<UUID> warnedPlayers = new HashSet<>();
    private UUID hostPlayerUuid = null;
    private boolean hostDetected = false;
    private boolean readyMessageSent = false;

    private enum AuthState {
        WAITING_REGISTER,
        WAITING_LOGIN,
        AUTHENTICATED
    }

    @Override
    public void onInitialize() {
        LOGGER.info("[{}] v2.0 模组已加载", MOD_ID);
        
        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(this::onPlayerDisconnect);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        registerCommands();
        registerInteractionBlocks();
        
        LOGGER.info("[{}] 初始化完成", MOD_ID);
        
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD.register((server, world) -> {
            if (!readyMessageSent) {
                Component msg = Component.literal("§a[LanLock] §f已就绪 | 密码验证 | 房主创造 | 禁用/give");
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.sendSystemMessage(msg);
                }
                readyMessageSent = true;
            }
        });
    }
    
    private void registerInteractionBlocks() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && !isAuthenticated(serverPlayer)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
            if (player instanceof ServerPlayer serverPlayer && !isAuthenticated(serverPlayer)) {
                return false;
            }
            return true;
        });
        
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && !isAuthenticated(serverPlayer)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && !isAuthenticated(serverPlayer)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && !isAuthenticated(serverPlayer)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }
    
    private boolean isAuthenticated(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (uuid.equals(hostPlayerUuid)) return true;
        AuthState state = authStates.getOrDefault(uuid, AuthState.AUTHENTICATED);
        return state == AuthState.AUTHENTICATED;
    }
    
    private void onPlayerJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        UUID uuid = player.getUUID();
        
        if (!hostDetected) {
            if (server.getPlayerList().getPlayerCount() == 1 || isWorldOwner(player)) {
                hostPlayerUuid = uuid;
                hostDetected = true;
                LOGGER.info("检测到房主: {} ({})", player.getName().getString(), uuid);
            }
        }
        
        if (uuid.equals(hostPlayerUuid)) {
            authenticatedPlayers.add(uuid);
            authStates.put(uuid, AuthState.AUTHENTICATED);
            player.sendSystemMessage(Component.literal("§a[系统] 房主身份已确认，无需密码验证"));
            return;
        }
        
        if (passwordManager == null) {
            passwordManager = new PasswordManager(server);
        }
        
        if (passwordManager.isRegistered(uuid)) {
            authStates.put(uuid, AuthState.WAITING_LOGIN);
            player.sendSystemMessage(Component.literal("§e[系统] 请输入密码登录，使用 /login <密码>"));
        } else {
            authStates.put(uuid, AuthState.WAITING_REGISTER);
            player.sendSystemMessage(Component.literal("§e[系统] 首次加入，请设置密码：/register <密码> <确认密码>"));
        }
    }
    
    private boolean isWorldOwner(ServerPlayer player) {
        String ip = player.getIpAddress();
        return ip.equals("local") || ip.isEmpty() || ip.equals("127.0.0.1");
    }
    
    private void onPlayerDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        UUID uuid = handler.getPlayer().getUUID();
        authStates.remove(uuid);
        authenticatedPlayers.remove(uuid);
        warnedPlayers.remove(uuid);
        
        if (uuid.equals(hostPlayerUuid)) {
            hostPlayerUuid = null;
            hostDetected = false;
            LOGGER.info("房主已断开，重置房主检测");
        }
    }
    
    private void onServerTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            AuthState state = authStates.getOrDefault(uuid, AuthState.AUTHENTICATED);
            
            if (state != AuthState.AUTHENTICATED && !uuid.equals(hostPlayerUuid)) {
                player.setDeltaMovement(0, 0, 0);
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 255, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 40, 255, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 255, false, false, false));
                
                if (server.getTickCount() % 40 == 0) {
                    if (state == AuthState.WAITING_REGISTER) {
                        player.sendSystemMessage(Component.literal("§c[系统] 请先注册：/register <密码> <确认密码>"));
                    } else if (state == AuthState.WAITING_LOGIN) {
                        player.sendSystemMessage(Component.literal("§c[系统] 请先登录：/login <密码>"));
                    }
                }
            }
            
            if (uuid.equals(hostPlayerUuid)) continue;
            
            if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
                player.setGameMode(GameType.SURVIVAL);
                if (!warnedPlayers.contains(uuid)) {
                    player.sendSystemMessage(Component.literal("§c[系统] 创造模式已被禁用，已自动切换为生存模式！"));
                    warnedPlayers.add(uuid);
                    LOGGER.info("玩家 {} 尝试进入创造模式，已被强制切回生存", player.getName().getString());
                }
            }
        }
    }
    
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            
            dispatcher.register(Commands.literal("register")
                .executes(context -> {
                    context.getSource().sendFailure(Component.literal("§c用法: /register <密码> <确认密码>"));
                    return 0;
                })
                .then(Commands.argument("password", StringArgumentType.word())
                    .then(Commands.argument("confirm", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            UUID uuid = player.getUUID();
                            String pass = StringArgumentType.getString(context, "password");
                            String confirm = StringArgumentType.getString(context, "confirm");
                            
                            if (authStates.get(uuid) != AuthState.WAITING_REGISTER) {
                                player.sendSystemMessage(Component.literal("§c[系统] 你已经注册过了，请使用 /login 登录"));
                                return 0;
                            }
                            if (!pass.equals(confirm)) {
                                player.sendSystemMessage(Component.literal("§c[系统] 两次输入的密码不一致！"));
                                return 0;
                            }
                            if (pass.length() < 4) {
                                player.sendSystemMessage(Component.literal("§c[系统] 密码长度至少4位！"));
                                return 0;
                            }
                            
                            passwordManager.register(uuid, pass);
                            authStates.put(uuid, AuthState.AUTHENTICATED);
                            authenticatedPlayers.add(uuid);
                            
                            player.removeEffect(MobEffects.SLOWNESS);
                            player.removeEffect(MobEffects.MINING_FATIGUE);
                            player.removeEffect(MobEffects.WEAKNESS);
                            
                            player.sendSystemMessage(Component.literal("§a[系统] 注册成功！欢迎加入！"));
                            LOGGER.info("玩家 {} 注册成功", player.getName().getString());
                            return 1;
                        })
                    )
                )
            );
            
            dispatcher.register(Commands.literal("login")
                .executes(context -> {
                    context.getSource().sendFailure(Component.literal("§c用法: /login <密码>"));
                    return 0;
                })
                .then(Commands.argument("password", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player == null) return 0;
                        UUID uuid = player.getUUID();
                        String pass = StringArgumentType.getString(context, "password");
                        
                        if (authStates.get(uuid) != AuthState.WAITING_LOGIN) {
                            player.sendSystemMessage(Component.literal("§c[系统] 你不需要登录"));
                            return 0;
                        }
                        if (passwordManager.verifyPassword(uuid, pass)) {
                            authStates.put(uuid, AuthState.AUTHENTICATED);
                            authenticatedPlayers.add(uuid);
                            
                            player.removeEffect(MobEffects.SLOWNESS);
                            player.removeEffect(MobEffects.MINING_FATIGUE);
                            player.removeEffect(MobEffects.WEAKNESS);
                            
                            player.sendSystemMessage(Component.literal("§a[系统] 登录成功！欢迎回来！"));
                            LOGGER.info("玩家 {} 登录成功", player.getName().getString());
                            return 1;
                        } else {
                            player.sendSystemMessage(Component.literal("§c[系统] 密码错误！请重试"));
                            return 0;
                        }
                    })
                )
            );
            
            dispatcher.register(Commands.literal("give")
                .executes(context -> {
                    context.getSource().sendFailure(Component.literal("§c[系统] /give 指令已被禁用！"));
                    return 0;
                })
                .then(Commands.argument("targets", EntityArgument.players())
                    .then(Commands.argument("item", ItemArgument.item(registryAccess))
                        .executes(context -> {
                            context.getSource().sendFailure(Component.literal("§c[系统] /give 指令已被禁用！"));
                            return 0;
                        })
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                context.getSource().sendFailure(Component.literal("§c[系统] /give 指令已被禁用！"));
                                return 0;
                            })
                        )
                    )
                )
            );
            
            dispatcher.register(Commands.literal("gamemode")
                .then(Commands.literal("creative")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player != null && player.getUUID().equals(hostPlayerUuid)) {
                            player.setGameMode(GameType.CREATIVE);
                            player.sendSystemMessage(Component.literal("§a[系统] 房主身份，创造模式已切换"));
                            return 1;
                        }
                        context.getSource().sendFailure(Component.literal("§c[系统] 创造模式已被禁用！"));
                        return 0;
                    })
                )
                .then(Commands.literal("1")
                    .executes(context -> {
                        context.getSource().sendFailure(Component.literal("§c[系统] 创造模式已被禁用！"));
                        return 0;
                    })
                )
            );
        });
    }
}
