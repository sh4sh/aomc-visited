package io.github.sh4sh.aomcvisited.mixin;

import io.github.sh4sh.aomcvisited.AomcVisitedClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Shadow
	private ClientLevel level;

	/**
	 * Records each chunk the server sends. The handler starts by re-queuing itself on the main thread
	 * (ensureRunningOnSameThread throws on the network thread), so reaching TAIL means we're on the main
	 * thread and the chunk is in the client world.
	 */
	@Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
	private void aomcVisited$recordChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		AomcVisitedClient.VISITED.record((ClientPacketListener) (Object) this, level.dimension(), packet.x(), packet.z());
	}
}
