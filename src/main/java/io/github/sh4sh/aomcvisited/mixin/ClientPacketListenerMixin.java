package io.github.sh4sh.aomcvisited.mixin;

import io.github.sh4sh.aomcvisited.AomcVisitedClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
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

	/*
	 * Both handlers start by re-queuing themselves on the main thread (ensureRunningOnSameThread throws on
	 * the network thread), so reaching TAIL means we're on the main thread and the client world is updated.
	 */

	@Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
	private void aomcVisited$chunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		AomcVisitedClient.VISITED.chunkLoaded((ClientPacketListener) (Object) this, level, packet.x(), packet.z());
	}

	@Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
	private void aomcVisited$chunkUnloaded(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
		AomcVisitedClient.VISITED.chunkUnloaded(level, packet.pos().x(), packet.pos().z());
	}
}
