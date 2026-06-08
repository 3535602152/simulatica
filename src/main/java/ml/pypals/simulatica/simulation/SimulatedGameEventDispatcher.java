package ml.pypals.simulatica.simulation;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debug.DebugGameEventInfo;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.level.gameevent.*;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class SimulatedGameEventDispatcher extends GameEventDispatcher {
    private final ServerLevel level;
    public GameEventListenerRegistry gameEventListenerRegistry;
    public SimulatedGameEventDispatcher(ServerLevel serverLevel) {
        super(serverLevel);
        level = serverLevel;
        gameEventListenerRegistry = new EuclideanGameEventListenerRegistry(
                serverLevel, 0, (i)->{}
        );
    }

    public void post(Holder<GameEvent> holder, @NonNull Vec3 vec3, GameEvent.@NonNull Context context) {
        int i = holder.value().notificationRadius();
        BlockPos blockPos = BlockPos.containing(vec3);
        int j = SectionPos.blockToSectionCoord(blockPos.getX() - i);
        int k = SectionPos.blockToSectionCoord(blockPos.getY() - i);
        int l = SectionPos.blockToSectionCoord(blockPos.getZ() - i);
        int m = SectionPos.blockToSectionCoord(blockPos.getX() + i);
        int n = SectionPos.blockToSectionCoord(blockPos.getY() + i);
        int o = SectionPos.blockToSectionCoord(blockPos.getZ() + i);
        List<GameEvent.ListenerInfo> list = new ArrayList<>();
        GameEventListenerRegistry.ListenerVisitor listenerVisitor = (gameEventListener, vec32) -> {
            if (gameEventListener.getDeliveryMode() == GameEventListener.DeliveryMode.BY_DISTANCE) {
                list.add(new GameEvent.ListenerInfo(holder, vec3, context, gameEventListener, vec32));
            } else {
                gameEventListener.handleGameEvent(this.level, holder, context, vec3);
            }
        };
        boolean bl = gameEventListenerRegistry.visitInRangeListeners(holder, vec3, context, listenerVisitor);

        if (!list.isEmpty()) {
            this.handleGameEventMessagesInQueue(list);
        }

        if (bl) {
            this.level.debugSynchronizers().broadcastEventToTracking(BlockPos.containing(vec3), DebugSubscriptions.GAME_EVENTS, new DebugGameEventInfo(holder, vec3));
        }
    }

    private void handleGameEventMessagesInQueue(List<GameEvent.ListenerInfo> list) {
        Collections.sort(list);

        for (GameEvent.ListenerInfo listenerInfo : list) {
            GameEventListener gameEventListener = listenerInfo.recipient();
            gameEventListener.handleGameEvent(this.level, listenerInfo.gameEvent(), listenerInfo.context(), listenerInfo.source());
        }
    }

}